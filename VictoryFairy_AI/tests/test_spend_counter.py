"""DynamoDB 누적 소비액 카운터 테스트.

pytest 없이 stdlib 로 실행:  `.venv/bin/python tests/test_spend_counter.py`

이 모듈의 존재 이유는 **실패를 삼키지 않는 것**이다. 0.0 으로 눙기면 배치 전 예산
확인이 항상 통과해 상한이 조용히 사라진다 — 구 Redis 구현에서 실제로 잡았던 결함이고,
저장소를 DynamoDB 로 바꾸면서 같은 실수를 반복하지 않는지 확인한다.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.spend_counter import (  # noqa: E402
    DEFAULT_TTL_DAYS,
    BedrockCostCounterUnavailable,
    DynamoDBSpendCounter,
)

DATE = "2026-07-22"
TABLE = "test-budget"

_results = []


def check(name, fn):
    try:
        fn()
        _results.append((name, None))
    except AssertionError as exc:
        _results.append((name, str(exc) or "assertion failed"))


class _FakeDynamo:
    """get_item/update_item 만 흉내 내는 페이크. 실제 AWS 호출은 하지 않는다."""

    def __init__(self, item=None, fail_on=None):
        self.item = item
        self.fail_on = fail_on or set()
        self.updates = []

    def get_item(self, **kwargs):
        if "get" in self.fail_on:
            raise RuntimeError("get_item 실패")
        self.last_get = kwargs
        return {"Item": self.item} if self.item else {}

    def update_item(self, **kwargs):
        if "update" in self.fail_on:
            raise RuntimeError("update_item 실패")
        self.updates.append(kwargs)


def _counter(client, required=True):
    return DynamoDBSpendCounter(TABLE, DATE, required=required, client=client)


# ---------------------------------------------------------------------------
# 정상 경로
# ---------------------------------------------------------------------------

def test_get_spend_reads_existing_value():
    c = _counter(_FakeDynamo(item={"spend_usd": {"N": "12.75"}}))
    assert c.get_spend() == 12.75


def test_get_spend_returns_zero_when_no_item_yet():
    """그날 첫 호출 — 아이템이 아직 없다. 이건 실패가 아니라 0원이다."""
    assert _counter(_FakeDynamo(item=None)).get_spend() == 0.0


def test_get_spend_uses_consistent_read():
    """직전 호출의 누적분을 못 읽으면 상한을 넘겨 놓고 뒤늦게 안다."""
    fake = _FakeDynamo(item={"spend_usd": {"N": "1"}})
    _counter(fake).get_spend()
    assert fake.last_get.get("ConsistentRead") is True, "ConsistentRead 가 켜져 있어야 한다"


def test_add_spend_uses_atomic_add():
    fake = _FakeDynamo()
    _counter(fake).add_spend(0.7407)
    assert len(fake.updates) == 1
    expr = fake.updates[0]["UpdateExpression"]
    assert "ADD spend_usd" in expr, f"원자적 ADD 여야 한다: {expr}"
    assert fake.updates[0]["ExpressionAttributeValues"][":amount"]["N"] == "0.7407"


def test_add_spend_skips_non_positive():
    """0원 호출로 쓰기 요청을 낭비하지 않는다."""
    fake = _FakeDynamo()
    c = _counter(fake)
    c.add_spend(0)
    c.add_spend(-1)
    assert fake.updates == []


# ---------------------------------------------------------------------------
# TTL — 짧으면 하루 중간에 상한이 리셋된다
# ---------------------------------------------------------------------------

def test_ttl_is_set_only_once_and_30_days_out():
    """expires_at 을 매번 갱신하면 처리가 이어지는 동안 만료가 계속 밀려 무기한 보관이 된다."""
    from datetime import datetime, timedelta
    from zoneinfo import ZoneInfo

    fake = _FakeDynamo()
    _counter(fake).add_spend(1.0)
    u = fake.updates[0]

    assert "if_not_exists(expires_at" in u["UpdateExpression"], "TTL 은 없을 때만 채워야 한다"

    expected = datetime.strptime(DATE, "%Y-%m-%d").replace(tzinfo=ZoneInfo("Asia/Seoul"))
    expected = int((expected + timedelta(days=DEFAULT_TTL_DAYS)).timestamp())
    assert int(u["ExpressionAttributeValues"][":expires"]["N"]) == expected

    assert DEFAULT_TTL_DAYS >= 7, (
        "TTL 이 처리 창보다 짧으면 하루 중간에 상한이 리셋된다 — 크롤이 10분 주기 상시라 "
        "처리가 하루 종일 이어진다"
    )


# ---------------------------------------------------------------------------
# 실패 취급 — 이 모듈의 핵심 계약
# ---------------------------------------------------------------------------

def test_get_failure_raises_instead_of_returning_zero():
    """0.0 을 돌려주면 예산 확인이 항상 통과해 상한이 조용히 사라진다."""
    c = _counter(_FakeDynamo(fail_on={"get"}))
    try:
        c.get_spend()
        raised = False
    except BedrockCostCounterUnavailable:
        raised = True
    assert raised, "조회 실패는 올려야 한다"


def test_add_failure_raises():
    """공유 카운터에 반영되지 않으면 다음 확인이 실제보다 적게 쓴 것으로 착각한다."""
    c = _counter(_FakeDynamo(fail_on={"update"}))
    try:
        c.add_spend(1.0)
        raised = False
    except BedrockCostCounterUnavailable:
        raised = True
    assert raised, "누적 실패는 올려야 한다"


def test_missing_table_name_raises_when_required():
    c = DynamoDBSpendCounter(None, DATE, required=True)
    try:
        c.get_spend()
        raised = False
    except BedrockCostCounterUnavailable:
        raised = True
    assert raised, "테이블 이름이 없으면 추적이 불가능하므로 올려야 한다"


def test_not_required_mode_is_lenient():
    """실과금이 0인 DRY_RUN 만 면제된다. SHADOW 는 실제로 호출하므로 면제가 아니다."""
    c = _counter(_FakeDynamo(fail_on={"get", "update"}), required=False)
    assert c.get_spend() == 0.0
    c.add_spend(1.0)  # 예외가 나지 않아야 한다


def test_srem_pending_is_noop():
    """작업 집합은 Lambda 전환으로 폐기됐다 — 인터페이스만 남긴다."""
    _counter(_FakeDynamo()).srem_pending("dcinside/2026-07-22/123")


for _name, _fn in sorted((k, v) for k, v in list(globals().items()) if k.startswith("test_")):
    check(_name, _fn)

_failed = [(n, e) for n, e in _results if e]
for _n, _e in _results:
    print(f"{'FAIL' if _e else 'PASS'}  {_n}" + (f": {_e}" if _e else ""))
print(f"\n{len(_results) - len(_failed)}/{len(_results)} passed")
sys.exit(1 if _failed else 0)
