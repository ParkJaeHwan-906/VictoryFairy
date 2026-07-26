"""패턴 검열 Lambda 핸들러 — S3 이벤트 1건당 게시글 1건.

    S3 community/{source}/{date}/{postId}.json  ──[ObjectCreated]──▶ 이 핸들러
                                                                        │ 통과분만
                                                                        ▼
                                                                    SQS (Bedrock 큐)

⚠️ **이 파일은 배선만 한다.** 판정은 `run_validation.process_post()`, S3 기록은
`run_validation._finalize_post()` 를 그대로 호출한다 — 로컬 실행 경로
(`run_validation.main()`)와 **같은 함수를 쓰므로 판정 기준이 갈리지 않는다.**
핸들러에 판정 로직을 다시 쓰면 로컬에서 검증한 것과 운영에서 도는 것이 달라진다.

## 멱등

S3 이벤트는 **최소 한 번(at-least-once)** 배달이다. 같은 객체에 대해 두 번 이상
호출될 수 있고, Lambda 자체 재시도도 있다. 완결 마커
(`validation/pattern/_manifest/...`)가 있으면 즉시 건너뛴다 — 그러면 재판정도
재기록도 하지 않는다.

## 실패 취급

예외를 올리면 Lambda 가 재시도하고, 최종 실패는 CloudWatch 에 남는다. S3 이벤트
소스에는 DLQ 가 자동으로 붙지 않으므로 **여기서 조용히 삼키면 그 게시글은 영구히
누락된다** — 마커가 없으니 "미완결"로 남지만 아무도 다시 부르지 않는다.
그래서 삼키지 않는다.
"""

import json
import os
import urllib.parse

from pipeline.core.config import pipeline_settings
from pipeline.run_validation import _finalize_post, process_post
from pipeline.s3_io import (
    build_s3_client,
    get_object_bytes,
    manifest_key,
    object_exists,
)

# 크롤 입력 키 규약: community/{source}/{date}/{postId}.json
_EXPECTED_PREFIX = "community/"
_EXPECTED_PARTS = 4

_s3 = None
_sqs = None


def _s3_client():
    global _s3
    if _s3 is None:
        _s3 = build_s3_client()
    return _s3


def _sqs_client():
    """SQS 클라이언트는 지연 생성한다 — 큐 URL 이 없는 로컬 테스트에서도 import 가 된다."""
    global _sqs
    if _sqs is None:
        import boto3

        _sqs = boto3.client("sqs")
    return _sqs


def parse_key(key: str) -> tuple:
    """`community/{source}/{date}/{postId}.json` 을 (source, date, post_id) 로 분해한다.

    규약에 맞지 않으면 `ValueError`. S3 알림에 prefix 필터를 걸어 두지만, 필터가
    바뀌거나 수동 업로드가 섞일 수 있어 핸들러에서도 확인한다.
    """
    if not key.startswith(_EXPECTED_PREFIX) or not key.endswith(".json"):
        raise ValueError(f"크롤 입력 키 규약과 다릅니다: {key}")
    parts = key[: -len(".json")].split("/")
    if len(parts) != _EXPECTED_PARTS:
        raise ValueError(f"크롤 입력 키 규약과 다릅니다: {key}")
    _, source, date, post_id = parts
    if not source or not date or not post_id:
        raise ValueError(f"크롤 입력 키 규약과 다릅니다: {key}")
    return source, date, post_id


def _extract_keys(event: dict) -> list:
    """S3 알림 이벤트에서 객체 키 목록을 뽑는다.

    ⚠️ S3 이벤트의 키는 **URL 인코딩**돼 있다(공백이 `+`, 한글이 `%XX`). 디코드하지
    않으면 GetObject 가 NoSuchKey 로 실패한다.
    """
    keys = []
    for record in event.get("Records") or []:
        s3_info = (record.get("s3") or {})
        raw = (s3_info.get("object") or {}).get("key")
        if not raw:
            continue
        keys.append(urllib.parse.unquote_plus(raw))
    return keys


def _enqueue(source: str, date: str, post_id: str) -> None:
    """Bedrock 단계로 넘긴다. 큐가 배치를 모아 주는 역할을 한다(비용 때문에 필수)."""
    queue_url = os.environ.get("BEDROCK_QUEUE_URL")
    if not queue_url:
        # 큐가 없으면 2단계로 넘길 방법이 없다. 조용히 넘어가면 패턴 통과분이
        # 영구히 방치되므로 올린다.
        raise RuntimeError("환경변수 BEDROCK_QUEUE_URL 이 설정되지 않았습니다.")
    _sqs_client().send_message(
        QueueUrl=queue_url,
        MessageBody=json.dumps(
            {"source": source, "date": date, "postId": post_id}, ensure_ascii=False
        ),
    )


def handler(event, context=None) -> dict:
    bucket = pipeline_settings.S3_BUCKET
    if not bucket:
        raise RuntimeError("환경변수 S3_BUCKET 이 설정되지 않았습니다.")

    client = _s3_client()
    processed = skipped = enqueued = 0

    for key in _extract_keys(event):
        source, date, post_id = parse_key(key)

        # 멱등 — 완결 마커가 있으면 재판정하지 않는다(S3 이벤트는 at-least-once).
        if object_exists(client, bucket, manifest_key(source, date, post_id)):
            skipped += 1
            continue

        post = json.loads(get_object_bytes(client, bucket, key))
        success_obj, failed_reasons = process_post(post)
        _finalize_post(client, bucket, source, date, post_id, success_obj, failed_reasons)
        processed += 1

        # 통과분만 다음 단계로. 전건 폐기된 게시글은 Bedrock 이 볼 입력이 없다.
        #
        # ⚠️ 마커 기록(위 _finalize_post) **뒤에** 넣는다. 먼저 넣으면 그 사이 실패 시
        #    큐에는 있는데 마커가 없어, 재시도가 같은 게시글을 다시 넣어 중복 판정이 된다.
        #    이 순서면 큐 전송 실패 시 마커가 이미 있어 재시도가 skip 하는데 — 그러면
        #    2단계로 영영 안 넘어간다. 둘 중 후자를 택했다: 큐 전송 실패는 예외로 올라가
        #    CloudWatch 에 남고, 누락은 백필로 복구할 수 있다(중복 과금은 되돌릴 수 없다).
        if success_obj is not None:
            _enqueue(source, date, post_id)
            enqueued += 1

    return {"processed": processed, "skipped": skipped, "enqueued": enqueued}
