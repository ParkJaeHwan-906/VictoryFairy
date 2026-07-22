"""pipeline.s3_io 테스트: 키 조립(순수, 항상 실행) + dev 버킷 실입출력 통합(선택, 조건부 skip).

pytest 없이 stdlib 로만 실행:  `python3 tests/test_s3_io.py`
(.venv 로 실행 권장 — boto3 가 시스템 python3 에는 없을 수 있다: `.venv/bin/python3 tests/test_s3_io.py`)

두 계층으로 나뉜다:
  1. 키 조립 테스트 (test_key_*) — S3 호출 없는 순수 함수 테스트. 자격증명 유무와
     무관하게 항상 실행되고 항상 통과해야 한다.
  2. 통합 테스트 (test_integration_*) — `victoryfairy-crawl-dev` 등 실 dev 버킷에
     실제로 put/get/list/exists/delete 를 태운다(PIPE-S3IO-30/31). 이 테스트는:
       - `S3_BUCKET` 환경변수가 없으면 SKIP.
       - 자격증명이 없거나 만료됐거나 네트워크가 없어 첫 S3 호출이 실패하면 SKIP
         (계약의 "알려진 한계": 임시 STS 만료 시 실패할 수 있음. moto/페이크는 이번
         결정에서 제외됐으므로 로컬 격리 대신 조건부 skip 으로 우선순위1 단위 테스트가
         자격증명 없이도 항상 돌게 한다).
     ⚠️ 절대 실크롤 입력(`community/...`)이나 실운영 출력(`validation/pattern/success|
     failed|_manifest/...`) 키를 건드리지 않는다. 이 테스트 전용 네임스페이스
     `validation/pattern/_it_test_/{run_id}/...` 에만 쓰고, 끝나면 스스로 지운다.

요구사항 ID 대응:
  PIPE-S3IO-2   test_key_input_prefix_matches_source_date_rule
  PIPE-S3IO-11  test_key_output_key_matches_status_source_date_postid_rule
  PIPE-S3IO-12  test_key_output_key_method_segment_is_variable
  PIPE-S3IO-27  test_list_json_keys_paginates_via_continuation_token (페이크 클라이언트, S3 불필요)
  PIPE-S3IO-30  test_integration_* 전체 (전용 테스트 네임스페이스만 사용)
  PIPE-S3IO-31  test_integration_round_trip_cleans_up_after_itself (종료 시 정리)
"""

import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.s3_io import (  # noqa: E402
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


class SkipTest(Exception):
    """통합 테스트를 건너뛸 때 쓰는 신호용 예외 (자격증명/버킷 부재/네트워크 실패)."""


# ---------------------------------------------------------------------------
# 1. 키 조립 (순수 함수, S3 불필요, 항상 실행)
# ---------------------------------------------------------------------------

def test_key_input_prefix_matches_source_date_rule():
    # PIPE-S3IO-2: community/{source}/{date}/
    assert input_prefix("dcinside", "2026-07-22") == "community/dcinside/2026-07-22/"
    assert input_prefix("fmkorea", "2026-01-05") == "community/fmkorea/2026-01-05/"


def test_key_output_key_matches_status_source_date_postid_rule():
    # PIPE-S3IO-11: validation/pattern/{success|failed}/{source}/{date}/{postExternalId}.json
    assert (
        output_key("success", "dcinside", "2026-07-22", "11229559")
        == "validation/pattern/success/dcinside/2026-07-22/11229559.json"
    )
    assert (
        output_key("failed", "fmkorea", "2026-07-22", "999")
        == "validation/pattern/failed/fmkorea/2026-07-22/999.json"
    )


def test_key_output_key_method_segment_is_variable():
    # PIPE-S3IO-12: 방식 세그먼트(pattern)가 향후 bedrock 등으로 확장 가능해야 한다.
    # s3_io.METHOD 상수를 직접 바꾸지 않고, OUTPUT_KEY_TEMPLATE 이 method 를 변수로
    # 받는 템플릿임을 확인한다(경로 상수가 하드코딩된 리터럴이 아님).
    from pipeline.s3_io import OUTPUT_KEY_TEMPLATE

    assert "{method}" in OUTPUT_KEY_TEMPLATE
    rendered = OUTPUT_KEY_TEMPLATE.format(
        method="bedrock", status="success", source="dcinside", date="2026-07-22", post_id="1"
    )
    assert rendered == "validation/bedrock/success/dcinside/2026-07-22/1.json", rendered


def test_key_manifest_key_uses_underscore_manifest_segment():
    key = manifest_key("dcinside", "2026-07-22", "11229559")
    assert key == "validation/pattern/_manifest/dcinside/2026-07-22/11229559.json"
    # success/failed 리스팅과 섞이지 않도록 별도 폴더인지 확인.
    assert "_manifest" in key
    assert "/success/" not in key and "/failed/" not in key


class _FakePaginatingClient:
    """list_objects_v2 만 흉내 낸 페이크 — PIPE-S3IO-27 페이지네이션을 실 S3 없이 검증."""

    def __init__(self, pages):
        self._pages = pages  # list[dict] — 각 dict 는 list_objects_v2 응답 형태.
        self.calls = []

    def list_objects_v2(self, **kwargs):
        self.calls.append(kwargs)
        idx = len(self.calls) - 1
        return self._pages[idx]


def test_list_json_keys_paginates_via_continuation_token():
    # PIPE-S3IO-27: 1페이지를 넘는 결과는 continuation token 으로 전건 리스팅해야 한다.
    pages = [
        {
            "Contents": [{"Key": "community/dcinside/2026-07-22/1.json"}],
            "IsTruncated": True,
            "NextContinuationToken": "tok-1",
        },
        {
            "Contents": [
                {"Key": "community/dcinside/2026-07-22/2.json"},
                {"Key": "community/dcinside/2026-07-22/not_json.txt"},  # .json 아닌 건 걸러짐.
            ],
            "IsTruncated": False,
        },
    ]
    client = _FakePaginatingClient(pages)

    keys = list_json_keys(client, "bucket", "community/dcinside/2026-07-22/")

    assert keys == [
        "community/dcinside/2026-07-22/1.json",
        "community/dcinside/2026-07-22/2.json",
    ], keys
    assert len(client.calls) == 2, "continuation token 으로 2페이지를 모두 호출해야 함"
    assert "ContinuationToken" not in client.calls[0]
    assert client.calls[1]["ContinuationToken"] == "tok-1"


# ---------------------------------------------------------------------------
# 2. 통합 (dev 버킷 실입출력, 선택 · 조건부 skip)
# ---------------------------------------------------------------------------

_TEST_NAMESPACE_PREFIX = "validation/pattern/_it_test_"


def _require_bucket() -> str:
    import os

    bucket = os.environ.get("S3_BUCKET")
    if not bucket:
        raise SkipTest("S3_BUCKET 환경변수 미설정 — 통합 테스트 skip")
    return bucket


def _connect_or_skip():
    """S3 클라이언트를 만들고, 실제로 한 번 호출해 자격증명/네트워크를 확인한다.
    실패하면(자격증명 없음/만료/네트워크 불가) SkipTest 로 변환한다."""
    bucket = _require_bucket()
    try:
        client = build_s3_client()
        # 가벼운 존재 확인 호출로 자격증명/네트워크를 검증한다. 실키가 아니라
        # 테스트 네임스페이스 아래 존재할 리 없는 키를 확인하므로 부작용이 없다.
        object_exists(client, bucket, f"{_TEST_NAMESPACE_PREFIX}/__connectivity_probe__.json")
    except SkipTest:
        raise
    except Exception as exc:  # noqa: BLE001 — 자격증명 만료/권한/네트워크 등 전부 skip 사유
        raise SkipTest(f"S3 연결 확인 실패({type(exc).__name__}: {exc}) — 통합 테스트 skip") from exc
    return client, bucket


def test_integration_put_get_round_trip():
    client, bucket = _connect_or_skip()
    run_id = uuid.uuid4().hex[:8]
    key = f"{_TEST_NAMESPACE_PREFIX}/{run_id}/round_trip.json"
    payload = {"hello": "world", "run_id": run_id}

    try:
        put_json_object(client, bucket, key, payload)
        raw = get_object_bytes(client, bucket, key)
        import json

        assert json.loads(raw) == payload
        assert object_exists(client, bucket, key) is True
    finally:
        delete_object_if_exists(client, bucket, key)


def test_integration_list_json_keys_finds_written_object():
    client, bucket = _connect_or_skip()
    run_id = uuid.uuid4().hex[:8]
    prefix = f"{_TEST_NAMESPACE_PREFIX}/{run_id}/"
    key = f"{prefix}listed.json"

    try:
        put_json_object(client, bucket, key, {"run_id": run_id})
        keys = list_json_keys(client, bucket, prefix)
        assert key in keys, keys
    finally:
        delete_object_if_exists(client, bucket, key)


def test_integration_object_exists_false_for_absent_key():
    client, bucket = _connect_or_skip()
    run_id = uuid.uuid4().hex[:8]
    key = f"{_TEST_NAMESPACE_PREFIX}/{run_id}/never_written.json"

    assert object_exists(client, bucket, key) is False


def test_integration_delete_object_if_exists_removes_object():
    client, bucket = _connect_or_skip()
    run_id = uuid.uuid4().hex[:8]
    key = f"{_TEST_NAMESPACE_PREFIX}/{run_id}/to_delete.json"

    put_json_object(client, bucket, key, {"run_id": run_id})
    assert object_exists(client, bucket, key) is True

    delete_object_if_exists(client, bucket, key)
    assert object_exists(client, bucket, key) is False

    # 이미 없는 키를 다시 지워도 에러 없이 성공해야 한다(PIPE-S3IO-25 전제).
    delete_object_if_exists(client, bucket, key)


def test_integration_round_trip_cleans_up_after_itself():
    # PIPE-S3IO-31: 테스트 종료 후 테스트 네임스페이스에 잔여 객체가 없어야 한다.
    client, bucket = _connect_or_skip()
    run_id = uuid.uuid4().hex[:8]
    prefix = f"{_TEST_NAMESPACE_PREFIX}/{run_id}/"
    keys_written = [f"{prefix}a.json", f"{prefix}b.json"]

    for k in keys_written:
        put_json_object(client, bucket, k, {"run_id": run_id})

    try:
        listed = list_json_keys(client, bucket, prefix)
        assert set(listed) == set(keys_written)
    finally:
        for k in keys_written:
            delete_object_if_exists(client, bucket, k)

    remaining = list_json_keys(client, bucket, prefix)
    assert remaining == [], f"정리 후 잔여 객체 존재: {remaining}"


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
              if name.startswith("test_") and callable(fn)]
    passed = 0
    skipped = 0
    failed = 0
    for fn in tests:
        try:
            fn()
            passed += 1
            print(f"PASS  {fn.__name__}")
        except SkipTest as exc:
            skipped += 1
            print(f"SKIP  {fn.__name__}: {exc}")
        except AssertionError as exc:
            failed += 1
            print(f"FAIL  {fn.__name__}: {exc}")
        except Exception as exc:  # noqa: BLE001 — 예상 못 한 예외도 실패로 집계
            failed += 1
            print(f"FAIL  {fn.__name__}: {type(exc).__name__}: {exc}")
    print(f"\n{passed}/{len(tests)} passed, {skipped} skipped, {failed} failed")
    sys.exit(1 if failed else 0)
