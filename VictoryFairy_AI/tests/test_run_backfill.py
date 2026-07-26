"""pipeline.run_backfill 단위 테스트 — 날짜 순회·커서·백필 전용 예산.

pytest 없이 stdlib 로만 실행:  `.venv/bin/python3 tests/test_run_backfill.py`
(`.venv/bin/python3` 로 실행할 것 — `pydantic`·`boto3` 의존 경로를 거친다.)

⚠️ 실제 AWS·Redis 를 호출하지 않는다:
  - S3: `pipeline.run_backfill.build_s3_client` 를 `_FakeS3Client`(인메모리)로
    몽키패치한다. 커서(`_backfill/{runId}/cursor.json`)·패턴 산출물·Bedrock 산출물
    모두 이 페이크 위에서 실제 `pipeline.s3_io` 함수 그대로 동작한다.
  - Bedrock: 대부분의 테스트는 `pipeline.run_bedrock.main` 자체를 스텁으로 교체해
    LLM 호출 경로를 완전히 우회한다. 예산 누적(PIPE-BF-15) 테스트만 실제
    `run_bedrock.main`을 그대로 태우되 `bedrock.judge_service.judge_batch`(판정
    로직)를 몽키패치해 토큰 사용량을 통제한다 — 이때도 실제 Bedrock 호출은 없다.
  - Redis: `run_bedrock.BatchRedis`·`run_validation.PatternBatchRedis` 를 "생성되면
    즉시 실패"하는 가짜 클래스로 바꿔치기해, 백필 경로가 이 두 클래스를 **아예
    구성하지 않는다는 것**(= SADD/SREM/정규 비용 카운터를 전혀 건드리지 않는다는
    것, PIPE-BF-3b/15)을 구조적으로 증명한다.

요구사항 ID 대응:
  PIPE-BF-7   test_iter_dates_ascending_inclusive_both_ends
  PIPE-BF-5   test_backfill_skips_missing_date_and_continues_to_next_date
  PIPE-BF-6   test_pattern_stage_skips_post_with_existing_marker
  PIPE-BF-12  test_cursor_advances_only_after_date_marker_is_written
  PIPE-BF-15  test_backfill_accumulates_spend_in_cursor_and_never_touches_regular_batch_redis
              (같은 테스트가 PIPE-BF-3b 의 `run_validation.PatternBatchRedis` 미구성도 함께 증명)
  PIPE-BF-3b  test_backfill_accumulates_spend_in_cursor_and_never_touches_regular_batch_redis
  PIPE-BF-18  test_budget_boundary_preempts_starting_even_a_single_date

⚠️ 커버하지 않은 것: PIPE-BF-20/21(정규 배치·동시 백필 Job 과의 배타 실행)은 이
러너 자신이 아무 가드도 두지 않는다고 문서·코드 docstring이 명시한다(k8s
CronJob concurrencyPolicy 소관) — 애초에 이 파일에서 검증할 대상이 없다.
PIPE-BF-24~27(정책 재적용 통합)은 `BACKFILL_MODE` 값이 커서에 기록만 되고
동작을 바꾸지 않는다는 것이 계약이라(코드 docstring) 별도 동작 테스트가 없다.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import io  # noqa: E402

from bedrock import bedrock_settings, judge_service  # noqa: E402
from bedrock.schemas.judgement import BatchJudgement, BatchUsage, JudgeResult  # noqa: E402

import pipeline.run_backfill as run_backfill  # noqa: E402
import pipeline.run_bedrock as run_bedrock  # noqa: E402
import pipeline.run_validation as run_validation  # noqa: E402
from pipeline.core.config import pipeline_settings  # noqa: E402
from pipeline.run_backfill import (  # noqa: E402
    Cursor,
    _iter_dates,
    _run_pattern_stage_for_date,
    backfill_settings,
    main,
)
from pipeline.s3_io import (  # noqa: E402
    cursor_key,
    get_object_bytes,
    input_prefix,
    manifest_key,
    object_exists,
    output_key,
    put_json_object,
)

SOURCE = "dcinside"


# ---------------------------------------------------------------------------
# 헬퍼 (다른 테스트 파일과 의도적으로 중복 — 파일 단독 실행 가능성을 위해)
# ---------------------------------------------------------------------------

class _override:
    def __init__(self, obj, **kwargs):
        self._obj = obj
        self._kwargs = kwargs
        self._orig = {}

    def __enter__(self):
        for key, value in self._kwargs.items():
            self._orig[key] = getattr(self._obj, key)
            setattr(self._obj, key, value)
        return self

    def __exit__(self, *exc):
        for key, value in self._orig.items():
            setattr(self._obj, key, value)


class _FakeS3Client:
    def __init__(self):
        self.objects = {}

    def list_objects_v2(self, Bucket, Prefix, ContinuationToken=None):
        keys = sorted(k for k in self.objects if k.startswith(Prefix))
        return {"Contents": [{"Key": k} for k in keys], "IsTruncated": False}

    def get_object(self, Bucket, Key):
        return {"Body": io.BytesIO(self.objects[Key])}

    def put_object(self, Bucket, Key, Body, ContentType=None):
        self.objects[Key] = Body if isinstance(Body, bytes) else Body.encode("utf-8")

    def delete_object(self, Bucket, Key):
        self.objects.pop(Key, None)

    def head_object(self, Bucket, Key):
        if Key not in self.objects:
            from botocore.exceptions import ClientError

            raise ClientError({"Error": {"Code": "404"}}, "HeadObject")
        return {}


class _bedrock_ready:
    def __init__(self, **overrides):
        defaults = dict(BEDROCK_MODEL_ID="anthropic.test-model", BEDROCK_DRY_RUN=False, BEDROCK_SHADOW=False)
        defaults.update(overrides)
        self._override = _override(bedrock_settings, **defaults)

    def __enter__(self):
        self._override.__enter__()
        return self

    def __exit__(self, *exc):
        self._override.__exit__(*exc)


def _post(post_id, body="정상 본문", title="제목"):
    return {"postExternalId": post_id, "source": SOURCE, "title": title, "body": body, "topComments": []}


def _seed_community(client, bucket, source, date, post):
    key = f"{input_prefix(source, date)}{post['postExternalId']}.json"
    put_json_object(client, bucket, key, post)


class _ForbiddenIfConstructed:
    """생성 자체가 계약 위반임을 나타내는 가짜 클래스(PIPE-BF-3b/15 증명용)."""

    def __init__(self, *args, **kwargs):
        raise AssertionError(f"{self.__class__.__name__} 가 백필 경로에서 생성됨(계약 위반)")


def _noop_bedrock_stage(date, cost_tracker, spend_limit_usd):
    return {
        "date": date,
        "processed": 0,
        "skipped_done": 0,
        "skipped_bad": 0,
        "not_started": 0,
        "spend_this_run": 0.0,
        "call_count": 0,
    }


# ---------------------------------------------------------------------------
# PIPE-BF-7 — 순수 로직, S3 불필요
# ---------------------------------------------------------------------------

def test_iter_dates_ascending_inclusive_both_ends():
    dates = list(_iter_dates("2026-07-09", "2026-07-11"))
    assert dates == ["2026-07-09", "2026-07-10", "2026-07-11"]


# ---------------------------------------------------------------------------
# PIPE-BF-5 — 결측일 skip 후 계속 (07-13 이 실제 결측)
# ---------------------------------------------------------------------------

def test_backfill_skips_missing_date_and_continues_to_next_date():
    client = _FakeS3Client()
    bucket = "test-bucket"
    run_id = "run-bf5"
    # 07-12, 07-14 에만 입력이 있고 07-13 은 완전히 비어 있다(실제 결측 재현).
    _seed_community(client, bucket, SOURCE, "2026-07-12", _post("p12"))
    _seed_community(client, bucket, SOURCE, "2026-07-14", _post("p14"))

    with _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_backfill, build_s3_client=lambda: client), \
            _override(run_bedrock, main=_noop_bedrock_stage), \
            _override(
                backfill_settings,
                BACKFILL_FROM="2026-07-12",
                BACKFILL_TO="2026-07-14",
                BACKFILL_RUN_ID=run_id,
                BACKFILL_BUDGET_USD=1000.0,
                BACKFILL_MODE="backfill",
            ):
        main()

    cursor_raw = json.loads(get_object_bytes(client, bucket, cursor_key(run_id)))
    assert cursor_raw["lastCompletedDate"] == "2026-07-14", (
        f"결측일(07-13)에서 멈추지 않고 07-14 까지 진행해야 하는데 커서={cursor_raw}"
    )
    assert object_exists(client, bucket, output_key("success", SOURCE, "2026-07-12", "p12"))
    assert object_exists(client, bucket, output_key("success", SOURCE, "2026-07-14", "p14"))


# ---------------------------------------------------------------------------
# PIPE-BF-6 — 완결 마커가 이미 있으면 skip
# ---------------------------------------------------------------------------

def test_pattern_stage_skips_post_with_existing_marker():
    client = _FakeS3Client()
    bucket = "test-bucket"
    date = "2026-07-22"
    post = _post("already-done")
    _seed_community(client, bucket, SOURCE, date, post)
    # 이미 정제된 것처럼 마커를 미리 심어 둔다(PIPE-BF-6 — 예: 07-22 의 557건).
    put_json_object(client, bucket, manifest_key(SOURCE, date, "already-done"), {"processedAt": "x"})

    summary = _run_pattern_stage_for_date(client, bucket, date)

    assert summary["skipped_done"] == 1
    assert summary["processed"] == 0
    assert not object_exists(client, bucket, output_key("success", SOURCE, date, "already-done")), (
        "마커가 있는 게시글은 재처리되지 않아야 하고, 새 success 도 생성되지 않아야 한다"
    )


# ---------------------------------------------------------------------------
# PIPE-BF-12 — "그 날짜의 마커 전건 기록 -> 커서 전진" 순서
# ---------------------------------------------------------------------------

def test_cursor_advances_only_after_date_marker_is_written():
    client = _FakeS3Client()
    bucket = "test-bucket"
    run_id = "run-bf12"
    date = "2026-07-09"
    post_id = "p1"
    _seed_community(client, bucket, SOURCE, date, _post(post_id))

    captured = {}
    original_advance = run_backfill.Cursor.advance_date

    def _spy_advance(self, advanced_date):
        marker = manifest_key(SOURCE, advanced_date, post_id)  # method="pattern" 기본값
        captured["marker_exists_at_advance"] = object_exists(self._client, self._bucket, marker)
        return original_advance(self, advanced_date)

    run_backfill.Cursor.advance_date = _spy_advance
    try:
        with _override(pipeline_settings, S3_BUCKET=bucket), \
                _override(run_backfill, build_s3_client=lambda: client), \
                _override(run_bedrock, main=_noop_bedrock_stage), \
                _override(
                    backfill_settings,
                    BACKFILL_FROM=date,
                    BACKFILL_TO=date,
                    BACKFILL_RUN_ID=run_id,
                    BACKFILL_BUDGET_USD=1000.0,
                    BACKFILL_MODE="backfill",
                ):
            main()
    finally:
        run_backfill.Cursor.advance_date = original_advance

    assert captured.get("marker_exists_at_advance") is True, (
        "PIPE-BF-12 위반: 커서가 전진하는 시점에 그 날짜의 패턴 마커가 아직 기록돼 있지 않음"
    )
    cursor_raw = json.loads(get_object_bytes(client, bucket, cursor_key(run_id)))
    assert cursor_raw["lastCompletedDate"] == date


# ---------------------------------------------------------------------------
# PIPE-BF-15/3b — 예산은 커서에 누적, 정규 bedrock_spend_usd(Redis)/작업 집합은
# 읽지도 쓰지도 않는다(BatchRedis/PatternBatchRedis 를 아예 구성하지 않는 것으로 증명).
# ---------------------------------------------------------------------------

def test_backfill_accumulates_spend_in_cursor_and_never_touches_regular_batch_redis():
    client = _FakeS3Client()
    bucket = "test-bucket"
    run_id = "run-bf15"
    date = "2026-07-09"
    post_id = "p1"
    _seed_community(client, bucket, SOURCE, date, _post(post_id))

    usage = BatchUsage(call_count=1, input_tokens=1000, output_tokens=500,
                        cache_read_input_tokens=0, cache_write_input_tokens=0)
    expected_cost = run_bedrock._estimate_cost_usd(usage)
    assert expected_cost > 0, "테스트 전제: 토큰 단가가 0이 아니어야 유의미한 검증이 된다"

    def _stub_judge_batch(items):
        return BatchJudgement(
            results=[JudgeResult(is_valid=True, message="ok") for _ in items],
            usage=usage,
        )

    with _bedrock_ready(), \
            _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_backfill, build_s3_client=lambda: client), \
            _override(run_bedrock, build_s3_client=lambda: client, BatchRedis=_ForbiddenIfConstructed), \
            _override(run_validation, PatternBatchRedis=_ForbiddenIfConstructed), \
            _override(judge_service, judge_batch=_stub_judge_batch), \
            _override(
                backfill_settings,
                BACKFILL_FROM=date,
                BACKFILL_TO=date,
                BACKFILL_RUN_ID=run_id,
                BACKFILL_BUDGET_USD=1000.0,
                BACKFILL_MODE="backfill",
            ):
        # PatternBatchRedis 생성 시 즉시 예외를 던지도록 바꿔치기했으므로, 이 호출이
        # 예외 없이 끝난다는 것 자체가 백필 경로에서 그 클래스가 전혀 구성되지
        # 않는다는 뜻이다(run_validation.main() 을 타지 않고 process_post/
        # _finalize_post 만 재사용하는 구조 — PIPE-BF-3b).
        main()

    cursor_raw = json.loads(get_object_bytes(client, bucket, cursor_key(run_id)))
    assert cursor_raw["spendUsd"] == expected_cost, (
        f"PIPE-BF-15: 백필 소비액은 커서(spendUsd)에 누적돼야 한다. 실제={cursor_raw['spendUsd']}"
    )


# ---------------------------------------------------------------------------
# PIPE-BF-18 — 예산 경계가 날짜 경계보다 우선(이미 상한이면 첫 날짜조차 시작 안 함)
# ---------------------------------------------------------------------------

def test_budget_boundary_preempts_starting_even_a_single_date():
    client = _FakeS3Client()
    bucket = "test-bucket"
    run_id = "run-bf18"
    budget = 80.0
    date1, date2 = "2026-07-09", "2026-07-10"
    _seed_community(client, bucket, SOURCE, date1, _post("d1"))
    _seed_community(client, bucket, SOURCE, date2, _post("d2"))

    # 이전 실행에서 이미 예산을 다 쓴 것처럼 커서를 미리 심어 둔다.
    put_json_object(
        client,
        bucket,
        cursor_key(run_id),
        {
            "runId": run_id,
            "mode": "backfill",
            "from": date1,
            "to": date2,
            "order": "asc",
            "lastCompletedDate": None,
            "spendUsd": budget,
            "updatedAt": "2026-07-08T00:00:00+09:00",
        },
    )

    bedrock_call_count = {"n": 0}

    def _counting_bedrock_stage(date, cost_tracker, spend_limit_usd):
        bedrock_call_count["n"] += 1
        return _noop_bedrock_stage(date, cost_tracker, spend_limit_usd)

    with _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_backfill, build_s3_client=lambda: client), \
            _override(run_bedrock, main=_counting_bedrock_stage), \
            _override(
                backfill_settings,
                BACKFILL_FROM=date1,
                BACKFILL_TO=date2,
                BACKFILL_RUN_ID=run_id,
                BACKFILL_BUDGET_USD=budget,
                BACKFILL_MODE="backfill",
            ):
        main()

    assert bedrock_call_count["n"] == 0, "예산이 이미 상한이면 첫 날짜조차 시작하지 않아야 한다(PIPE-BF-18)"
    assert not object_exists(client, bucket, output_key("success", SOURCE, date1, "d1")), (
        "예산 경계가 날짜 경계보다 우선하므로 첫 날짜의 패턴 단계조차 실행되면 안 된다"
    )
    assert not object_exists(client, bucket, output_key("success", SOURCE, date2, "d2"))
    cursor_raw = json.loads(get_object_bytes(client, bucket, cursor_key(run_id)))
    assert cursor_raw["lastCompletedDate"] is None, "아무 날짜도 완결되지 않았으므로 커서가 전진하면 안 된다"


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
             if name.startswith("test_") and callable(fn)]
    passed = 0
    failed_count = 0
    for fn in tests:
        try:
            fn()
            passed += 1
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {exc}")
        except Exception as exc:  # noqa: BLE001 — 예상 못 한 예외도 이 테스트만 FAIL 처리하고 계속 진행.
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {type(exc).__name__}: {exc}")
    print(f"\n{passed}/{len(tests)} passed")
    sys.exit(1 if failed_count else 0)
