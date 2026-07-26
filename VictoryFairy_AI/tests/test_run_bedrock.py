"""pipeline.run_bedrock 단위 테스트 — 세 갈래 라우팅(`_route_post`) + 예산/마커
순서/shadow/비용 카운터 실패 계약.

pytest 없이 stdlib 로만 실행:  `.venv/bin/python3 tests/test_run_bedrock.py`
(`pipeline.run_bedrock` 은 `pydantic`·`pydantic_settings`·`boto3` 를 요구하는
경로를 거친다 — `.venv/bin/python3` 로 실행할 것.)

⚠️ 실제 AWS·Redis 를 호출하지 않는다:
  - S3: `pipeline.run_bedrock.build_s3_client` 를 이 파일의 `_FakeS3Client`(인메모리
    dict)로 몽키패치한다. `pipeline.s3_io` 의 나머지 함수(list_json_keys 등)는
    실제 코드 그대로 실행되고 페이크 클라이언트를 대상으로 동작한다.
  - LLM 판정: `bedrock.judge_service.judge_batch` 를 인스턴스 속성으로 몽키패치해
    판정 결과를 테스트가 직접 지정한다(bedrock 서비스 내부 로직은 tests/test_bedrock.py
    소관 — 여기서는 러너의 라우팅/S3/비용 로직만 본다).
  - Redis: `main()`의 `cost_tracker`/`spend_limit_usd` 인자(백필 전용 주입 지점,
    docstring 참고)로 커스텀 트래커를 주입해 `BatchRedis`/실 Redis 를 아예 만들지
    않는다. `BatchRedis` 자체의 "카운터 실패 vs 작업집합 실패" 구분은 그 클래스를
    직접 생성해 `_client` 속성만 페이크로 갈아 끼워 테스트한다(redis 패키지 불필요).

요구사항 ID 대응:
  PIPE-2SB-11c  test_route_post_three_branches_partition_without_overlap_or_gap
  PIPE-2SB-11b  test_route_post_body_abuse_discard_yields_full_post_fail_even_with_passing_comments
  PIPE-2SB-10b  test_route_post_body_slot_spam_discard_clears_body_field_only,
                test_route_post_title_slot_offtopic_discard_clears_title_field_not_body
  PIPE-2SB-10c  test_route_post_title_slot_offtopic_discard_clears_title_field_not_body
  PIPE-2SB-11   test_route_post_body_axis_b_discard_with_zero_passing_comments_yields_no_success
  PIPE-2SB-59   test_route_post_records_fallback_notes_for_passed_body_and_comments
  PIPE-2SB-74   test_main_checks_budget_before_batch_call_and_skips_untouched_posts_when_over_budget
  PIPE-2SB-72   test_main_writes_s3_marker_before_calling_srem_pending
  PIPE-2SB-70   test_main_shadow_mode_only_writes_shadow_key_and_skips_success_failed_marker_and_srem
  (비용 카운터 실패=중단 vs 작업집합 실패=로그만)
                test_main_exits_nonzero_when_cost_counter_unavailable,
                test_batch_redis_cost_counter_failure_raises_while_pending_set_failure_only_logs

⚠️ 커버하지 않은 것: PIPE-2SB-71/14(배치 크기·배칭 이점)은 판정 품질 측정이 필요해
`accuracy-tuner` 소관이라 여기선 배치 크기 기본값(5)로 소규모 시나리오만 돈다.
컨트롤러·k8s Job 트리거(PIPE-2SB-27~50)는 이 러너 밖(컨트롤러 프로세스) 이라 범위 밖.
"""

import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bedrock import bedrock_settings, judge_service  # noqa: E402
from bedrock.schemas.judgement import BatchJudgement, BatchUsage, JudgeResult  # noqa: E402

import pipeline.run_bedrock as run_bedrock  # noqa: E402
from pipeline.core.config import pipeline_settings  # noqa: E402
from pipeline.run_bedrock import (  # noqa: E402
    BatchRedis,
    BedrockCostCounterUnavailable,
    _route_post,
    main,
    runner_settings,
)
from pipeline.s3_io import manifest_key, object_exists, output_key, put_json_object, shadow_key  # noqa: E402

DATE = "2026-07-25"
SOURCE = "dcinside"


# ---------------------------------------------------------------------------
# 헬퍼
# ---------------------------------------------------------------------------

def _result(is_valid, axis=None, message="ok", fallback=False):
    return JudgeResult(is_valid=is_valid, axis=axis, message=message, fallback=fallback)


def _post(post_id="11229559", body="본문", title="제목", comments=None):
    return {
        "postExternalId": post_id,
        "source": SOURCE,
        "title": title,
        "body": body,
        "topComments": comments if comments is not None else [],
    }


def _comment(author, body):
    return {"author": author, "body": body}


class _override:
    """임의 객체의 속성을 일시 교체하고 종료 시 원복하는 컨텍스트 매니저.

    `bedrock_settings`/`pipeline_settings`/`judge_service` 같은 전역 싱글턴을
    건드려야 하는 `main()` 통합 테스트에서 테스트 간 오염을 막기 위해 쓴다.
    """

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
    """list_objects_v2/get_object/put_object/delete_object/head_object 만 흉내 낸
    인메모리 S3 클라이언트 — 실제 AWS 호출 없이 `pipeline.s3_io` 의 실제 함수들을
    그대로 태울 수 있다."""

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


class _NoOpCostTracker:
    def __init__(self, spend=0.0):
        self._spend = spend
        self.srem_calls = []

    def get_spend(self):
        return self._spend

    def add_spend(self, amount_usd):
        self._spend += amount_usd

    def srem_pending(self, member):
        self.srem_calls.append(member)


class _bedrock_ready:
    """`main()`이 요구하는 최소 설정(모델 ID·DRY_RUN/SHADOW false)을 전역 싱글턴에
    임시로 세팅한다. `**overrides` 로 개별 테스트가 필요한 값(예: BEDROCK_SHADOW)을
    덮어쓸 수 있다."""

    def __init__(self, **overrides):
        defaults = dict(BEDROCK_MODEL_ID="apac.test-model", BEDROCK_DRY_RUN=False, BEDROCK_SHADOW=False)
        defaults.update(overrides)
        self._override = _override(bedrock_settings, **defaults)

    def __enter__(self):
        self._override.__enter__()
        return self

    def __exit__(self, *exc):
        self._override.__exit__(*exc)


def _seed_pattern_success(client, bucket, post):
    key = output_key("success", SOURCE, DATE, post["postExternalId"], method="pattern")
    put_json_object(client, bucket, key, post)


# ---------------------------------------------------------------------------
# PIPE-2SB-11c — `_route_post` 세 갈래 라우팅 (S3/판정 서비스 불필요, 순수 로직)
# ---------------------------------------------------------------------------

def test_route_post_body_pass_returns_normal_success_no_full_fail():
    post = _post(body="정상 본문")
    results = [_result(True)]

    success, failed = _route_post(post, "body", "정상 본문", [], results)

    assert success is not None
    assert success["body"] == "정상 본문"
    assert failed == []


def test_route_post_body_abuse_discard_yields_full_post_fail_even_with_passing_comments():
    post = _post(body="욕설 본문")
    comments = [_comment("a", "통과 댓글")]
    results = [_result(False, axis="abuse", message="인신공격"), _result(True)]

    success, failed = _route_post(post, "body", "욕설 본문", comments, results)

    assert success is None, "PIPE-2SB-11b: abuse 로 폐기되면 통과 댓글이 있어도 전체 fail"
    assert len(failed) == 1 and failed[0]["unit"] == "body" and failed[0]["axis"] == "abuse"


def test_route_post_body_slot_spam_discard_clears_body_field_only():
    post = _post(body="광고 본문", title="제목")
    comments = [_comment("a", "통과 댓글")]
    results = [_result(False, axis="spam", message="광고"), _result(True)]

    success, failed = _route_post(post, "body", "광고 본문", comments, results)

    assert success is not None, "PIPE-2SB-11c 갈래(3): 통과 댓글이 있으면 success 생성"
    assert success["body"] == "", "PIPE-2SB-10b: 본문 자리(body) 필드만 비운다"
    assert success["title"] == "제목", "title 은 판정 자리가 아니므로 건드리지 않는다"
    assert [c["author"] for c in success["topComments"]] == ["a"]
    assert len(failed) == 1 and failed[0]["axis"] == "spam"


def test_route_post_title_slot_offtopic_discard_clears_title_field_not_body():
    # 빈 본문 글의 title 이 본문 자리 판정 단위가 된 경우(PIPE-2SB-8b).
    post = _post(body="", title="오프토픽 제목")
    comments = [_comment("a", "통과 댓글")]
    results = [_result(False, axis="offtopic", message="야구와 무관"), _result(True)]

    success, failed = _route_post(post, "title", "오프토픽 제목", comments, results)

    assert success is not None
    assert success["title"] == "", "PIPE-2SB-10b: 판정 단위가 title 이었으면 title 필드를 비운다"
    assert success["body"] == "", "원래도 빈 본문 — 별도로 건드리지 않는다"
    assert [c["author"] for c in success["topComments"]] == ["a"]


def test_route_post_body_axis_b_discard_with_zero_passing_comments_yields_no_success():
    post = _post(body="광고 본문")
    comments = [_comment("a", "욕설 댓글")]
    results = [_result(False, axis="spam", message="광고"), _result(False, axis="abuse", message="욕설")]

    success, failed = _route_post(post, "body", "광고 본문", comments, results)

    assert success is None, "PIPE-2SB-11: 통과 단위가 하나도 없으면 success 미생성"
    assert len(failed) == 2


def test_route_post_three_branches_partition_without_overlap_or_gap():
    # PIPE-2SB-11c: 세 갈래는 겹치지 않고 빈 곳도 없다 — abuse 만 전체 fail,
    # spam/offtopic 은 통과 댓글이 있으면 본문 자리만 비운 success.
    for axis in ("abuse", "spam", "offtopic"):
        post = _post(body="폐기될 본문")
        comments = [_comment("a", "통과 댓글")]
        results = [_result(False, axis=axis, message="사유"), _result(True)]

        success, failed = _route_post(post, "body", "폐기될 본문", comments, results)

        if axis == "abuse":
            assert success is None, f"axis={axis} 는 게시글 전체 fail 이어야 함"
        else:
            assert success is not None, f"axis={axis} 는 본문 자리만 비운 success 여야 함"
            assert success["body"] == ""
        assert len(failed) == 1 and failed[0]["axis"] == axis


def test_route_post_records_fallback_notes_for_passed_body_and_comments():
    post = _post(body="본문")
    comments = [_comment("a", "댓글")]
    results = [_result(True, message="ok", fallback=True), _result(True, message="ok", fallback=True)]

    success, failed = _route_post(post, "body", "본문", comments, results)

    assert success is not None and failed == []
    notes = success.get("bedrockFallback")
    assert notes is not None, "PIPE-2SB-59: 폴백 통과는 success 객체에 사유와 함께 기록돼야 한다"
    units = [n["unit"] for n in notes]
    assert units.count("body") == 1
    assert units.count("comment") == 1


# ---------------------------------------------------------------------------
# main() 통합 — S3/비용 카운터는 페이크·주입으로 격리
# ---------------------------------------------------------------------------

def test_main_checks_budget_before_batch_call_and_skips_untouched_posts_when_over_budget():
    # PIPE-2SB-74: 배치 호출 "시작 전"에 예산을 확인한다 — 이미 상한 이상이면
    # judge_batch 자체가 호출되지 않아야 한다(전건 미시작).
    client = _FakeS3Client()
    bucket = "test-bucket"
    _seed_pattern_success(client, bucket, _post(post_id="1"))
    _seed_pattern_success(client, bucket, _post(post_id="2"))

    call_count = {"n": 0}

    def _stub_judge_batch(items):
        call_count["n"] += 1
        return BatchJudgement(results=[_result(True) for _ in items], usage=BatchUsage())

    tracker = _NoOpCostTracker(spend=100.0)  # 이미 상한 초과.

    with _bedrock_ready(), _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_bedrock, build_s3_client=lambda: client), \
            _override(judge_service, judge_batch=_stub_judge_batch):
        result = main(date=DATE, cost_tracker=tracker, spend_limit_usd=30.0)

    assert call_count["n"] == 0, "예산 초과 상태에서는 judge_batch 자체가 호출되면 안 된다"
    assert result["processed"] == 0
    assert result["not_started"] == 2


def test_main_exits_nonzero_when_cost_counter_unavailable():
    # 비용 카운터에 접근할 수 없는 것은 "상한 도달"과 달라 명확한 에러로 중단해야
    # 한다(exit != 0) — 작업 집합 실패(로그만)와 섞이면 안 된다.
    client = _FakeS3Client()
    bucket = "test-bucket"
    _seed_pattern_success(client, bucket, _post(post_id="1"))

    class _RaisingTracker:
        def get_spend(self):
            raise BedrockCostCounterUnavailable("Redis 연결 실패")

        def add_spend(self, amount_usd):
            pass

        def srem_pending(self, member):
            pass

    exit_code = None
    with _bedrock_ready(), _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_bedrock, build_s3_client=lambda: client):
        try:
            main(date=DATE, cost_tracker=_RaisingTracker(), spend_limit_usd=30.0)
        except SystemExit as exc:
            exit_code = exc.code

    assert exit_code is not None and exit_code != 0, "비용 카운터 실패는 0이 아닌 종료 코드로 중단해야 한다"


def test_main_writes_s3_marker_before_calling_srem_pending():
    # PIPE-2SB-72: 완료 처리는 "S3 마커 기록 -> SREM" 순서다.
    client = _FakeS3Client()
    bucket = "test-bucket"
    post = _post(post_id="42")
    _seed_pattern_success(client, bucket, post)

    marker = manifest_key(SOURCE, DATE, "42", method="bedrock")
    order_calls = []

    class _OrderCheckingTracker:
        def get_spend(self):
            return 0.0

        def add_spend(self, amount_usd):
            pass

        def srem_pending(self, member):
            assert object_exists(client, bucket, marker), (
                "PIPE-2SB-72 위반: SREM 이 호출된 시점에 S3 완결 마커가 아직 없다"
            )
            order_calls.append(member)

    def _stub_judge_batch(items):
        return BatchJudgement(results=[_result(True) for _ in items], usage=BatchUsage())

    with _bedrock_ready(), _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_bedrock, build_s3_client=lambda: client), \
            _override(judge_service, judge_batch=_stub_judge_batch):
        result = main(date=DATE, cost_tracker=_OrderCheckingTracker(), spend_limit_usd=30.0)

    assert result["processed"] == 1
    assert order_calls == [f"{SOURCE}/42"]
    assert object_exists(client, bucket, output_key("success", SOURCE, DATE, "42", method="bedrock"))


def test_main_shadow_mode_only_writes_shadow_key_and_skips_success_failed_marker_and_srem():
    # PIPE-2SB-70: shadow 모드는 _shadow 에만 쓰고 success/failed/마커/작업 집합은
    # 전혀 건드리지 않는다.
    client = _FakeS3Client()
    bucket = "test-bucket"
    post = _post(post_id="7", body="폐기될 본문")
    _seed_pattern_success(client, bucket, post)

    def _stub_judge_batch(items):
        return BatchJudgement(results=[_result(False, axis="offtopic", message="무관")], usage=BatchUsage())

    tracker = _NoOpCostTracker()

    with _bedrock_ready(BEDROCK_SHADOW=True), _override(pipeline_settings, S3_BUCKET=bucket), \
            _override(run_bedrock, build_s3_client=lambda: client), \
            _override(judge_service, judge_batch=_stub_judge_batch):
        result = main(date=DATE, cost_tracker=tracker, spend_limit_usd=30.0)

    assert result["processed"] == 1
    assert not object_exists(client, bucket, output_key("success", SOURCE, DATE, "7", method="bedrock"))
    assert not object_exists(client, bucket, output_key("failed", SOURCE, DATE, "7", method="bedrock"))
    assert not object_exists(client, bucket, manifest_key(SOURCE, DATE, "7", method="bedrock"))
    assert object_exists(client, bucket, shadow_key(SOURCE, DATE, "7", method="bedrock"))
    assert tracker.srem_calls == [], "shadow 는 작업 집합(SREM)을 전혀 건드리지 않아야 한다"


# ---------------------------------------------------------------------------
# BatchRedis — 비용 카운터 실패(raise)와 작업 집합 실패(log-only)의 구분
# ---------------------------------------------------------------------------

class _RaisingRedisClient:
    def get(self, key):
        raise RuntimeError("get 실패")

    def incrbyfloat(self, key, amount):
        raise RuntimeError("incrbyfloat 실패")

    def srem(self, key, member):
        raise RuntimeError("srem 실패")


def test_batch_redis_cost_counter_failure_raises_while_pending_set_failure_only_logs():
    required = BatchRedis(url=None, cost_tracking_required=True)
    required._client = _RaisingRedisClient()  # 생성자의 실제 redis 접속을 우회하고 직접 주입.

    try:
        required.get_spend()
        raised_get = False
    except BedrockCostCounterUnavailable:
        raised_get = True
    assert raised_get, "비용 카운터 조회 실패는 대체 정본이 없어 예외로 올려야 한다"

    try:
        required.add_spend(1.23)
        raised_add = False
    except BedrockCostCounterUnavailable:
        raised_add = True
    assert raised_add, "비용 카운터 누적 실패도 예외로 올려야 한다"

    # 작업 집합(SREM)은 실패해도 절대 예외를 올리지 않는다(PIPE-2SB-73 — S3 마커가 정본).
    required.srem_pending(f"{SOURCE}/1")  # 예외가 나면 이 줄에서 테스트가 FAIL 로 잡힌다.

    # DRY_RUN 처럼 비용 추적이 면제된 경우(cost_tracking_required=False)는 관대하게 0.0/무시.
    optional = BatchRedis(url=None, cost_tracking_required=False)
    optional._client = _RaisingRedisClient()
    assert optional.get_spend() == 0.0
    optional.add_spend(5.0)  # 예외 없이 넘어가야 한다.


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
