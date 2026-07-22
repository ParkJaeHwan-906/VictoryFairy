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

실행: (프로젝트 루트에서)
    python -m pipeline.run_validation
"""

import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

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

# 크롤러가 적재하는 커뮤니티 소스. 한 번의 실행에서 둘 다 처리한다(PIPE-S3IO-6).
SOURCES: list[str] = ["dcinside", "fmkorea"]


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

    - body 1개 + 댓글 N개 = 검열 단위 (1+N)개, 서로 독립적으로 판정한다(PIPE-S3IO-7).
    - 통과한 단위만 남긴 정화 객체를 success 로 삼는다(PIPE-S3IO-10).
      본문이 폐기됐으면 body 는 빈 문자열로 두고 통과 댓글만 유지한다(PIPE-S3IO-17).
      통과 댓글이 0개면 topComments 는 빈 배열로 남는다(PIPE-S3IO-18).
    - 통과한 단위가 하나도 없으면 success 는 생성하지 않는다(PIPE-S3IO-19).
    - 전건 통과면 failed 사유는 빈 리스트가 되어(호출부에서) failed 를 만들지 않는다(PIPE-S3IO-20).
    """
    failed_reasons: list[dict] = []

    body_ok, body_message = _validate_unit(post.get("body"))
    if not body_ok:
        # 걸린 원본 텍스트(text)를 함께 남긴다 — postExternalId 만으론 무엇이 왜
        # 필터링됐는지 알 수 없다는 피드백 반영(PIPE-S3IO-13).
        failed_reasons.append(
            {
                "unit": "body",
                "commentIndex": None,
                "author": None,
                "text": post.get("body"),
                "message": body_message,
            }
        )

    passed_comments: list[dict] = []
    for idx, comment in enumerate(post.get("topComments") or []):
        # topComments 가 빈 배열/누락이면 이 루프는 아예 돌지 않는다(PIPE-S3IO-20c).
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

    has_passed_unit = body_ok or bool(passed_comments)
    if not has_passed_unit:
        return None, failed_reasons

    # 정화 객체: 원본 필드는 그대로 두고 body/topComments 만 통과분으로 교체한다.
    # 전건 통과라면 body_ok=True, passed_comments == 원본 댓글 전부라 내용상 원본과 동일하다(PIPE-S3IO-9).
    success_obj = dict(post)
    success_obj["body"] = post.get("body") if body_ok else ""
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
    date = today_kst()

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
            total_processed += 1

    print(
        f"완료: 처리 {total_processed} / 이미완결(skip) {total_skipped_done} / 불량객체(skip) {total_skipped_bad}"
    )


if __name__ == "__main__":
    main()
