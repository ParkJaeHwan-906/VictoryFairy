import contextlib
import sys
from pathlib import Path
from types import SimpleNamespace

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


# --- DB jobs (records / registrations) — kbo-collector-db 함수 경로 ---

class _DummyDb:
    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


def _isolate_db(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    db = _DummyDb()
    monkeypatch.setattr(handler, "DbSink", lambda s: db)
    # DB 잡은 S3 sink 를 만들면 안 된다
    monkeypatch.setattr(handler, "S3RawSink", _boom("S3RawSink"))
    return db


def test_handler_records_range_backfill(monkeypatch, settings):
    db = _isolate_db(monkeypatch, settings)
    captured = {}

    def fake_range(start, end, **k):
        captured["range"] = (start, end)
        return {"loaded": ["g1", "g2"], "failed": ["g3"]}

    monkeypatch.setattr(handler.run, "land_game_records_range", fake_range)
    monkeypatch.setattr(handler.run, "land_registrations", _boom("land_registrations"))
    out = handler.handler({"job": "records", "from": "2026-07-01", "to": "2026-07-03"}, None)
    assert captured["range"] == ("2026-07-01", "2026-07-03")
    assert out["records"] == {"loaded": 2, "failed": ["g3"]}
    assert db.closed


def test_handler_records_defaults_to_single_date(monkeypatch, settings):
    db = _isolate_db(monkeypatch, settings)
    captured = {}

    def fake_range(start, end, **k):
        captured["range"] = (start, end)
        return {"loaded": [], "failed": []}

    monkeypatch.setattr(handler.run, "land_game_records_range", fake_range)
    handler.handler({"job": "records", "date": "2026-07-10"}, None)
    assert captured["range"] == ("2026-07-10", "2026-07-10")
    assert db.closed


def test_handler_registrations(monkeypatch, settings):
    db = _isolate_db(monkeypatch, settings)
    captured = {}

    def fake_regs(date, **k):
        captured["date"] = date
        return ["OB", "LG"]

    monkeypatch.setattr(handler.run, "land_registrations", fake_regs)
    monkeypatch.setattr(handler.run, "land_game_records_range", _boom("records"))
    out = handler.handler({"job": "registrations"}, None)
    assert captured["date"] is None  # 미지정 -> KBO 사이트의 최신 등록일
    assert out["registrations"] == ["OB", "LG"]
    assert db.closed


def test_handler_db_job_closes_connection_on_error(monkeypatch, settings):
    db = _isolate_db(monkeypatch, settings)

    def boom(*a, **k):
        raise RuntimeError("db down")

    monkeypatch.setattr(handler.run, "land_registrations", boom)
    try:
        handler.handler({"job": "registrations"}, None)
        raise AssertionError("should propagate")
    except RuntimeError:
        pass
    assert db.closed


# --- S3-only jobs (kbo_records / game_schedule) — kbo-collector (non-VPC) 함수 경로 ---

def test_handler_kbo_records_job(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    monkeypatch.setattr(handler.run, "land_schedule", _boom("land_schedule"))
    monkeypatch.setattr(handler.run, "land_community", _boom("land_community"))
    called = {}

    class FakeSrc:
        needs_db = False

        def collect(self, ctx):
            called["date"] = ctx.date
            return SimpleNamespace(loaded=10, failed=[])

    monkeypatch.setattr("kbo_collector.sources.base.get_source", lambda sid: FakeSrc())
    out = handler.handler({"job": "kbo_records"}, None)
    assert out["kboRecords"] == {"loaded": 10, "failed": []}
    assert called["date"] is None  # date 미지정 -> 소스가 자체 오늘 날짜로 처리


def test_handler_game_schedule_job(monkeypatch, settings):
    _isolate(monkeypatch, settings)
    seq = []
    monkeypatch.setattr(handler.run, "land_schedule",
                        lambda date, **kw: seq.append(("land", date)) or [])
    monkeypatch.setattr("kbo_collector.exports.exporter.export",
                        lambda t, **kw: seq.append(("export", t, kw["date"])) or 3)
    out = handler.handler({"job": "game_schedule", "date": "2026-07-31"}, None)
    assert seq == [("land", "2026-07-31"), ("export", "game_schedule", "2026-07-31")]
    assert out["gameSchedule"] == 3


def test_handler_game_schedule_defaults_to_kst_today_not_utc(monkeypatch, settings):
    # 03:00 KST game 잡은 UTC-오늘=KST-어제 기준이라 _today() 를 쓴다. game_schedule 은
    # 반대로 "당일 예정경기"를 내다보는 잡이라 KST-오늘이어야 한다 — _today() (UTC) 로
    # 잘못 배선되면 이 테스트가 실패한다.
    _isolate(monkeypatch, settings)
    monkeypatch.setattr(handler, "_kst_today", lambda: "2026-08-01")
    monkeypatch.setattr(handler, "_today", _boom("_today"))
    seq = []
    monkeypatch.setattr(handler.run, "land_schedule",
                        lambda date, **kw: seq.append(("land", date)) or [])
    monkeypatch.setattr("kbo_collector.exports.exporter.export",
                        lambda t, **kw: seq.append(("export", t, kw["date"])) or 0)
    handler.handler({"job": "game_schedule"}, None)
    assert seq == [("land", "2026-08-01"), ("export", "game_schedule", "2026-08-01")]


# --- export job (docType 기반 S3 적재) — kbo-collector-db 함수 경로 ---

def test_handler_export_job_uses_db_function(monkeypatch, settings):
    # export는 DB reader(예: game_result)를 쓸 수 있으므로 DB 잡과 같은 -db 함수 분기로
    # 가야 한다(S3 함수로 잘못 가면 DbSink 없이 실행돼 DB 기반 docType이 깨진다).
    _isolate(monkeypatch, settings)  # S3RawSink 는 여기선 정상 경로(export 내부 sink)
    db = _DummyDb()
    monkeypatch.setattr(handler, "DbSink", lambda s: db)
    monkeypatch.setattr(handler.run, "land_schedule", _boom("land_schedule"))
    monkeypatch.setattr(handler.run, "land_community", _boom("land_community"))
    captured = {}

    def fake_export(target, **kw):
        captured["target"] = target
        captured["db"] = kw.get("db")
        captured["sink"] = kw.get("sink")
        captured["date"] = kw.get("date")
        return 5

    monkeypatch.setattr("kbo_collector.exports.exporter.export", fake_export)
    out = handler.handler({"job": "export", "target": "game_result"}, None)
    assert out["exported"] == 5
    assert captured["target"] == "game_result"
    assert captured["db"] is db
    assert captured["sink"] is not None
    assert db.closed


# --- games_sync job (경기 상태 동기화) — kbo-collector-db 함수 경로 ---

def test_handler_games_sync_job(monkeypatch, settings):
    db = _isolate_db(monkeypatch, settings)
    captured = {}

    def fake_games_sync(s, d, date):
        captured["settings"] = s
        captured["db"] = d
        captured["date"] = date
        return 12

    monkeypatch.setattr(handler.run, "job_games_sync", fake_games_sync)
    monkeypatch.setattr(handler.run, "land_registrations", _boom("land_registrations"))
    monkeypatch.setattr(handler.run, "land_game_records_range", _boom("land_game_records_range"))
    out = handler.handler({"job": "games_sync", "date": "2026-07-31"}, None)
    assert captured["settings"] is settings
    assert captured["db"] is db
    assert captured["date"] == "2026-07-31"
    assert out["gamesSynced"] == 12
    assert db.closed


def test_handler_games_sync_defaults_to_kst_today_not_utc(monkeypatch, settings):
    # "당일 경기 상태"를 다루는 잡이라 KST-오늘이어야 한다 — _today() (UTC) 로
    # 잘못 배선되면 이 테스트가 실패한다.
    db = _isolate_db(monkeypatch, settings)
    monkeypatch.setattr(handler, "_kst_today", lambda: "2026-08-01")
    monkeypatch.setattr(handler, "_today", _boom("_today"))
    captured = {}

    def fake_games_sync(s, d, date):
        captured["date"] = date
        return 0

    monkeypatch.setattr(handler.run, "job_games_sync", fake_games_sync)
    handler.handler({"job": "games_sync"}, None)
    assert captured["date"] == "2026-08-01"
    assert db.closed
