"""Bedrock 2차 검열 Lambda 핸들러 — SQS 배치 1건당 모델 호출 1회.

    SQS ──[이벤트 소스 매핑 batch_size=N]──▶ 이 핸들러 ──▶ S3 validation/bedrock/...

⚠️ **이 파일은 배선만 한다.** 판정 단위 분해·배치 조립·결과 라우팅·S3 기록은
`run_bedrock` 의 함수를 그대로 호출한다 — 로컬 실행 경로(`run_bedrock.main()`)와
**같은 함수를 쓰므로 판정 기준이 갈리지 않는다.**

## 왜 SQS 인가 — 배치가 비용을 결정한다

시스템 프롬프트가 2,470토큰이라 **호출마다** 붙는다. 게시글 1건씩 부르면 하루 $44.8 로
상한 $30 을 넘기고, 5건 묶으면 $8.97 이다. SQS 이벤트 소스 매핑의 `batch_size` 가 곧
"한 번의 모델 호출에 묶는 게시글 수"다.

## 부분 실패 — ReportBatchItemFailures

배치 안에서 일부만 실패했을 때 성공분까지 재처리하면 **같은 게시글에 모델 호출이 두 번
나간다**(마커가 결과는 막지만 돈은 이미 나간 뒤다). 실패한 메시지 ID 만 돌려주면 SQS 가
그것만 재배달한다. 인프라 쪽에서 `function_response_types = ["ReportBatchItemFailures"]`
로 켜 뒀다.

## 예산 상한

호출 **전에** 누적 소비액을 확인한다. 상한 이상이면 그 배치를 처리하지 않고 **전건을
실패로 돌려준다** — 메시지를 큐에 남겨 다음 날(카운터가 리셋된 뒤) 처리되게 하기
위해서다. 삼키면 그 게시글들은 영구히 미판정이 된다.

⚠️ 카운터 자체에 접근하지 못하는 것은 "상한 도달"과 **다르다** — 그때는 예외를 올려
Lambda 를 실패시킨다. 0으로 눙기면 상한이 조용히 사라진다.
"""

import json
from typing import Optional

from bedrock import BedrockConfigError, BedrockFatalError, bedrock_settings, judge_service, validate_startup

from pipeline.core.config import pipeline_settings
from pipeline.run_bedrock import (
    _build_batch_items,
    _estimate_cost_usd,
    _finalize_post,
    _route_post,
    runner_settings,
)
from pipeline.s3_io import build_s3_client, get_object_bytes, manifest_key, object_exists, output_key
from pipeline.spend_counter import DynamoDBSpendCounter

_s3 = None


def _s3_client():
    global _s3
    if _s3 is None:
        _s3 = build_s3_client()
    return _s3


def _parse_message(record: dict) -> Optional[dict]:
    try:
        body = json.loads(record.get("body") or "{}")
        source, date, post_id = body["source"], body["date"], body["postId"]
    except Exception:  # noqa: BLE001 — 깨진 메시지 하나가 배치 전체를 막으면 안 된다.
        return None
    if not source or not date or not post_id:
        return None
    return {"source": source, "date": date, "post_id": post_id, "message_id": record.get("messageId")}


def _load_pattern_success(client, bucket: str, entry: dict) -> Optional[dict]:
    source, date, post_id = entry["source"], entry["date"], entry["post_id"]

    # 멱등 — SQS 도 최소 한 번 배달이다. 마커가 있으면 재판정하지 않는다.
    if object_exists(client, bucket, manifest_key(source, date, post_id, method="bedrock")):
        return None

    key = output_key("success", source, date, post_id, method="pattern")
    if not object_exists(client, bucket, key):
        # 패턴 산출물이 없다 — 정책 재적용으로 지워졌거나 순서가 어긋난 경우.
        # 재시도해도 생기지 않으므로 실패로 올리지 않고 넘긴다(무한 재배달 방지).
        return None
    return json.loads(get_object_bytes(client, bucket, key))


def handler(event, context=None) -> dict:
    bucket = pipeline_settings.S3_BUCKET
    if not bucket:
        raise RuntimeError("환경변수 S3_BUCKET 이 설정되지 않았습니다.")

    try:
        validate_startup()  # 모델 ID·리전·모드 조합 검사(BRK-LLM-6b/48b/5)
    except BedrockConfigError as exc:
        raise RuntimeError(f"Bedrock 설정 오류: {exc}") from exc

    client = _s3_client()
    records = event.get("Records") or []

    # 배치 안의 모든 메시지가 같은 날짜라는 보장이 없다. 예산 카운터는 날짜별이므로
    # 첫 메시지의 날짜를 기준으로 잡는다 — 자정 근처에서 하루치가 섞일 수 있으나
    # 상한 판정이 하루 어긋나는 것이지 유실은 아니다.
    parsed = [p for p in (_parse_message(r) for r in records) if p]
    if not parsed:
        return {"batchItemFailures": []}

    spend = DynamoDBSpendCounter(
        runner_settings.BUDGET_TABLE_NAME,
        batch_date=parsed[0]["date"],
        required=not bedrock_settings.BEDROCK_DRY_RUN,
    )

    # 예산 확인은 호출 **전에**. 카운터 접근 실패는 여기서 예외로 올라간다(삼키지 않는다).
    limit = runner_settings.BEDROCK_SPEND_LIMIT_USD
    if spend.get_spend() >= limit:
        # 상한 도달 — 전건을 실패로 돌려 큐에 남긴다. 카운터가 리셋된 뒤 처리된다.
        print(f"예산 상한 도달(${limit}) — 이 배치 {len(records)}건을 처리하지 않고 큐에 남깁니다.")
        return {"batchItemFailures": [{"itemIdentifier": r.get("messageId")} for r in records]}

    batch = []
    for entry in parsed:
        post = _load_pattern_success(client, bucket, entry)
        if post is None:
            continue
        batch.append({"source": entry["source"], "post_id": entry["post_id"],
                      "date": entry["date"], "post": post, "message_id": entry["message_id"]})

    if not batch:
        return {"batchItemFailures": []}

    items, offsets, slots = _build_batch_items(batch)

    try:
        judgement = judge_service.judge_batch(items)
    except BedrockFatalError:
        raise

    spend.add_spend(_estimate_cost_usd(judgement.usage))

    for index, entry in enumerate(batch):
        slot_unit, slot_text, comments = slots[index]
        start = offsets[index]
        results = judgement.results[start : start + 1 + len(comments)]
        success_obj, failed_reasons = _route_post(entry["post"], slot_unit, slot_text, comments, results)
        _finalize_post(client, bucket, entry["source"], entry["date"], entry["post_id"],
                       success_obj, failed_reasons)

    return {"batchItemFailures": []}
