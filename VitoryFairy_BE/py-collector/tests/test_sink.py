import json

from kbo_collector.sink import S3RawSink


def test_put_then_exists(settings, s3_bucket):
    sink = S3RawSink(settings)
    key = "raw-json/schedule/2026-07-10/abc.json"
    assert sink.exists(key) is False
    n = sink.put(key, b'{"a":1}')
    assert n == 7
    assert sink.exists(key) is True


def test_put_json_roundtrip_and_idempotent(settings, s3_bucket):
    import boto3
    sink = S3RawSink(settings)
    key = "community/fmkorea/2026-07-10/1.json"
    sink.put_json(key, {"k": "값"})
    sink.put_json(key, {"k": "값2"})  # overwrite same key
    body = boto3.client("s3", region_name=settings.s3_region).get_object(
        Bucket=s3_bucket, Key=key)["Body"].read().decode("utf-8")
    assert json.loads(body) == {"k": "값2"}


def test_dead_letter_writes_payload(settings, s3_bucket):
    import boto3
    sink = S3RawSink(settings)
    sink.dead_letter("result", "2026-07-10", "gid1",
                     url="https://x", error=RuntimeError("boom"),
                     attempts=3, ts="2026-07-10T00:00:00")
    body = boto3.client("s3", region_name=settings.s3_region).get_object(
        Bucket=s3_bucket, Key="dead-letter/result/2026-07-10/gid1.json"
    )["Body"].read().decode("utf-8")
    payload = json.loads(body)
    assert payload["url"] == "https://x"
    assert "boom" in payload["error"]
    assert payload["attempts"] == 3
