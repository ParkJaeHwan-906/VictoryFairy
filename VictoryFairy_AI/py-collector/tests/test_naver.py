import json
from pathlib import Path

from kbo_collector import naver
from kbo_collector.config import Settings

FIX = Path(__file__).parent / "fixtures" / "naver"


def _settings(monkeypatch):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "b")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "s")
    return Settings(_env_file=None)


def test_url_builders(monkeypatch):
    s = _settings(monkeypatch)
    assert naver.schedule_url(s, "2026-07-10") == (
        "https://api-gw.sports.naver.com/schedule/games?fields=basic,statusNum,statusInfo"
        "&upperCategoryId=kbaseball&fromDate=2026-07-10&toDate=2026-07-10"
    )
    assert naver.result_url(s, "20260710LGOB02026") == \
        "https://api-gw.sports.naver.com/schedule/games/20260710LGOB02026"
    assert naver.relay_url(s, "20260710LGOB02026", 3) == \
        "https://api-gw.sports.naver.com/schedule/games/20260710LGOB02026/relay?inning=3"


def test_extract_game_ids_filters_kbo_only():
    # Fixture has 5 games: 2 finished KBO (kept), 1 cancelled KBO, 1 non-KBO,
    # 1 KBO with a missing gameId — only the 2 finished ones survive.
    data = json.loads((FIX / "schedule.json").read_text(encoding="utf-8"))
    assert naver.extract_game_ids(data) == ["20260710LGOB02026", "20260710HTSK02026"]


def test_extract_game_ids_handles_empty():
    assert naver.extract_game_ids({}) == []
    assert naver.extract_game_ids({"result": {}}) == []


def test_extract_game_ids_excludes_cancelled():
    # A cancelled game is BEFORE/cancel=true with a 0-0 skeleton. The S3 landing
    # path must skip it (same rule as the DB path), else its empty snapshot gets
    # frozen into S3 by the existence checkpoint and never self-heals.
    data = {"result": {"games": [
        {"categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "LG", "homeTeamCode": "OB", "gameId": "played"},
        {"categoryId": "kbo", "statusCode": "BEFORE", "cancel": True,
         "awayTeamCode": "KT", "homeTeamCode": "LG", "gameId": "rained-out"},
    ]}}
    assert naver.extract_game_ids(data) == ["played"]


def test_relay_is_empty():
    inning = json.loads((FIX / "relay_inning.json").read_text(encoding="utf-8"))
    empty = json.loads((FIX / "relay_empty.json").read_text(encoding="utf-8"))
    assert naver.relay_is_empty(inning) is False
    assert naver.relay_is_empty(empty) is True
    assert naver.relay_is_empty({}) is True
    assert naver.relay_is_empty({"result": None}) is True
