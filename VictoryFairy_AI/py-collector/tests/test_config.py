import pytest
from kbo_collector.config import Settings, get_settings


def _base_env(monkeypatch):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "b")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "s")


def test_defaults(monkeypatch):
    _base_env(monkeypatch)
    s = Settings(_env_file=None)
    assert s.s3_bucket == "b"
    assert s.s3_region == "ap-northeast-2"
    assert s.naver_base_url == "https://api-gw.sports.naver.com"
    assert s.top_comments == 20
    assert s.fetch_delay_ms == 800
    assert s.retry_attempts == 3
    assert s.http_timeout_s == 10.0


def test_env_override_and_types(monkeypatch):
    _base_env(monkeypatch)
    monkeypatch.setenv("COLLECTOR_TOP_COMMENTS", "5")
    monkeypatch.setenv("COLLECTOR_S3_PATH_STYLE", "true")
    monkeypatch.setenv("RETRY_BACKOFF_BASE", "0.25")
    s = Settings(_env_file=None)
    assert s.top_comments == 5
    assert s.s3_path_style is True
    assert s.retry_backoff_base == 0.25


def test_missing_required_raises(monkeypatch):
    monkeypatch.delenv("COLLECTOR_S3_BUCKET", raising=False)
    monkeypatch.delenv("COLLECTOR_PII_SALT", raising=False)
    with pytest.raises(Exception):
        Settings(_env_file=None)


def test_get_settings_cached(monkeypatch):
    _base_env(monkeypatch)
    get_settings.cache_clear()
    assert get_settings() is get_settings()
