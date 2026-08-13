"""Bedrock 2차 검열 판정 서비스 (BRK-LLM-1 · 10 · 12~19 · 48).

`validation_service` · `analysis_service` 와 같은 방식으로 **모듈 레벨 싱글턴**
(`judge_service`)을 노출한다. DI 컨테이너는 없다 — 러너가 import 해서 바로 쓴다.

이 모듈이 아는 것은 텍스트와 `unit_kind` 뿐이다. S3·버킷·키 규약(BRK-LLM-2)도,
validation 모듈의 사전·정규식(BRK-LLM-3)도 참조하지 않는다.

⚠️ **동기(`def`) 서비스다.** FastAPI 라우트가 아니라 배치 러너 전용이고, 내부에서
지수 백오프 `time.sleep` 으로 블로킹한다. async 로 감싸면 이벤트 루프가 멈춘다.
"""

import logging
import time
from typing import Any, Optional, Sequence, Union

from bedrock.core import client as bedrock_client
from bedrock.core.config import bedrock_settings, validate_startup
from bedrock.core.errors import BedrockFatalError, BedrockSchemaError, BedrockTransientError
from bedrock.core.parser import parse_judgements
from bedrock.core.prompt import PROMPT_VERSION, build_system_blocks, build_user_message
from bedrock.schemas.judgement import (
    BODY_ONLY_AXES,
    DRY_RUN_MESSAGE,
    FALLBACK_MESSAGE,
    PASS_MESSAGE,
    BatchJudgement,
    BatchUsage,
    JudgeItem,
    JudgeResult,
)

logger = logging.getLogger(__name__)

# judge() 가 받는 항목 타입 — 러너 편의를 위해 dict 도 받아 JudgeItem 으로 강제한다.
JudgeItemInput = Union[JudgeItem, dict]


class BedrockJudgeService:
    """실패 태도: 재시도 소진은 폴백 통과(fail-open, BRK-LLM-15), 구조적 실패는
    즉시 중단(BRK-LLM-17). **이 둘을 섞지 않는 것이 이 서비스의 핵심 계약이다.**
    """

    def __init__(self, settings=None):
        self._settings = settings or bedrock_settings
        self._client = None
        self._startup_validated = False

    # ------------------------------------------------------------------
    # 공개 API
    # ------------------------------------------------------------------

    def judge(self, items: Sequence[JudgeItemInput]) -> list:
        return self.judge_batch(items).results

    def judge_batch(self, items: Sequence[JudgeItemInput]) -> BatchJudgement:
        self._ensure_startup()

        normalized = [self._coerce_item(item) for item in items]
        if not normalized:
            logger.debug("판정 대상 항목이 없어 호출을 생략합니다.")
            return BatchJudgement(results=[], usage=BatchUsage())

        prepared, truncated_count = self._truncate_items(normalized)

        # BRK-LLM-16/16b — 실제 호출 없이 전건 통과. 호출 예정 건수를 로그로 남긴다.
        if self._settings.BEDROCK_DRY_RUN:
            logger.info(
                "DRY_RUN: 모델 호출 없이 전건 통과 처리합니다(호출 예정 1회 / 판정 단위 %d개).",
                len(prepared),
            )
            return BatchJudgement(
                results=[self._dry_run_result() for _ in prepared],
                usage=BatchUsage(),
                truncated_count=truncated_count,
            )

        return self._judge_with_model(prepared, truncated_count)

    @property
    def shadow_mode(self) -> bool:
        """⚠️ **판정 동작은 정식 실행과 완전히 동일하다** — shadow 는 실제로 모델을
        호출한다. 달라지는 것은 산출물 경로·마커 미기록·표본 추출인데 그건 전부
        러너 소관이다(PIPE-2SB-70). 이 모듈은 플래그만 알려 준다.
        """
        return self._settings.BEDROCK_SHADOW

    @property
    def model_id(self) -> Optional[str]:
        return (self._settings.BEDROCK_MODEL_ID or "").strip() or None

    @property
    def prompt_version(self) -> str:
        return PROMPT_VERSION

    # ------------------------------------------------------------------
    # 내부 구현
    # ------------------------------------------------------------------

    def _ensure_startup(self) -> None:
        """러너가 기동 시 `validate_startup()` 을 직접 부르는 것이 정석이지만,
        부르지 않고 바로 판정에 들어와도 우회되지 않도록 여기서도 막는다.
        """
        if self._startup_validated:
            return
        validate_startup(self._settings)
        self._startup_validated = True

    def _coerce_item(self, item: JudgeItemInput) -> JudgeItem:
        if isinstance(item, JudgeItem):
            return item
        return JudgeItem.model_validate(item)

    def _truncate_items(self, items: Sequence[JudgeItem]):
        limit = self._settings.BEDROCK_MAX_TEXT_CHARS
        prepared: list = []
        truncated_count = 0
        for index, item in enumerate(items, start=1):
            if limit > 0 and len(item.text) > limit:
                truncated_count += 1
                logger.warning(
                    "판정 대상 텍스트를 %d자로 절단했습니다(원본 %d자, 항목 %d, unit_kind=%s).",
                    limit,
                    len(item.text),
                    index,
                    item.unit_kind,
                )
                prepared.append(JudgeItem(text=item.text[:limit], unit_kind=item.unit_kind))
            else:
                prepared.append(item)
        return prepared, truncated_count

    def _get_client(self):
        if self._client is None:
            self._client = bedrock_client.build_bedrock_client(self._settings)
        return self._client

    def _judge_with_model(self, items: Sequence[JudgeItem], truncated_count: int) -> BatchJudgement:
        usage = BatchUsage()
        system_blocks = build_system_blocks(self._settings.BEDROCK_PROMPT_CACHE)
        user_text = build_user_message(items)
        model_id = self.model_id

        schema_retries_left = self._settings.BEDROCK_SCHEMA_RETRIES
        transient_retries_left = self._settings.BEDROCK_TRANSIENT_RETRIES
        transient_attempt = 0
        last_error: Optional[str] = None

        while True:
            try:
                response = bedrock_client.converse(
                    self._get_client(), model_id, system_blocks, user_text, self._settings
                )
            except BedrockFatalError:
                # BRK-LLM-17 — 전건 폴백 통과로 조용히 끝나는 것이 최악이다. 그대로 올린다.
                raise
            except BedrockTransientError as exc:
                last_error = str(exc)
                if transient_retries_left <= 0:
                    logger.error("Bedrock 일시적 실패 재시도를 모두 소진했습니다: %s", exc)
                    break
                delay = self._settings.BEDROCK_BACKOFF_BASE_SECONDS * (2 ** transient_attempt)
                logger.warning(
                    "Bedrock 호출 실패(%s). %.1f초 후 재시도합니다(남은 재시도 %d회).",
                    exc,
                    delay,
                    transient_retries_left,
                )
                transient_retries_left -= 1
                transient_attempt += 1
                if delay > 0:
                    time.sleep(delay)
                continue

            # 호출이 성공했으면 파싱 실패 여부와 무관하게 토큰은 소비됐다 — 반드시 누적한다.
            self._accumulate_usage(usage, bedrock_client.extract_usage(response))

            try:
                parsed = parse_judgements(bedrock_client.extract_text(response), len(items))
            except BedrockSchemaError as exc:
                last_error = str(exc)
                if schema_retries_left <= 0:
                    logger.error("Bedrock 응답 스키마 재시도를 모두 소진했습니다: %s", exc)
                    break
                logger.warning(
                    "Bedrock 응답 스키마 불일치(%s). 호출 전체를 재시도합니다(남은 재시도 %d회).",
                    exc,
                    schema_retries_left,
                )
                schema_retries_left -= 1
                continue

            results = [
                self._to_result(judgement, item, model_id)
                for judgement, item in zip(parsed, items)
            ]
            return BatchJudgement(results=results, usage=usage, truncated_count=truncated_count)

        # BRK-LLM-15/15b — 재시도 소진. 이 배치 전체를 폴백 통과로 처리한다.
        logger.error(
            "판정을 얻지 못해 %d개 단위를 폴백 통과 처리합니다(마지막 오류: %s).",
            len(items),
            last_error,
        )
        return BatchJudgement(
            results=[self._fallback_result() for _ in items],
            usage=usage,
            truncated_count=truncated_count,
        )

    def _accumulate_usage(self, usage: BatchUsage, delta: dict) -> None:
        usage.call_count += 1
        usage.input_tokens += delta.get("input_tokens", 0)
        usage.output_tokens += delta.get("output_tokens", 0)
        usage.cache_read_input_tokens += delta.get("cache_read_input_tokens", 0)
        usage.cache_write_input_tokens += delta.get("cache_write_input_tokens", 0)

    def _to_result(self, judgement: Any, item: JudgeItem, model_id: Optional[str]) -> JudgeResult:
        """여기서 거르는 것이 하나 있다 — **댓글에 붙은 `offtopic`**(BRK-LLM-19).
        프롬프트로 금지하고 있지만 모델이 어길 수 있고, 그대로 내보내면 계약 위반
        (댓글에 적용되지 않아야 할 축)이 산출물에 남는다. 스키마 불일치로 올려
        배치 전체를 재시도하는 대신 **그 항목만 통과로 되돌린다** — 정의상 적용 대상이
        아닌 판정이고, 이 단계의 기본 태도가 fail-open 이기 때문이다.

        ⚠️ BRK-LLM-1c(두 축 겹치면 `abuse` 우선)는 여기서 강제할 수 없다. 응답
        스키마가 축을 하나만 담아 겹침 자체가 관측되지 않으므로 **프롬프트 계약**으로만
        보장된다(`core/prompt.py`).
        """
        if judgement.is_valid:
            return JudgeResult(is_valid=True, axis=None, message=PASS_MESSAGE, fallback=False)

        if judgement.axis in BODY_ONLY_AXES and item.unit_kind != "body":
            logger.warning(
                "본문 전용 축(%s)이 unit_kind=%s 에 적용돼 통과로 되돌립니다(BRK-LLM-19).",
                judgement.axis,
                item.unit_kind,
            )
            return JudgeResult(is_valid=True, axis=None, message=PASS_MESSAGE, fallback=False)

        # BRK-LLM-18 — 폐기 판정에는 modelId·promptVersion 을 함께 실어 보낸다.
        return JudgeResult(
            is_valid=False,
            axis=judgement.axis,
            message=judgement.message,
            fallback=False,
            model_id=model_id,
            prompt_version=PROMPT_VERSION,
        )

    def _fallback_result(self) -> JudgeResult:
        return JudgeResult(is_valid=True, axis=None, message=FALLBACK_MESSAGE, fallback=True)

    def _dry_run_result(self) -> JudgeResult:
        """`fallback=true` 는 BRK-LLM-15 와 같지만 `message` 로 갈린다 — 산출물에서
        "Bedrock 장애로 못 거른 것"과 "애초에 호출을 안 한 것"이 구분돼야 한다.
        """
        return JudgeResult(is_valid=True, axis=None, message=DRY_RUN_MESSAGE, fallback=True)


# 러너에서 import 해 바로 사용할 싱글턴(validation_service·analysis_service 와 같은 방식)
judge_service = BedrockJudgeService()
