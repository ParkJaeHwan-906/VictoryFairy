import contextlib
import sys
from pathlib import Path

# handler.py lives under deploy/lambda/ (baked into the Lambda image root at runtime)
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deploy" / "lambda"))
import handler  # noqa: E402


class _DummySink:
    def __init__(self, *a, **k):
        pass


@contextlib.contextmanager
def _dummy_client(*a, **k):
    yield object()


def _isolate(monkeypatch, settings):
    # no real settings/S3/client/journal — this is a pure routing test
    monkeypatch.setattr(handler, "get_settings", lambda: settings)
    monkeypatch.setattr(handler, "S3RawSink", _DummySink)
    monkeypatch.setattr(handler, "Journal", lambda *a, **k: object())
    monkeypatch.setattr(handler.fetch, "build_client", _dummy_client)


def _boom(name):
    def _f(*a, **k):
        raise AssertionError(f"{name} should not run for this job")
    return _f


def test_handler_community_only(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    monkeypatch.setattr(handler.run, "land_community", lambda date, **k: 7)
    monkeypatch.setattr(handler.run, "land_schedule", _boom("land_schedule"))
    out = handler.handler({"job": "community", "date": "2026-07-10"}, None)
    assert out == {"job": "community", "date": "2026-07-10", "community": 7}


def test_handler_daily_runs_schedule_result_relay(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    monkeypatch.setattr(handler.run, "land_schedule", lambda date, **k: ["g1", "g2"])
    monkeypatch.setattr(handler.run, "land_results", lambda date, gids, **k: len(gids))
    monkeypatch.setattr(handler.run, "land_relays", lambda date, gids, **k: 9)
    monkeypatch.setattr(handler.run, "land_community", _boom("land_community"))
    out = handler.handler({"job": "daily", "date": "2026-07-08"}, None)
    assert out["gameIds"] == 2
    assert out["results"] == 2
    assert out["relays"] == 9
    assert "community" not in out


def test_handler_defaults_to_community_today(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    monkeypatch.setattr(handler.run, "land_community", lambda date, **k: 1)
    out = handler.handler({}, None)  # no job -> community; no date -> today (UTC)
    assert out["job"] == "community"
    assert len(out["date"]) == 10  # YYYY-MM-DD


def test_handler_uses_request_id_as_run_id(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    captured = {}

    def fake_journal(job, date, run_id, journal_dir):
        captured["run_id"] = run_id
        return object()

    monkeypatch.setattr(handler, "Journal", fake_journal)
    monkeypatch.setattr(handler.run, "land_community", lambda date, **k: 0)

    class Ctx:
        aws_request_id = "abcdef012345-6789-more"

    handler.handler({"job": "community"}, Ctx())
    assert captured["run_id"] == "abcdef012345-678"  # truncated to 16 chars
