"""Bedrock 2차 검열 러너 — pattern 통과분 S3 게시글별 `.json` in/out.

`docs/requirements/pipeline/two-stage-batch.md`(PIPE-2SB-*)·
`docs/requirements/bedrock/llm-validation.md`(BRK-LLM-*) 승인 계약의 구현체다.

입력: `validation/pattern/success/{source}/{date}/{postExternalId}.json`
     (사전·정규식 1차 검열 통과분만 — PIPE-2SB-4/5, community/**·validation/pattern/**
     은 읽기 전용).

흐름(게시글 단위 순차 처리, PIPE-2SB-8):
    1. 게시글의 본문 판정 단위(`body`, 비어 있으면 `title`)와 `topComments[].body`를
       모두 판정 단위로 모은다.
    2. "게시글 N개분"(PIPE-2SB-71) 단위로 여러 게시글의 단위를 묶어(이종 혼합,
       `unit_kind` 항목별 동반) `bedrock.judge_service.judge_batch()`에 위임한다
       (PIPE-2SB-14/15 — 판정 로직은 여기 없다).
    3. 배치 호출 **시작 전** 누적 비용(`bedrock_spend_usd`)을 확인해 상한 이상이면
       그 배치와 이후 남은 게시글을 모두 시작하지 않는다(PIPE-2SB-74/75).
    4. 응답을 게시글별로 되잘라 세 갈래로 라우팅한다(PIPE-2SB-11c):
       (1) 본문 통과 → 정상 success
       (2) 본문이 `abuse`로 폐기 → 게시글 전체 fail
       (3) 본문이 `spam`/`offtopic`으로 폐기 → 본문 자리 필드만 비운 success
           (통과 댓글 0개면 success 미생성 → (2)와 마찬가지로 failed만)

산출물(S3 키):
    - 성공: validation/bedrock/success/{source}/{date}/{postExternalId}.json
    - 실패: validation/bedrock/failed/{source}/{date}/{postExternalId}.json
    - 완결 마커: validation/bedrock/_manifest/{source}/{date}/{postExternalId}.json
    - shadow(BEDROCK_SHADOW=true 일 때만): validation/bedrock/_shadow/{source}/{date}/{postExternalId}.json
      (success/failed/마커/작업 집합 전부 건드리지 않음 — PIPE-2SB-70)

완료 처리 순서는 "S3 마커 기록 → Redis SREM"이다(PIPE-2SB-72). 대상 선정은 이번에도
S3 리스팅 + 마커 기준이다(Redis 목록을 읽어 처리하지 않는다).

⚠️ Redis 두 용도는 실패 취급이 **다르다**(`BatchRedis` 클래스 참고). `pending:bedrock`
Set(SREM/SADD)은 PIPE-2SB-73 그대로 정본이 아니라 최적화 수단이라 연결·명령 실패를
로그만 남기고 계속 진행한다(S3 마커가 정본이라 유실이 아니다). 반면 `bedrock_spend_usd`
**비용 카운터는 대체 정본이 없어**(PIPE-2SB-61) 접근 실패 시 명확한 에러로 중단한다
(PIPE-2SB-22와 같은 방식) — 그대로 두면 $30 상한(PIPE-2SB-60)이 확인 불가능한 채로
무의미해진다. 이 러너는 **작업 집합에 한해서만** Redis 없이 도는 것을 허용하고, **비용
카운터에는 하드 의존한다**(단, `BEDROCK_DRY_RUN`처럼 실과금이 0인 모드는 면제).

⚠️ 이 러너는 판정 로직을 갖지 않는다. LLM 판정은 전부 `bedrock.judge_service`에
위임하고, 여기서는 S3 리스팅/읽기/쓰기·검열 단위 분해·배치 조립·결과 라우팅·
Redis 작업 집합/비용 카운터 갱신만 한다.

실행: (프로젝트 루트에서)
    python -m pipeline.run_bedrock
"""

import json
import sys
from datetime import datetime
from typing import Any, Optional
from zoneinfo import ZoneInfo

from pydantic_settings import BaseSettings, SettingsConfigDict

from bedrock import (
    BedrockConfigError,
    BedrockFatalError,
    JudgeItem,
    bedrock_settings,
    judge_service,
    validate_startup,
)

from pipeline.core.config import pipeline_settings
from pipeline.run_validation import today_kst
from pipeline.s3_io import (
    build_s3_client,
    delete_object_if_exists,
    get_object_bytes,
    list_json_keys,
    manifest_key,
    object_exists,
    output_key,
    output_prefix,
    put_json_object,
    shadow_key,
)
from pipeline.text_normalize import is_blank_content, strip_crawler_artifacts

# 크롤러가 적재하는 커뮤니티 소스. run_validation과 동일(PIPE-S3IO-6).
SOURCES: list[str] = ["dcinside", "fmkorea"]


class BedrockRunnerSettings(BaseSettings):
    """`run_bedrock` 러너 전용 설정 — 오케스트레이션(배치 크기·Redis·비용 상한·날짜)이며
    모델 호출 설정(`bedrock.core.config.BedrockSettings`)과는 경계가 다르다(PIPE-2SB-15
    대칭: bedrock 모듈이 S3를 모르듯, 여기 설정도 모델 파라미터를 모른다).

    아래 다수는 문서상 "(가정 키명)"·"승인 후 확정 항목"으로 남겨진 값이다 — 계약이 아니라
    구현 시 정하는 값이므로 기본값을 두되 전부 env로 override 가능하게 했다.
    """

    # PIPE-2SB-37/38: 미주입 시 today_kst() 폴백(main()에서 처리).
    BATCH_DATE: Optional[str] = None

    # 작업 집합/비용 카운터(PIPE-2SB-26, 배치 전용 batch-redis, ns victoryfairy-batch).
    # 비워 두면 Redis 없이 동작한다(PIPE-2SB-73 — Redis는 정본이 아니라 최적화 수단).
    BATCH_REDIS_URL: Optional[str] = None
    PENDING_BEDROCK_KEY: str = "pending:bedrock"
    BEDROCK_SPEND_KEY: str = "bedrock_spend_usd"

    # 비용 상한(PIPE-2SB-60, 사용자 확정 $30/밤). 소프트 상한(PIPE-2SB-75)이다.
    BEDROCK_SPEND_LIMIT_USD: float = 30.0

    # 토큰 단가(PIPE-2SB-62 — 코드 상수 금지, env로만 읽는다). ⚠️ 미확인 가정치다
    # (Anthropic 1P Sonnet 4 단가를 파트너 단가로 가정 — AWS Pricing API는 SCP로 차단).
    # bedrock_spend_usd는 추정 소비액이지 청구액이 아니다 — 실청구액과 대조해 갱신할 것.
    BEDROCK_PRICE_INPUT_PER_1K_USD: float = 0.003
    BEDROCK_PRICE_OUTPUT_PER_1K_USD: float = 0.015
    BEDROCK_PRICE_CACHE_READ_PER_1K_USD: float = 0.0003
    BEDROCK_PRICE_CACHE_WRITE_PER_1K_USD: float = 0.00375

    # 배치 크기(PIPE-2SB-71, "게시글 N개분"). 기본값은 문서에 박지 않는다 — 판정 품질을
    # 보고 accuracy-tuner가 정한다. 여기서는 배선 검증 가능한 보수적 기본값만 둔다.
    BEDROCK_BATCH_POST_SIZE: int = 5

    # shadow 모드(BRK-LLM-48)는 "전건이 아니라 표본만" 돌린다. 0 이하면 표본 제한 없음.
    BEDROCK_SHADOW_SAMPLE_SIZE: int = 500

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


runner_settings = BedrockRunnerSettings()


def _die(message: str) -> None:
    """명확한 에러를 남기고 0이 아닌 종료 코드로 중단한다(PIPE-2SB-22)."""
    print(f"오류: {message}", file=sys.stderr)
    sys.exit(1)


class BedrockCostCounterUnavailable(Exception):
    """`bedrock_spend_usd` 카운터에 접근할 수 없다(조회/누적 실패 또는 미연결).

    ⚠️ `PIPE-2SB-73`의 "Redis 실패는 로그만" 원칙은 **작업 집합(SREM/SADD)에만** 적용된다.
    비용 카운터는 S3 같은 대체 정본이 없고(`PIPE-2SB-61` — "누적 주체가 러너 메모리면
    Job 이 여러 개라 총량이 집계되지 않는다, 공유 카운터여야 한다"), 이게 없으면
    `PIPE-2SB-74`의 배치 전 확인이 항상 통과해 `$30` 상한(`PIPE-2SB-60`)이 무의미해진다.
    그래서 이 예외는 호출부가 반드시 `_die()`로 이어받아야 한다(로그만 남기고 삼키지 않는다).
    """


class BatchRedis:
    """작업 집합(`pending:bedrock`)·비용 카운터(`bedrock_spend_usd`) 접근 래퍼.

    ⚠️ 두 대상은 실패 시 취급이 **다르다** — 이게 이 클래스의 핵심 계약이다.

    - **작업 집합(SREM/SADD)**: `PIPE-2SB-73` 그대로 — S3 마커가 정본이라 유실이
      아니다. 연결 실패·명령 실패를 전부 이 클래스 안에서 로그만 남기고 삼킨다.
    - **비용 카운터(get_spend/add_spend)**: 대체 정본이 없다. 미연결·조회·누적
      실패를 `BedrockCostCounterUnavailable`로 **올린다** — 호출부(`main()`)가
      `_die()`로 이어받아 명확한 에러 + 0이 아닌 종료 코드로 중단해야 한다
      (`PIPE-2SB-22`와 같은 방식). **예외: `BEDROCK_DRY_RUN`**(실제 모델 호출이
      없어 과금 자체가 0인 모드)에서는 `cost_tracking_required=False`로 생성해
      이 요구를 면제한다 — DRY_RUN에서까지 Redis를 강제하면 배선 검증 목적의
      "S3_BUCKET만 있으면 도는" 시나리오가 깨진다. **SHADOW 모드는 면제 대상이
      아니다** — `BRK-LLM-48`이 "실제로 모델을 호출해 판정"하고 비용 상한 적용을
      받는다고 명시했으므로 실과금이 있고, 카운터가 없으면 상한을 확인할 수 없다.
    """

    def __init__(self, url: Optional[str], cost_tracking_required: bool = True):
        self._client = None
        self._cost_tracking_required = cost_tracking_required
        exemption_note = "" if cost_tracking_required else "(BEDROCK_DRY_RUN이라 비용 카운터는 면제됨)"
        if not url:
            if cost_tracking_required:
                print(
                    "정보: BATCH_REDIS_URL 미설정 — 작업 집합 없이 동작합니다. "
                    "비용 카운터도 사용할 수 없어 배치 전 예산 확인 시 중단됩니다(PIPE-2SB-61/73)."
                )
            else:
                print(f"정보: BATCH_REDIS_URL 미설정 — 작업 집합 없이 동작합니다. {exemption_note}")
            return
        try:
            import redis  # 지연 import: 로컬 환경에 redis 패키지가 없을 수 있다(s3_io의 boto3와 같은 이유).

            self._client = redis.Redis.from_url(url, decode_responses=True)
            self._client.ping()
        except Exception as exc:  # noqa: BLE001
            if cost_tracking_required:
                print(
                    f"경고: Redis 연결 실패({url}): {exc} — 작업 집합 없이 동작합니다. "
                    "비용 카운터도 사용할 수 없어 배치 전 예산 확인 시 중단됩니다(PIPE-2SB-61/73).",
                    file=sys.stderr,
                )
            else:
                print(
                    f"경고: Redis 연결 실패({url}): {exc} — 작업 집합 없이 동작합니다. {exemption_note}",
                    file=sys.stderr,
                )
            self._client = None

    def get_spend(self) -> float:
        """누적 소비액(`bedrock_spend_usd`)을 읽는다(PIPE-2SB-74의 배치 전 확인용).

        `cost_tracking_required=True`(기본, DRY_RUN이 아닌 모든 실행)일 때 미연결/조회
        실패는 `BedrockCostCounterUnavailable`을 올린다 — 0.0으로 눙기면 상한이
        무의미해진다(클래스 docstring 참고). `cost_tracking_required=False`(DRY_RUN)일
        때만 관대하게 0.0을 돌려준다.
        """
        if self._client is None:
            if self._cost_tracking_required:
                raise BedrockCostCounterUnavailable(
                    "Redis에 연결되어 있지 않아 bedrock_spend_usd를 확인할 수 없습니다."
                )
            return 0.0
        try:
            value = self._client.get(runner_settings.BEDROCK_SPEND_KEY)
            return float(value) if value is not None else 0.0
        except Exception as exc:  # noqa: BLE001
            if self._cost_tracking_required:
                raise BedrockCostCounterUnavailable(
                    f"{runner_settings.BEDROCK_SPEND_KEY} 조회 실패: {exc}"
                ) from exc
            print(f"경고: {runner_settings.BEDROCK_SPEND_KEY} 조회 실패: {exc}", file=sys.stderr)
            return 0.0

    def add_spend(self, amount_usd: float) -> None:
        """이번 호출분 소비액을 누적한다(PIPE-2SB-61, `INCRBYFLOAT`).

        실패 시 취급은 `get_spend()`와 동일하다 — 공유 카운터에 반영되지 않으면
        다음 배치의 예산 확인이 실제보다 적게 쓴 것으로 착각해 상한이 뚫릴 수 있으므로
        `cost_tracking_required=True`일 때는 로그만 남기고 넘어가지 않는다.
        """
        if amount_usd <= 0:
            return
        if self._client is None:
            if self._cost_tracking_required:
                raise BedrockCostCounterUnavailable(
                    "Redis에 연결되어 있지 않아 bedrock_spend_usd를 누적할 수 없습니다."
                )
            return
        try:
            self._client.incrbyfloat(runner_settings.BEDROCK_SPEND_KEY, amount_usd)
        except Exception as exc:  # noqa: BLE001
            if self._cost_tracking_required:
                raise BedrockCostCounterUnavailable(
                    f"{runner_settings.BEDROCK_SPEND_KEY} 누적 실패: {exc}"
                ) from exc
            print(f"경고: {runner_settings.BEDROCK_SPEND_KEY} 누적 실패: {exc}", file=sys.stderr)

    def srem_pending(self, member: str) -> None:
        """완료된 게시글 키를 `pending:bedrock`에서 제거한다.

        PIPE-2SB-72: 이 호출은 반드시 **S3 마커 기록 뒤에** 이뤄져야 한다(호출부 책임).
        실패는 에러로 중단하지 않고 로그만 남긴다(PIPE-2SB-73 — 작업 집합은 S3 마커가
        정본이라 유실이 아니다) — 다음 실행이 마커를 보고 skip한 뒤 이 키를 다시
        지우므로 수렴한다. **비용 카운터와 달리 이 메서드는 항상 관대하다.**
        """
        if self._client is None:
            return
        try:
            self._client.srem(runner_settings.PENDING_BEDROCK_KEY, member)
        except Exception as exc:  # noqa: BLE001 — PIPE-2SB-73
            print(
                f"경고: {runner_settings.PENDING_BEDROCK_KEY} SREM 실패({member}): {exc}",
                file=sys.stderr,
            )


def _estimate_cost_usd(usage) -> float:
    """토큰 사용량에 단가를 곱해 소비액을 추정한다(PIPE-2SB-61/62).

    단가는 코드 상수가 아니라 `runner_settings`(env)에서 읽는다 — 미확인 가정치이며
    실제 청구액과 다를 수 있다(문서 "알려진 한계" 참고, 실청구액과 주기적으로 대조할 것).
    """
    return (
        usage.input_tokens / 1000.0 * runner_settings.BEDROCK_PRICE_INPUT_PER_1K_USD
        + usage.output_tokens / 1000.0 * runner_settings.BEDROCK_PRICE_OUTPUT_PER_1K_USD
        + usage.cache_read_input_tokens / 1000.0 * runner_settings.BEDROCK_PRICE_CACHE_READ_PER_1K_USD
        + usage.cache_write_input_tokens / 1000.0 * runner_settings.BEDROCK_PRICE_CACHE_WRITE_PER_1K_USD
    )


def _body_slot(post: dict) -> tuple:
    """게시글의 본문 판정 단위를 결정한다(PIPE-2SB-8/8b).

    반환: (slot_unit, slot_text) — `slot_unit`은 산출물 `reasons[].unit` 표기용으로
    "body" 또는 "title"(PIPE-2SB-13). Bedrock 서비스에는 이 값과 무관하게 항상
    `unit_kind="body"`로 보낸다(PIPE-2SB-8b — title이 본문 자리를 대신하므로).

    pattern success 객체는 body/title이 둘 다 비어 있으면 애초에 만들어지지
    않으므로(PIPE-S3IO-33) 정상적으로는 title이 비어 있지 않다. 다만 v1 규칙으로
    쌓인 과거 산출물이 섞여 있을 수 있어(문서 "알려진 한계") title도 비어 있으면
    빈 문자열을 그대로 흘려보낸다 — 크래시하지 않는 것이 계약이다.

    ⚠️ 빈 판정과 서명 제거는 `pipeline.text_normalize` 를 **패턴 러너와 공유**한다
    (PIPE-S3IO-39). 각자 구현하면 두 단계가 서로 다른 텍스트를 판정하게 되고,
    1차에서 통과시킨 것과 2차가 보는 것이 어긋난다.
    """
    body_text = post.get("body")
    if not is_blank_content(body_text):
        return "body", strip_crawler_artifacts(body_text)
    title_text = post.get("title")
    if not is_blank_content(title_text):
        return "title", strip_crawler_artifacts(title_text)
    return "title", title_text if isinstance(title_text, str) else ""


def _post_comments(post: dict) -> list:
    """pattern success 객체의 `topComments` 목록을 그대로 반환한다.

    pattern 단계가 이미 통과 댓글만 남겨뒀으므로(PIPE-S3IO-10) 여기선 다시 필터링하지
    않는다. 댓글이 없으면 빈 리스트.
    """
    comments = post.get("topComments")
    return comments if isinstance(comments, list) else []


def _comment_text(comment: Any) -> str:
    if isinstance(comment, dict):
        body = comment.get("body")
        return body if isinstance(body, str) else ""
    return ""


def _build_batch_items(batch: list) -> tuple:
    """배치(게시글 목록)를 이종 혼합 판정 항목 목록으로 펼친다(PIPE-2SB-14/71).

    반환: (items, offsets, slots)
        - items: JudgeItem 평탄화 목록(본문 1 + 댓글 N, 게시글 순서대로 이어붙임)
        - offsets: 게시글별 items 내 시작 인덱스
        - slots: 게시글별 (slot_unit, slot_text, comments) — 결과를 되자를 때 필요
    """
    items: list = []
    offsets: list = []
    slots: list = []
    for entry in batch:
        post = entry["post"]
        slot_unit, slot_text = _body_slot(post)
        comments = _post_comments(post)
        offsets.append(len(items))
        items.append(JudgeItem(text=slot_text, unit_kind="body"))
        for comment in comments:
            items.append(JudgeItem(text=_comment_text(comment), unit_kind="comment"))
        slots.append((slot_unit, slot_text, comments))
    return items, offsets, slots


def _route_post(post: dict, slot_unit: str, slot_text: str, comments: list, results: list) -> tuple:
    """단위 결과(본문 1개 + 댓글 N개, 요청 순서와 1:1 대응)를 받아 게시글의
    success 객체·failed 사유 목록을 결정한다(PIPE-2SB-8~13, 세 갈래는 PIPE-2SB-11c).

    반환: (success_객체_또는_None, failed_reasons: list[dict])
    """
    body_result = results[0]
    comment_results = results[1:]

    failed_reasons: list = []
    if not body_result.is_valid:
        failed_reasons.append(
            {
                "unit": slot_unit,
                "commentIndex": None,
                "author": None,
                "text": slot_text,
                "axis": body_result.axis,
                "message": body_result.message,
            }
        )

    passed_comments: list = []
    # (commentIndex, message) — 통과했지만 폴백으로 처리된 댓글(PIPE-2SB-59).
    passed_comment_fallbacks: list = []
    for idx, (comment, result) in enumerate(zip(comments, comment_results)):
        if result.is_valid:
            passed_comments.append(comment)
            if result.fallback:
                passed_comment_fallbacks.append((idx, result.message))
        else:
            failed_reasons.append(
                {
                    "unit": "comment",
                    "commentIndex": idx,
                    "author": comment.get("author") if isinstance(comment, dict) else None,
                    "text": _comment_text(comment),
                    "axis": result.axis,
                    "message": result.message,
                }
            )

    if not body_result.is_valid and body_result.axis == "abuse":
        # PIPE-2SB-11b/11c 갈래(2): 본문이 abuse로 폐기 -> 게시글 전체 fail.
        # 통과 댓글이 있어도 success를 만들지 않는다(오탐 1건이 전부를 날리는 것을 막는
        # 축 B와 달리, abuse는 근거가 명확해 패턴 단계와 대칭을 맞춘다).
        return None, failed_reasons

    if not body_result.is_valid:
        # PIPE-2SB-11c 갈래(3): 축 B(spam/offtopic) -> 본문 자리 필드만 비우고
        # 통과 댓글로 success를 만든다(PIPE-2SB-10b/10c).
        if not passed_comments:
            # PIPE-2SB-11: 통과 단위가 하나도 없으면 success 미생성.
            return None, failed_reasons
        success_obj = dict(post)
        success_obj[slot_unit] = ""  # 판정 단위 자리 필드만 비운다 — body 또는 title.
        success_obj["topComments"] = passed_comments
        fallback_notes = [
            {"unit": "comment", "commentIndex": idx, "message": message}
            for idx, message in passed_comment_fallbacks
        ]
        if fallback_notes:
            success_obj["bedrockFallback"] = fallback_notes  # PIPE-2SB-59
        return success_obj, failed_reasons

    # PIPE-2SB-11c 갈래(1): 본문 통과 -> 정상 success. body/title 필드는 건드리지 않는다
    # (원본 스키마 형태 보존 — PIPE-2SB-10).
    success_obj = dict(post)
    success_obj["topComments"] = passed_comments
    fallback_notes = []
    if body_result.fallback:
        fallback_notes.append({"unit": slot_unit, "commentIndex": None, "message": body_result.message})
    fallback_notes.extend(
        {"unit": "comment", "commentIndex": idx, "message": message}
        for idx, message in passed_comment_fallbacks
    )
    if fallback_notes:
        success_obj["bedrockFallback"] = fallback_notes  # PIPE-2SB-59
    return success_obj, failed_reasons


def _finalize_post(
    client,
    bucket: str,
    redis: BatchRedis,
    source: str,
    date: str,
    post_id: str,
    success_obj,
    failed_reasons: list,
) -> None:
    """게시글 하나의 Bedrock 산출물을 원자적으로 확정한다(PIPE-2SB-18).

    success/failed를 쓰거나(해당 없으면) 지우고, 마지막에 완결 마커를 쓴다. 마커
    기록 **뒤에** `pending:bedrock`에서 SREM한다(PIPE-2SB-72 — 순서가 계약이다).
    """
    success_key = output_key("success", source, date, post_id, method="bedrock")
    failed_key = output_key("failed", source, date, post_id, method="bedrock")
    marker_key = manifest_key(source, date, post_id, method="bedrock")

    try:
        if success_obj is not None:
            put_json_object(client, bucket, success_key, success_obj)
        else:
            delete_object_if_exists(client, bucket, success_key)

        if failed_reasons:
            put_json_object(
                client,
                bucket,
                failed_key,
                {
                    "postExternalId": post_id,
                    "source": source,
                    "date": date,
                    "stage": "bedrock",
                    "modelId": judge_service.model_id,
                    "promptVersion": judge_service.prompt_version,
                    "reasons": failed_reasons,
                },
            )
        else:
            delete_object_if_exists(client, bucket, failed_key)

        put_json_object(
            client,
            bucket,
            marker_key,
            {
                "postExternalId": post_id,
                "source": source,
                "date": date,
                "processedAt": datetime.now(ZoneInfo("Asia/Seoul")).isoformat(),
            },
        )
    except Exception as exc:  # noqa: BLE001 — S3 쓰기 실패는 명확한 에러로 중단(PIPE-2SB-22)
        _die(f"S3 쓰기 실패({source}/{date}/{post_id}): {exc}")

    # PIPE-2SB-72: 마커 기록 성공 뒤에만 SREM한다.
    redis.srem_pending(f"{source}/{post_id}")


def _finalize_shadow(
    client,
    bucket: str,
    source: str,
    date: str,
    post_id: str,
    slot_unit: str,
    slot_text: str,
    comments: list,
    results: list,
) -> None:
    """shadow 모드(BEDROCK_SHADOW=true) 전용 기록(PIPE-2SB-70/BRK-LLM-48).

    `_shadow` 경로에만 쓰고 success/failed/`_manifest`·작업 집합은 전혀 건드리지
    않는다 — 폐기율 실측 전용이라 실제 판정을 대신하지 않는다.
    """
    body_result = results[0]
    comment_results = results[1:]

    units = [
        {
            "unit": slot_unit,
            "commentIndex": None,
            "author": None,
            "text": slot_text,
            "is_valid": body_result.is_valid,
            "axis": body_result.axis,
            "message": body_result.message,
            "fallback": body_result.fallback,
        }
    ]
    for idx, (comment, result) in enumerate(zip(comments, comment_results)):
        units.append(
            {
                "unit": "comment",
                "commentIndex": idx,
                "author": comment.get("author") if isinstance(comment, dict) else None,
                "text": _comment_text(comment),
                "is_valid": result.is_valid,
                "axis": result.axis,
                "message": result.message,
                "fallback": result.fallback,
            }
        )

    key = shadow_key(source, date, post_id, method="bedrock")
    try:
        put_json_object(
            client,
            bucket,
            key,
            {
                "postExternalId": post_id,
                "source": source,
                "date": date,
                "modelId": judge_service.model_id,
                "promptVersion": judge_service.prompt_version,
                "units": units,
            },
        )
    except Exception as exc:  # noqa: BLE001 — PIPE-2SB-22
        _die(f"S3 쓰기 실패(shadow {source}/{date}/{post_id}): {exc}")


def _collect_pending(client, bucket: str, date: str) -> tuple:
    """전 소스의 pattern 성공분 중 Bedrock 마커가 없는 게시글을 모은다(PIPE-2SB-7/17).

    반환: (pending: list[{"source","post_id","post"}], skipped_done: int, skipped_bad: int)
    """
    pending: list = []
    skipped_done = 0
    skipped_bad = 0

    for source in SOURCES:
        prefix = output_prefix("success", source, date, method="pattern")

        try:
            keys = list_json_keys(client, bucket, prefix)
        except Exception as exc:  # noqa: BLE001 — 리스팅 실패도 S3 접근 실패로 취급(PIPE-2SB-22)
            _die(f"S3 리스팅 실패({source}, prefix={prefix}): {exc}")
            return pending, skipped_done, skipped_bad  # _die가 exit하지만 정적 분석용 안전장치

        if not keys:
            # PIPE-2SB-24: 입력이 없으면 크래시 없이 로그만 남기고 계속한다.
            print(f"{source}: {prefix} 에 객체 없음 — 건너뜀")
            continue

        print(f"{source}: {len(keys)}개 객체 리스팅됨 ({prefix})")

        for key in keys:
            filename_post_id = key.rsplit("/", 1)[-1].removesuffix(".json")
            marker_key = manifest_key(source, date, filename_post_id, method="bedrock")

            try:
                already_done = object_exists(client, bucket, marker_key)
            except Exception as exc:  # noqa: BLE001
                _die(f"S3 접근 실패(매니페스트 확인 {key}): {exc}")
                return pending, skipped_done, skipped_bad

            if already_done:
                # PIPE-2SB-17: 완결분은 재처리하지 않는다(LLM 호출 0회 = 비용 0).
                skipped_done += 1
                continue

            try:
                raw = get_object_bytes(client, bucket, key)
            except Exception as exc:  # noqa: BLE001 — PIPE-2SB-22
                _die(f"S3 접근 실패(객체 읽기 {key}): {exc}")
                return pending, skipped_done, skipped_bad

            try:
                post = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                # PIPE-2SB-23: 불량 객체는 건너뛰고 나머지 처리를 계속한다.
                print(f"경고: JSON 파싱 실패({key}): {exc} — 건너뜀")
                skipped_bad += 1
                continue

            if not isinstance(post, dict) or not post.get("postExternalId"):
                print(f"경고: 필수 필드(postExternalId) 누락({key}) — 건너뜀")
                skipped_bad += 1
                continue

            pending.append({"source": source, "post_id": str(post["postExternalId"]), "post": post})

    return pending, skipped_done, skipped_bad


def main(
    date: Optional[str] = None,
    cost_tracker: Optional[Any] = None,
    spend_limit_usd: Optional[float] = None,
) -> dict:
    """Bedrock 러너 진입점.

    인자 없이 호출하면(정규 경로 — k8s Job CMD `python -m pipeline.run_bedrock`) 이전과
    동일하게 동작한다: 날짜는 `BATCH_DATE`/`today_kst()`, 비용 추적·작업 집합은
    `BatchRedis`(공유 Redis `bedrock_spend_usd`/`pending:bedrock`), 상한은
    `BEDROCK_SPEND_LIMIT_USD`(정규 $30/밤).

    `date`·`cost_tracker`·`spend_limit_usd`는 **백필 전용 주입 지점**이다
    (`docs/requirements/pipeline/backfill.md` PIPE-BF-11/15) — `run_backfill.py`가
    정규 `bedrock_spend_usd`(Redis) 대신 백필 커서의 `spendUsd`(S3, PIPE-BF-9/10)에
    누적하는 객체와 `BACKFILL_BUDGET_USD`를 넘긴다. `cost_tracker`는 `BatchRedis`와
    같은 인터페이스(`get_spend()`/`add_spend(amount)`/`srem_pending(member)`)를
    duck-typing으로 구현해야 하며, `srem_pending`을 no-op으로 두면 PIPE-2SB-72의
    `SREM`이 백필 모드에서 수행되지 않는다(PIPE-BF-3b).

    반환값(신규): 처리 요약 dict — 호출부가 예산 소진 여부(`not_started`)·처리 건수를
    판정하는 데 쓴다. CLI 진입점(`if __name__ == "__main__": main()`)은 반환값을
    쓰지 않으므로 정규 경로의 동작은 바뀌지 않는다.
    """
    bucket = pipeline_settings.S3_BUCKET
    if not bucket:
        _die("환경변수 S3_BUCKET 이 설정되지 않았습니다.")

    try:
        validate_startup()  # BRK-LLM-6b/48b/5 — 호출 전에 기동 계약을 검사한다.
    except BedrockConfigError as exc:
        _die(f"Bedrock 설정 오류: {exc}")

    client = build_s3_client()
    date = date or runner_settings.BATCH_DATE or today_kst()  # PIPE-2SB-37/38
    if cost_tracker is not None:
        # 백필 주입 경로(PIPE-BF-15) — 정규 BatchRedis/공유 Redis는 전혀 만들지 않는다.
        redis = cost_tracker
    else:
        # 비용 카운터는 DRY_RUN(실호출 없음, 과금 0)에서만 면제한다 — SHADOW는 실제로
        # 모델을 호출해 과금이 발생하므로(BRK-LLM-48) 면제 대상이 아니다(BatchRedis
        # 클래스 docstring).
        redis = BatchRedis(
            runner_settings.BATCH_REDIS_URL,
            cost_tracking_required=not bedrock_settings.BEDROCK_DRY_RUN,
        )
    batch_size = max(1, runner_settings.BEDROCK_BATCH_POST_SIZE)
    spend_limit = spend_limit_usd if spend_limit_usd is not None else runner_settings.BEDROCK_SPEND_LIMIT_USD
    shadow_mode = judge_service.shadow_mode
    dry_run_mode = bedrock_settings.BEDROCK_DRY_RUN

    print(f"BATCH_DATE={date} / batch_size={batch_size} / shadow={shadow_mode} / dry_run={dry_run_mode}")

    pending, total_skipped_done, total_skipped_bad = _collect_pending(client, bucket, date)

    if not pending:
        # PIPE-2SB-24
        print("완료: 처리할 pattern 성공분이 없습니다.")
        print(
            f"완료: 처리 0 / 이미완결(skip) {total_skipped_done} / 불량객체(skip) {total_skipped_bad} / "
            f"예산으로 미시작 0"
        )
        return {
            "date": date,
            "processed": 0,
            "skipped_done": total_skipped_done,
            "skipped_bad": total_skipped_bad,
            "not_started": 0,
            "spend_this_run": 0.0,
            "call_count": 0,
        }

    if shadow_mode:
        # BRK-LLM-48: shadow는 전건이 아니라 표본만 돌린다.
        sample_size = runner_settings.BEDROCK_SHADOW_SAMPLE_SIZE
        if sample_size > 0 and len(pending) > sample_size:
            print(f"SHADOW: 표본 {sample_size}건만 처리합니다(전체 {len(pending)}건).")
            pending = pending[:sample_size]

    total_processed = 0
    total_not_started = 0
    total_call_count = 0
    total_body_units = 0
    total_comment_units = 0
    total_discarded_units = 0
    total_fallback_units = 0
    total_truncated = 0
    total_cache_read_tokens = 0
    total_spend_this_run = 0.0

    for batch_start in range(0, len(pending), batch_size):
        batch = pending[batch_start : batch_start + batch_size]

        # PIPE-2SB-74: 배치 호출 시작 "전"에 예산을 확인한다. 초과 시 이 배치와 이후
        # 남은 게시글 전부를 시작하지 않는다(PIPE-2SB-63 — exit 0, 무한 재시도 방지).
        # ⚠️ 카운터 자체에 접근할 수 없는 것(BedrockCostCounterUnavailable)은 "상한 도달"과
        # 다르다 — 상한을 확인할 수 없는 상태로 계속 도는 것은 $30 상한을 무의미하게 만들므로
        # PIPE-2SB-63의 exit 0(정상 종료)이 아니라 PIPE-2SB-22 방식의 명확한 에러로 중단한다.
        try:
            current_spend = redis.get_spend()
        except BedrockCostCounterUnavailable as exc:
            _die(f"비용 카운터에 접근할 수 없어 예산 상한을 확인할 수 없습니다: {exc}")
            return  # _die가 exit하지만 정적 분석/테스트용 안전장치
        if current_spend >= spend_limit:
            remaining = len(pending) - batch_start
            total_not_started += remaining
            print(
                f"경고: 누적 소비액 ${current_spend:.4f} 가 상한 ${spend_limit:.2f} 이상 — "
                f"게시글 {remaining}건을 시작하지 않습니다(PIPE-2SB-74/75)."
            )
            break

        items, offsets, slots = _build_batch_items(batch)
        # judge_batch()를 쓴다 — judge()와 같은 판정이지만 usage(토큰량)까지 함께 줘야
        # 비용을 누적할 수 있다(PIPE-2SB-61, bedrock/__init__.py 문서 참고).
        try:
            judgement = judge_service.judge_batch(items)
        except BedrockFatalError as exc:
            # BRK-LLM-17: 구조적 실패(자격증명 없음·AccessDenied 등)는 fail-open 대상이
            # 아니다 — 즉시 명확한 에러로 중단한다(PIPE-2SB-22 와 같은 태도).
            _die(f"Bedrock 구조적 실패: {exc}")
            return  # _die 가 exit하지만 정적 분석/테스트용 안전장치

        total_call_count += judgement.usage.call_count
        total_cache_read_tokens += judgement.usage.cache_read_input_tokens
        total_truncated += judgement.truncated_count
        total_discarded_units += sum(1 for r in judgement.results if not r.is_valid)
        total_fallback_units += sum(1 for r in judgement.results if r.fallback)

        cost = _estimate_cost_usd(judgement.usage)
        try:
            redis.add_spend(cost)
        except BedrockCostCounterUnavailable as exc:
            # ⚠️ 이 시점엔 이미 위 judge_batch() 호출로 실제 과금이 발생했다(usage가 이미
            # 채워짐). 그래도 공유 카운터에 반영하지 못하면 다음 배치 전 확인(PIPE-2SB-74)이
            # 실제보다 적게 쓴 것으로 착각해 상한이 뚫릴 수 있으므로, 이 배치의 S3 산출물
            # 확정(success/failed/마커)을 포기하더라도 중단하는 편을 택한다(PIPE-2SB-61 —
            # 공유 카운터가 없으면 비용 총량 자체가 집계되지 않는다는 것이 이 조항의 취지).
            # 이 배치의 게시글들은 마커가 없어 다음 실행이 재판정한다(재과금 위험 — 알려진 한계).
            _die(f"비용 카운터에 접근할 수 없어 이번 호출분 소비액을 누적하지 못했습니다: {exc}")
            return  # _die가 exit하지만 정적 분석/테스트용 안전장치
        total_spend_this_run += cost

        for index, entry in enumerate(batch):
            slot_unit, slot_text, comments = slots[index]
            start = offsets[index]
            end = start + 1 + len(comments)
            results = judgement.results[start:end]

            total_body_units += 1
            total_comment_units += len(comments)

            source = entry["source"]
            post_id = entry["post_id"]

            if dry_run_mode:
                # DRY_RUN 은 S3 에 아무것도 쓰지 않는다(PIPE-2SB-78).
                #
                # ⚠️ 예전에는 여기서 success + 완결 마커를 썼다. 판정을 한 번도 하지 않은
                # 게시글이 "2차 검열 완결"로 기록되고, 마커가 붙는 순간 멱등 skip 대상이
                # 되어 **진짜 실행도 섀도 측정도 그 게시글을 영영 건너뛴다.** 배선 검증용
                # 모드가 운영 상태를 영구히 바꾸는 셈이라, 실버킷에 한 번만 돌려도 그
                # 날짜가 통째로 미판정인 채 완결 처리됐다(실제로 발생시킨 사고다).
                pass
            elif shadow_mode:
                _finalize_shadow(
                    client, bucket, source, date, post_id, slot_unit, slot_text, comments, results
                )
            else:
                success_obj, failed_reasons = _route_post(
                    entry["post"], slot_unit, slot_text, comments, results
                )
                _finalize_post(client, bucket, redis, source, date, post_id, success_obj, failed_reasons)

            total_processed += 1

    print(
        f"완료: 처리 {total_processed} / 이미완결(skip) {total_skipped_done} / "
        f"불량객체(skip) {total_skipped_bad} / 예산으로 미시작 {total_not_started}"
    )
    print(
        f"LLM 호출 {total_call_count}회 / 본문단위 {total_body_units} / 댓글단위 {total_comment_units} / "
        f"폐기단위 {total_discarded_units} / 폴백통과 {total_fallback_units} / 절단 {total_truncated} / "
        f"이번 실행 추정소비액 ${total_spend_this_run:.4f} / 캐시적중토큰 {total_cache_read_tokens}"
    )
    if dry_run_mode:
        print("DRY_RUN 모드: S3 에 아무것도 기록하지 않았습니다(PIPE-2SB-78) — 배선 검증 전용.")
    elif shadow_mode:
        print("SHADOW 모드: success/failed/마커/작업 집합 미기록 — _shadow 전용 경로에만 기록됨.")

    return {
        "date": date,
        "processed": total_processed,
        "skipped_done": total_skipped_done,
        "skipped_bad": total_skipped_bad,
        "not_started": total_not_started,
        "spend_this_run": total_spend_this_run,
        "call_count": total_call_count,
    }


if __name__ == "__main__":
    main()
