import boto3
import pytest
from moto import mock_aws

from kbo_collector.config import Settings


@pytest.fixture
def settings(monkeypatch):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "test-bronze")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "pepper")
    monkeypatch.setenv("COLLECTOR_S3_REGION", "ap-northeast-2")
    monkeypatch.setenv("RETRY_ATTEMPTS", "2")
    monkeypatch.setenv("RETRY_BACKOFF_BASE", "0")
    # ensure no LocalStack endpoint leaks into moto-based tests
    monkeypatch.delenv("COLLECTOR_S3_ENDPOINT", raising=False)
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "test")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "test")
    return Settings(_env_file=None)


@pytest.fixture
def s3_bucket(settings):
    with mock_aws():
        client = boto3.client("s3", region_name=settings.s3_region)
        client.create_bucket(
            Bucket=settings.s3_bucket,
            CreateBucketConfiguration={"LocationConstraint": settings.s3_region},
        )
        yield settings.s3_bucket
