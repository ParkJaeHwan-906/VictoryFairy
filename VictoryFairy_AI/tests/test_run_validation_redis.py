"""pipeline.run_validation.PatternBatchRedis 단위 테스트 — 작업 집합(Set) 갱신 계약.

pytest 없이 stdlib 로만 실행:  `.venv/bin/python3 tests/test_run_validation_redis.py`

⚠️ 실제 Redis 를 호출하지 않는다. `PatternBatchRedis(url=None)`으로 생성해 생성자의
실제 redis 접속(지연 import)을 건너뛰고, `_client` 속성에 호출을 기록하는 페이크
객체를 직접 주입한다(`tests/test_run_bedrock.py`의 `BatchRedis` 테스트와 같은 방식).

이 계약은 5.5단계에서 추가된 것으로, 기존 `tests/test_run_validation.py`·
`tests/test_run_validation_orchestration.py` 는 다루지 않는다(그 두 파일은
`process_post`/`_finalize_post`/`today_kst` 등만 검증) — 그래서 별도 파일로 분리했다.

요구사항 ID 대응 (docs/requirements/pipeline/two-stage-batch.md):
  PIPE-2SB-47/72b  test_mark_pattern_complete_success_case_sadd_before_srem,
                   test_mark_pattern_complete_failure_case_srem_without_sadd
  PIPE-2SB-73      test_mark_pattern_complete_swallows_redis_command_errors
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.run_validation import PatternBatchRedis, pattern_runner_settings  # noqa: E402


class _RecordingRedisClient:
    """sadd/srem 호출 순서와 인자를 기록하는 페이크(실제 Redis 불필요)."""

    def __init__(self):
        self.calls = []

    def sadd(self, key, member):
        self.calls.append(("sadd", key, member))

    def srem(self, key, member):
        self.calls.append(("srem", key, member))


class _RaisingRedisClient:
    def sadd(self, key, member):
        raise RuntimeError("sadd 실패")

    def srem(self, key, member):
        raise RuntimeError("srem 실패")


def _redis_with_fake_client(fake_client):
    redis = PatternBatchRedis(url=None)  # url=None -> 생성자가 실제 redis 접속을 시도하지 않는다.
    redis._client = fake_client  # 접속 성공 상태를 흉내 내기 위해 직접 주입.
    return redis


# ---------------------------------------------------------------------------
# PIPE-2SB-47/72b — 순서는 "마커 기록(호출부 책임) -> SADD(성공 시) -> SREM"
# ---------------------------------------------------------------------------

def test_mark_pattern_complete_success_case_sadd_before_srem():
    client = _RecordingRedisClient()
    redis = _redis_with_fake_client(client)

    redis.mark_pattern_complete("dcinside/1", advanced_to_bedrock=True)

    assert client.calls == [
        ("sadd", pattern_runner_settings.PENDING_BEDROCK_KEY, "dcinside/1"),
        ("srem", pattern_runner_settings.PENDING_PATTERN_KEY, "dcinside/1"),
    ], f"success 케이스는 SADD 가 SREM 보다 먼저여야 한다(PIPE-2SB-72b): {client.calls}"


def test_mark_pattern_complete_failure_case_srem_without_sadd():
    # PIPE-2SB-47/19b: 전건 폐기(성공 단위 0) 게시글도 pending:pattern 에서는 빠져야
    # 하지만(대기 상태를 벗어났으므로), pending:bedrock 에는 추가하지 않는다(볼 입력이 없다).
    client = _RecordingRedisClient()
    redis = _redis_with_fake_client(client)

    redis.mark_pattern_complete("dcinside/2", advanced_to_bedrock=False)

    assert client.calls == [
        ("srem", pattern_runner_settings.PENDING_PATTERN_KEY, "dcinside/2"),
    ], f"실패(전건 폐기) 케이스는 SADD 없이 SREM 만 호출돼야 한다: {client.calls}"


# ---------------------------------------------------------------------------
# PIPE-2SB-73 — 작업 집합 명령 실패는 에러로 중단하지 않고 로그만 남긴다
# ---------------------------------------------------------------------------

def test_mark_pattern_complete_swallows_redis_command_errors():
    redis = _redis_with_fake_client(_RaisingRedisClient())

    # SADD/SREM 이 모두 실패해도 예외가 전파되면 안 된다 — 이 줄에서 예외가 나면
    # 이 테스트가 FAIL 로 잡힌다(러너의 AssertionError 외 예외 처리 관례).
    redis.mark_pattern_complete("dcinside/3", advanced_to_bedrock=True)
    redis.mark_pattern_complete("dcinside/4", advanced_to_bedrock=False)


def test_mark_pattern_complete_noop_when_no_client_connected():
    # BATCH_REDIS_URL 미설정(url=None) 이고 _client 를 주입하지 않은 기본 상태 —
    # 작업 집합 없이도 예외 없이 넘어가야 한다(PIPE-2SB-73 — Redis 없이도 배치는
    # 멈추지 않는다).
    redis = PatternBatchRedis(url=None)
    assert redis._client is None
    redis.mark_pattern_complete("dcinside/5", advanced_to_bedrock=True)


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
