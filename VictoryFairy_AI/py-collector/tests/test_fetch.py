import httpx
import pytest
import respx

from kbo_collector.config import Settings
from kbo_collector import fetch


def _settings(monkeypatch, attempts=3):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "b")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "s")
    monkeypatch.setenv("RETRY_ATTEMPTS", str(attempts))
    monkeypatch.setenv("RETRY_BACKOFF_BASE", "0")  # no real sleeping in tests
    return Settings(_env_file=None)


@respx.mock
def test_sends_browser_headers(monkeypatch):
    s = _settings(monkeypatch)
    route = respx.get("https://api-gw.sports.naver.com/x").mock(
        return_value=httpx.Response(200, json={"ok": True})
    )
    with fetch.build_client(s) as client:
        resp = fetch.fetch(client, "https://api-gw.sports.naver.com/x",
                           settings=s, referer=s.naver_referer)
    assert resp.status_code == 200
    sent = route.calls.last.request
    assert "Mozilla" in sent.headers["user-agent"]
    assert sent.headers["referer"] == "https://m.sports.naver.com/"
    assert sent.headers["accept"] == "application/json"


@respx.mock
def test_retries_then_succeeds(monkeypatch):
    s = _settings(monkeypatch, attempts=3)
    respx.get("https://x/y").mock(side_effect=[
        httpx.Response(500),
        httpx.Response(500),
        httpx.Response(200, text="ok"),
    ])
    with fetch.build_client(s) as client:
        resp = fetch.fetch(client, "https://x/y", settings=s)
    assert resp.text == "ok"


@respx.mock
def test_exhausts_and_raises_fetcherror(monkeypatch):
    s = _settings(monkeypatch, attempts=2)
    respx.get("https://x/down").mock(return_value=httpx.Response(503))
    with fetch.build_client(s) as client:
        with pytest.raises(fetch.FetchError):
            fetch.fetch(client, "https://x/down", settings=s)


@respx.mock
def test_transport_error_becomes_fetcherror(monkeypatch):
    s = _settings(monkeypatch, attempts=2)
    respx.get("https://x/boom").mock(side_effect=httpx.ConnectError("boom"))
    with fetch.build_client(s) as client:
        with pytest.raises(fetch.FetchError):
            fetch.fetch(client, "https://x/boom", settings=s)


def _state(exc, attempt=1):
    from types import SimpleNamespace
    return SimpleNamespace(outcome=SimpleNamespace(exception=lambda: exc),
                           attempt_number=attempt, seconds_since_start=0.0, idle_for=0.0)


def _status_error(code):
    req = httpx.Request("GET", "https://x")
    return httpx.HTTPStatusError("e", request=req, response=httpx.Response(code, request=req))


def test_rate_limit_uses_long_cooldown(monkeypatch):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "b")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "s")
    monkeypatch.setenv("RETRY_BACKOFF_BASE", "0")
    monkeypatch.setenv("RATE_LIMIT_COOLDOWN_S", "20")
    s = Settings(_env_file=None)
    wait = fetch._make_wait(s)
    # 429/430 -> long cooldown; 500 -> fast backoff (well under the cooldown).
    assert wait(_state(_status_error(430))) >= 20
    assert wait(_state(_status_error(429))) >= 20
    assert wait(_state(_status_error(500), attempt=1)) < 20
