"""검열 러너 — S3 게시글별 `.json` in/out.

크롤러가 `community/{source}/{date}/` 아래에 게시글별로 적재한 `.json` 객체를
읽어 `body`·`topComments[].body` 를 각각 **독립 검열 단위**로 `validation_service`
에 그대로 넘기고(분할·변형 없음), 결과를 게시글 단위로 미러링한다.

산출물:
    - 성공(정화 객체): validation/pattern/success/{source}/{date}/{postExternalId}.json
    - 실패(폐기 사유):  validation/pattern/failed/{source}/{date}/{postExternalId}.json
    - 완결 마커(멱등 skip 판정용): validation/pattern/_manifest/{source}/{date}/{postExternalId}.json

완결 처리(success/failed 산출이 모두 확정된 게시글)는 매니페스트 마커로 판정한다.
게시글에 따라 success 만 나오거나 failed 만 나올 수 있어 "두 키 다 존재"로는 완결을
판정할 수 없기 때문이다(설계는 docs/requirements/pipeline/s3-io.md PIPE-S3IO-24/25 참고).
마커는 success/failed(해당하는 것만) 기록을 모두 마친 **마지막**에 쓴다. 마커가 없으면
그 게시글은 "미완결"로 보고 재실행 시 다시 처리한다 — 재처리 시에는 이번 판정 결과에
없는 이전 산출물(예: 과거엔 통과였는데 이번엔 폐기)을 지워 부분 상태가 고착되지 않게 한다.

⚠️ 이 러너는 판정 로직을 갖지 않는다. 검열은 전부 `validation_service.validation()`
에 위임하고, 여기서는 S3 리스팅/읽기/쓰기와 검열 단위 분해·결과 라우팅만 한다.

⚠️ **`main()` 은 이제 로컬 수동 실행 전용이다.** 운영 경로는 Lambda 핸들러
(`pipeline/lambda_pattern.py`)가 S3 이벤트마다 게시글 1건씩 처리한다. 두 경로 모두
`process_post()`/`_finalize_post()` 를 그대로 쓰므로 **판정 기준이 갈리지 않는다.**
`main()` 은 수동 재처리·디버깅·백필 보조용으로 남긴다.

작업 집합(Redis `pending:*`) 갱신 코드가 있었으나 **Lambda 전환으로 제거**됐다.
단계 전이를 S3 이벤트와 SQS 가 담당하므로 "다음 단계 대기 목록"을 우리가 셀 이유가
없어졌다 — 컨트롤러가 `SCARD` 를 폴링하던 구조가 통째로 사라졌기 때문이다.

실행: (프로젝트 루트에서)
    python -m pipeline.run_validation
"""

import json
import sys
from datetime import datetime
from typing import Optional
from zoneinfo import ZoneInfo

from pydantic_settings import BaseSettings, SettingsConfigDict

from validation.schemas.validation import ValidationRequest
from validation.services.validation import validation_service

from pipeline.core.config import pipeline_settings
from pipeline.s3_io import (
    build_s3_client,
    delete_object_if_exists,
    get_object_bytes,
    input_prefix,
    list_json_keys,
    manifest_key,
    object_exists,
    output_key,
    put_json_object,
)
from pipeline.text_normalize import is_blank_content, strip_crawler_artifacts

# 크롤러가 적재하는 커뮤니티 소스. 한 번의 실행에서 둘 다 처리한다(PIPE-S3IO-6).
SOURCES: list[str] = ["dcinside", "fmkorea"]


class PatternRunnerSettings(BaseSettings):
    """패턴 러너 설정.

    ⚠️ 작업 집합(Redis Set) 설정이 있었으나 **Lambda 전환으로 폐기**됐다. 단계 전이를
    S3 이벤트와 SQS 가 담당하므로 "다음 단계 대기 목록"을 우리가 셀 이유가 없어졌다.
    """

    # 처리 대상 날짜. 비우면 실행 당일 KST 로 폴백한다.
    #
    # ⚠️ 이 값이 없으면 처리가 자정을 넘길 때 대상 prefix 가 갈린다 — 전날 미처리분이 든
    # prefix 를 영영 보지 않는다(마커도 날짜 prefix 안에서만 의미가 있다).
    # Lambda 경로에서는 S3 키에서 파싱하므로 이 설정이 필요 없고, **로컬 수동 실행에서만**
    # 쓴다.
    BATCH_DATE: Optional[str] = None

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


pattern_runner_settings = PatternRunnerSettings()



def today_kst() -> str:
    """실행 당일 날짜를 KST(Asia/Seoul) 기준 YYYY-MM-DD 로 반환한다(PIPE-S3IO-4)."""
    return datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y-%m-%d")


def _validate_unit(text) -> tuple:
    """단일 검열 단위(본문 또는 댓글 본문)를 판정한다.

    빈 문자열/공백은 검열을 태우지 않고 곧바로 폐기로 간주한다(PIPE-S3IO-20b).
    그 외에는 텍스트를 분할·변형 없이 그대로 validation_service 에 전달한다(PIPE-S3IO-8).

    반환: (통과여부: bool, 사유: str)
    """
    if not isinstance(text, str) or not text.strip():
        return False, "빈 본문"
    result = validation_service.validation(ValidationRequest(line=text))
    return result.is_valid, result.message


def process_post(post: dict) -> tuple:
    """게시글 하나를 검열해 (success_객체_또는_None, failed_사유_목록) 을 반환한다.

    v2(docs/requirements/pipeline/s3-io.md) 규칙:
    - 본문 자리 판정 단위는 `body`. `body`가 빈 문자열/공백이면 `title`을 그 자리의
      판정 단위로 대신 쓴다(PIPE-S3IO-32). `body`가 있으면 `title`은 판정하지 않는다
      (PIPE-S3IO-35). 이 둘을 합쳐 "본문 판정 단위"라 부른다.
    - `body`·`title`이 둘 다 비어 있으면 곧바로 fail 확정(PIPE-S3IO-33), 사유는
      "빈 본문·빈 제목".
    - 본문 판정 단위가 폐기되면(걸렸거나, 둘 다 비었거나) 통과 댓글이 있어도 게시글
      전체를 fail 로 확정한다 — success 는 만들지 않는다(PIPE-S3IO-10/19).
      **댓글도 마저 판정해 그 사유를 failed 에 함께 담는다**(PIPE-S3IO-19b는 "댓글을
      더 판정하지 않아도 된다"만 허용하고 강제하진 않는다 — 판정하는 쪽을 택했다).
      이유: (1) 이 단계는 정규식 매칭이라 판정 비용이 사실상 0이라 "비용 절감" 근거가
      성립하지 않는다, (2) Bedrock 단계는 배칭이라 본문·댓글이 한 호출에 함께 가므로
      (PIPE-2SB-14) 패턴만 생략하면 두 단계 동작이 어긋난다, (3) 안 남긴 정보는
      재처리해야 복구되지만 남긴 정보는 그냥 무시하면 되므로 되돌리기 쉬운 쪽을 택한다.
    - 댓글은 본문 판정 단위 통과 여부와 무관하게 항상 각각 독립 판정한다(PIPE-S3IO-7).
      통과한 댓글만 남긴 정화 객체를 success 로 삼는다(PIPE-S3IO-10) — 단, 본문 판정
      단위가 폐기됐으면 이 정화 객체 자체를 success 로 내보내지 않는다(위 규칙).
      통과 댓글이 0개면 topComments 는 빈 배열로 남는다(PIPE-S3IO-18).
    - 전건 통과면 failed 사유는 빈 리스트가 되어(호출부에서) failed 를 만들지 않는다
      (PIPE-S3IO-20).
    """
    body_text = post.get("body")
    title_text = post.get("title")
    # PIPE-S3IO-39: 크롤러 자동 서명만 남은 본문은 **내용이 없는 것으로 본다**.
    # str.strip() 을 그대로 쓰면 `- dc official App` 뿐인 본문이 "내용 있음"으로
    # 판정돼 아래 title 대체가 발동하지 않는다(실측 15건이 그랬다).
    body_is_blank = is_blank_content(body_text)

    if body_is_blank:
        # PIPE-S3IO-32: 빈 본문이면 title 을 본문 자리 판정 단위로 삼는다.
        body_slot_unit = "title"
        body_slot_text = title_text
        title_is_blank = is_blank_content(title_text)
        if title_is_blank:
            # PIPE-S3IO-33: 본문·제목 모두 비어 있으면 판정할 텍스트가 없으므로 fail 확정.
            # (댓글은 있을 수 있으므로 여기서 곧장 리턴하지 않고 아래에서 마저 판정한다.)
            body_slot_ok, body_slot_message = False, "빈 본문·빈 제목"
        else:
            body_slot_ok, body_slot_message = _validate_unit(strip_crawler_artifacts(title_text))
    else:
        # PIPE-S3IO-35: body 가 있으면 title 은 판정하지 않는다.
        body_slot_unit = "body"
        body_slot_text = body_text
        # 서명을 뗀 텍스트로 판정한다. 저장은 원문 그대로다(text_normalize 모듈 주석).
        body_slot_ok, body_slot_message = _validate_unit(strip_crawler_artifacts(body_text))

    failed_reasons: list[dict] = []
    if not body_slot_ok:
        # 걸린 원본 텍스트(text)를 함께 남긴다 — postExternalId 만으론 무엇이 왜
        # 필터링됐는지 알 수 없다는 피드백 반영(PIPE-S3IO-13). unit 은 판정에 쓰인
        # 단위(body 또는 title)를 그대로 기록한다(PIPE-S3IO-34).
        failed_reasons.append(
            {
                "unit": body_slot_unit,
                "commentIndex": None,
                "author": None,
                "text": body_slot_text,
                "message": body_slot_message,
            }
        )

    passed_comments: list[dict] = []
    for idx, comment in enumerate(post.get("topComments") or []):
        # topComments 가 빈 배열/누락이면 이 루프는 아예 돌지 않는다(PIPE-S3IO-20c).
        # 본문 판정 단위가 이미 폐기됐어도 댓글은 마저 판정한다(PIPE-S3IO-19b, 위 사유).
        comment_body = comment.get("body") if isinstance(comment, dict) else None
        comment_ok, comment_message = _validate_unit(comment_body)
        if comment_ok:
            passed_comments.append(comment)
        else:
            failed_reasons.append(
                {
                    "unit": "comment",
                    "commentIndex": idx,
                    "author": comment.get("author") if isinstance(comment, dict) else None,
                    "text": comment_body,
                    "message": comment_message,
                }
            )

    if not body_slot_ok:
        # PIPE-S3IO-19: 본문 판정 단위 폐기 -> 게시글 전체 fail, success 미생성.
        # (댓글 사유는 위에서 이미 failed_reasons 에 함께 담겼다.)
        return None, failed_reasons

    # 정화 객체: 원본 필드는 그대로 두고 body/topComments 만 통과분으로 교체한다.
    # 여기 도달했다는 것 자체가 본문 판정 단위 통과를 뜻하므로 body 는 원본 그대로 둔다
    # (title 이 대신 판정됐더라도 success 의 body 필드를 title 로 바꿔치기하지 않는다 —
    # title 은 판정에만 쓰였을 뿐 정화 대상 필드가 아니다). 전건 통과라면 passed_comments
    # == 원본 댓글 전부라 내용상 원본과 동일하다(PIPE-S3IO-9).
    success_obj = dict(post)
    success_obj["body"] = post.get("body")
    success_obj["topComments"] = passed_comments
    return success_obj, failed_reasons


def _die(message: str) -> None:
    """명확한 에러를 남기고 0이 아닌 종료 코드로 중단한다(PIPE-S3IO-26)."""
    print(f"오류: {message}", file=sys.stderr)
    sys.exit(1)


def _finalize_post(client, bucket: str, source: str, date: str, post_id: str, success_obj, failed_reasons) -> None:
    """게시글 하나의 산출물을 확정한다: success/failed 를 쓰거나(해당 없으면) 지우고,
    마지막에 완결 마커를 쓴다(PIPE-S3IO-25).

    "해당 없으면 지운다"는 처리는 이전 실행이 중간에 죽어 남긴 부분 산출물이 이번 판정
    결과와 어긋난 채로 고착되지 않게 하기 위함이다(예: 이전엔 success 만 쓰고 죽었는데
    이번엔 전건 폐기로 판정 → 묵은 success 를 지운다).
    """
    success_key = output_key("success", source, date, post_id)
    failed_key = output_key("failed", source, date, post_id)
    marker_key = manifest_key(source, date, post_id)

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
                {"postExternalId": post_id, "source": source, "date": date, "reasons": failed_reasons},
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
    except Exception as exc:  # noqa: BLE001 — S3 쓰기 실패는 명확한 에러로 중단
        _die(f"S3 쓰기 실패({source}/{date}/{post_id}): {exc}")


def main() -> None:
    bucket = pipeline_settings.S3_BUCKET
    if not bucket:
        _die("환경변수 S3_BUCKET 이 설정되지 않았습니다.")

    client = build_s3_client()
    # PIPE-2SB-37/38 — 주입된 배치 시작일 우선, 없으면 실행 당일 KST 폴백.
    date = (pattern_runner_settings.BATCH_DATE or "").strip() or today_kst()

    total_processed = 0
    total_skipped_done = 0
    total_skipped_bad = 0

    for source in SOURCES:
        prefix = input_prefix(source, date)

        try:
            keys = list_json_keys(client, bucket, prefix)
        except Exception as exc:  # noqa: BLE001 — 리스팅 실패도 S3 접근 실패로 취급
            _die(f"S3 리스팅 실패({source}, prefix={prefix}): {exc}")
            return  # _die 가 sys.exit 하지만 정적 분석/테스트용 안전장치

        if not keys:
            # 해당 소스의 당일 입력이 없으면 건너뛰고 계속한다(PIPE-S3IO-21/22).
            print(f"{source}: {prefix} 에 객체 없음 — 건너뜀")
            continue

        print(f"{source}: {len(keys)}개 객체 리스팅됨 ({prefix})")

        for key in keys:
            # 파일명이 곧 postExternalId 라는 실측 규칙(설계 §32)을 이용해, 매니페스트
            # 존재 확인을 위한 GetObject 호출을 완결된 게시글에 대해서는 건너뛴다.
            filename_post_id = key.rsplit("/", 1)[-1].removesuffix(".json")
            marker_key = manifest_key(source, date, filename_post_id)

            try:
                already_done = object_exists(client, bucket, marker_key)
            except Exception as exc:  # noqa: BLE001
                _die(f"S3 접근 실패(매니페스트 확인 {key}): {exc}")
                return

            if already_done:
                total_skipped_done += 1
                continue

            try:
                raw = get_object_bytes(client, bucket, key)
            except Exception as exc:  # noqa: BLE001 — 객체 읽기(S3 접근) 실패는 크래시
                _die(f"S3 접근 실패(객체 읽기 {key}): {exc}")
                return

            try:
                post = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                # 불량 객체는 건너뛰고 나머지 처리를 계속한다(PIPE-S3IO-23).
                print(f"경고: JSON 파싱 실패({key}): {exc} — 건너뜀")
                total_skipped_bad += 1
                continue

            if not isinstance(post, dict) or not post.get("postExternalId"):
                print(f"경고: 필수 필드(postExternalId) 누락({key}) — 건너뜀")
                total_skipped_bad += 1
                continue

            post_id = str(post["postExternalId"])
            success_obj, failed_reasons = process_post(post)
            _finalize_post(client, bucket, source, date, post_id, success_obj, failed_reasons)
            # ⚠️ 로컬 실행 경로는 여기서 끝난다 — 다음 단계로 넘기지 않는다.
            # 운영 경로(Lambda 핸들러)는 success 가 생성됐을 때 SQS 에 메시지를 넣어
            # Bedrock 단계를 잇는다. 로컬에서 Bedrock 까지 돌리려면 run_bedrock.main()
            # 을 이어서 실행한다(그쪽은 pattern/success prefix 를 직접 리스팅한다).
            total_processed += 1

    print(
        f"완료: 처리 {total_processed} / 이미완결(skip) {total_skipped_done} / 불량객체(skip) {total_skipped_bad}"
    )


if __name__ == "__main__":
    main()
