"""Lambda 핸들러 테스트 — S3 이벤트 / SQS 배치 진입점.

pytest 없이 stdlib 로 실행:  `.venv/bin/python tests/test_lambda_handlers.py`

⚠️ 실제 AWS 를 호출하지 않는다. S3 는 인메모리 페이크, SQS·Bedrock 은 스텁이다.

핸들러는 **배선만** 하므로 판정 자체는 검증하지 않는다(그건 test_run_validation·
test_run_bedrock 소관). 여기서 확인하는 것은 배선에서만 생길 수 있는 사고다:
  - S3 이벤트 키의 URL 인코딩을 디코드하는가
  - 마커가 있으면 재판정하지 않는가 (at-least-once 배달 대비)
  - 통과분만 큐로 넘기는가
  - 큐 전송이 마커 기록 **뒤에** 일어나는가
  - 예산 상한에서 배치를 삼키지 않고 큐에 남기는가
"""

import io
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline import lambda_bedrock, lambda_pattern  # noqa: E402
from pipeline.core.config import pipeline_settings  # noqa: E402
from pipeline.s3_io import manifest_key, output_key  # noqa: E402

SOURCE = "dcinside"
DATE = "2026-07-22"

_results = []


def check(name, fn):
    try:
        fn()
        _results.append((name, None))
    except AssertionError as exc:
        _results.append((name, str(exc) or "assertion failed"))


class _FakeS3:
    def __init__(self):
        self.objects = {}

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


class _FakeSQS:
    def __init__(self):
        self.sent = []

    def send_message(self, QueueUrl, MessageBody):
        self.sent.append(json.loads(MessageBody))


class _override:
    def __init__(self, target, **values):
        self.target, self.values = target, values

    def __enter__(self):
        self.saved = {k: getattr(self.target, k, None) for k in self.values}
        for k, v in self.values.items():
            setattr(self.target, k, v)
        return self

    def __exit__(self, *exc):
        for k, v in self.saved.items():
            setattr(self.target, k, v)


def _post(post_id="1", body="오늘 경기 재밌었다", title="제목"):
    return {"postExternalId": post_id, "title": title, "body": body, "topComments": []}


def _s3_event(key):
    return {"Records": [{"s3": {"bucket": {"name": "b"}, "object": {"key": key}}}]}


def _seed_community(fake, post, source=SOURCE, date=DATE):
    key = f"community/{source}/{date}/{post['postExternalId']}.json"
    fake.objects[key] = json.dumps(post, ensure_ascii=False).encode("utf-8")
    return key


# ---------------------------------------------------------------------------
# 패턴 핸들러
# ---------------------------------------------------------------------------

def test_parse_key_rejects_non_conforming():
    for bad in ("validation/pattern/success/a/b/c.json", "community/a/b.json",
                "community/a/b/c.txt", "community//b/c.json"):
        try:
            lambda_pattern.parse_key(bad)
            raised = False
        except ValueError:
            raised = True
        assert raised, f"규약 위반 키를 통과시켰다: {bad}"


def test_extract_keys_decodes_url_encoding():
    """S3 이벤트의 키는 URL 인코딩돼 있다 — 디코드하지 않으면 GetObject 가 NoSuchKey 다."""
    event = _s3_event("community/dcinside/2026-07-22/%ED%95%9C%EA%B8%80+id.json")
    assert lambda_pattern._extract_keys(event) == ["community/dcinside/2026-07-22/한글 id.json"]


def test_pattern_handler_processes_and_enqueues_passing_post():
    fake, sqs = _FakeS3(), _FakeSQS()
    key = _seed_community(fake, _post("11"))

    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_pattern, _s3_client=lambda: fake, _sqs_client=lambda: sqs), \
            _override(lambda_pattern.os, environ={"BEDROCK_QUEUE_URL": "q"}):
        out = lambda_pattern.handler(_s3_event(key))

    assert out["processed"] == 1 and out["enqueued"] == 1, out
    assert output_key("success", SOURCE, DATE, "11") in fake.objects
    assert manifest_key(SOURCE, DATE, "11") in fake.objects
    assert sqs.sent == [{"source": SOURCE, "date": DATE, "postId": "11"}]


def test_pattern_handler_does_not_enqueue_discarded_post():
    """전건 폐기된 게시글은 Bedrock 이 볼 입력이 없다 — 큐에 넣으면 빈 작업이 돈다."""
    fake, sqs = _FakeS3(), _FakeSQS()
    key = _seed_community(fake, _post("12", body="시발 개새끼야", title="시발"))

    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_pattern, _s3_client=lambda: fake, _sqs_client=lambda: sqs), \
            _override(lambda_pattern.os, environ={"BEDROCK_QUEUE_URL": "q"}):
        out = lambda_pattern.handler(_s3_event(key))

    assert out["processed"] == 1, out
    assert out["enqueued"] == 0, "폐기 게시글을 큐에 넣었다"
    assert sqs.sent == []


def test_pattern_handler_skips_when_marker_exists():
    """S3 이벤트는 at-least-once 다 — 같은 객체로 두 번 불릴 수 있다."""
    fake, sqs = _FakeS3(), _FakeSQS()
    key = _seed_community(fake, _post("13"))
    fake.objects[manifest_key(SOURCE, DATE, "13")] = b"{}"

    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_pattern, _s3_client=lambda: fake, _sqs_client=lambda: sqs), \
            _override(lambda_pattern.os, environ={"BEDROCK_QUEUE_URL": "q"}):
        out = lambda_pattern.handler(_s3_event(key))

    assert out == {"processed": 0, "skipped": 1, "enqueued": 0}, out
    assert sqs.sent == [], "마커가 있는데 큐에 다시 넣었다 — 중복 판정·중복 과금"


def test_pattern_handler_enqueues_after_marker_is_written():
    """큐 전송이 마커보다 먼저면, 그 사이 실패 시 재시도가 같은 글을 또 넣는다."""
    fake, sqs = _FakeS3(), _FakeSQS()
    key = _seed_community(fake, _post("14"))
    marker = manifest_key(SOURCE, DATE, "14")
    violations = []

    class _CheckingSQS(_FakeSQS):
        def send_message(self, QueueUrl, MessageBody):
            if marker not in fake.objects:
                violations.append(MessageBody)
            super().send_message(QueueUrl=QueueUrl, MessageBody=MessageBody)

    checking = _CheckingSQS()
    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_pattern, _s3_client=lambda: fake, _sqs_client=lambda: checking), \
            _override(lambda_pattern.os, environ={"BEDROCK_QUEUE_URL": "q"}):
        lambda_pattern.handler(_s3_event(key))

    assert not violations, "마커 기록 전에 큐로 보냈다"


def test_pattern_handler_raises_when_queue_url_missing():
    """조용히 넘어가면 패턴 통과분이 2단계로 영영 안 넘어간다."""
    fake = _FakeS3()
    key = _seed_community(fake, _post("15"))

    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_pattern, _s3_client=lambda: fake, _sqs_client=lambda: _FakeSQS()), \
            _override(lambda_pattern.os, environ={}):
        try:
            lambda_pattern.handler(_s3_event(key))
            raised = False
        except RuntimeError:
            raised = True
    assert raised, "BEDROCK_QUEUE_URL 이 없으면 올려야 한다"


# ---------------------------------------------------------------------------
# Bedrock 핸들러
# ---------------------------------------------------------------------------

def _sqs_event(post_ids, date=DATE):
    return {
        "Records": [
            {"messageId": f"m{p}",
             "body": json.dumps({"source": SOURCE, "date": date, "postId": p})}
            for p in post_ids
        ]
    }


def _seed_pattern_success(fake, post_id):
    key = output_key("success", SOURCE, DATE, post_id, method="pattern")
    fake.objects[key] = json.dumps(_post(post_id), ensure_ascii=False).encode("utf-8")


class _StubSpend:
    def __init__(self, spend=0.0):
        self.spend = spend
        self.added = []

    def get_spend(self):
        return self.spend

    def add_spend(self, amount):
        self.added.append(amount)


def test_bedrock_handler_returns_all_failures_when_over_budget():
    """상한에서 삼키면 그 게시글들은 영구 미판정이 된다 — 큐에 남겨야 한다."""
    fake = _FakeS3()
    for p in ("1", "2"):
        _seed_pattern_success(fake, p)

    called = []
    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_bedrock, _s3_client=lambda: fake,
                      validate_startup=lambda: None,
                      DynamoDBSpendCounter=lambda *a, **k: _StubSpend(spend=999.0)), \
            _override(lambda_bedrock.judge_service, judge_batch=lambda items: called.append(items)):
        out = lambda_bedrock.handler(_sqs_event(["1", "2"]))

    assert called == [], "상한 초과인데 모델을 호출했다"
    assert {f["itemIdentifier"] for f in out["batchItemFailures"]} == {"m1", "m2"}


def test_bedrock_handler_skips_message_when_marker_exists():
    """SQS 도 at-least-once 다."""
    fake = _FakeS3()
    _seed_pattern_success(fake, "3")
    fake.objects[manifest_key(SOURCE, DATE, "3", method="bedrock")] = b"{}"

    called = []
    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_bedrock, _s3_client=lambda: fake,
                      validate_startup=lambda: None,
                      DynamoDBSpendCounter=lambda *a, **k: _StubSpend()), \
            _override(lambda_bedrock.judge_service, judge_batch=lambda items: called.append(items)):
        out = lambda_bedrock.handler(_sqs_event(["3"]))

    assert called == [], "마커가 있는데 모델을 호출했다 — 중복 과금"
    assert out["batchItemFailures"] == []


def test_bedrock_handler_ignores_malformed_message():
    """깨진 메시지 하나가 배치 전체를 막으면 안 된다."""
    event = {"Records": [{"messageId": "bad", "body": "not-json"}]}
    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_bedrock, _s3_client=lambda: _FakeS3(), validate_startup=lambda: None):
        out = lambda_bedrock.handler(event)
    assert out["batchItemFailures"] == []


def test_bedrock_handler_skips_when_pattern_output_missing():
    """정책 재적용으로 패턴 산출물이 지워졌을 수 있다 — 재시도해도 생기지 않는다."""
    fake = _FakeS3()  # 패턴 success 를 넣지 않는다
    called = []
    with _override(pipeline_settings, S3_BUCKET="b"), \
            _override(lambda_bedrock, _s3_client=lambda: fake,
                      validate_startup=lambda: None,
                      DynamoDBSpendCounter=lambda *a, **k: _StubSpend()), \
            _override(lambda_bedrock.judge_service, judge_batch=lambda items: called.append(items)):
        out = lambda_bedrock.handler(_sqs_event(["99"]))

    assert called == [], "입력이 없는데 모델을 호출했다"
    assert out["batchItemFailures"] == [], "무한 재배달을 유발하면 안 된다"


for _name, _fn in sorted((k, v) for k, v in list(globals().items()) if k.startswith("test_")):
    check(_name, _fn)

_failed = [(n, e) for n, e in _results if e]
for _n, _e in _results:
    print(f"{'FAIL' if _e else 'PASS'}  {_n}" + (f": {_e}" if _e else ""))
print(f"\n{len(_results) - len(_failed)}/{len(_results)} passed")
sys.exit(1 if _failed else 0)
