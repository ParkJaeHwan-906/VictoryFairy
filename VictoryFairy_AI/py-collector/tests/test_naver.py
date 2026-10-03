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


# --------------------------------------------------------------------------- extract_inning_events
# relay_inning_events.json 은 실측 샘플(2026-09-20 한화-LG전, S3 raw-json/relay)의
# 5회 실제 구조를 그대로 옮긴 fixture다 — relay_inning.json(relay_is_empty 전용
# synthetic)과는 별개.
INN_FIX = json.loads(
    (FIX / "relay_inning_events.json").read_text(encoding="utf-8"))


def test_extract_inning_events_finds_hits_by_result_text_top_half():
    # 5회초(away 공격, half=0): 한지윤 1루타 / 최인호 1루타 / 정은원 번트안타만
    # 안타 — 병살타·희생플라이 아웃은 "안타"/"루타"/"홈런" 중 어느 것도 포함하지
    # 않아 섞이지 않는다.
    events = naver.extract_inning_events(INN_FIX, inning=5, half=0)
    assert events == {
        "60123": {"hit": True},   # 한지윤 : 우익수 앞 1루타
        "60456": {"hit": True},   # 최인호 : 우익수 앞 1루타
        "60789": {"hit": True},   # 정은원 : 투수 왼쪽 번트안타
    }
    # 병살타(허인서)·희생플라이 아웃(박정현)은 안타가 아니다
    assert "60999" not in events
    assert "61000" not in events


def test_extract_inning_events_finds_hits_by_result_text_bottom_half():
    # 5회말(home 공격, half=1): 문정빈 1루타 / 오스틴 1루타 / 박해민 2루타 /
    # (fallback pcode 타석) 이재원 홈런. 강백호 땅볼 아웃·송찬의 삼진은 비안타.
    events = naver.extract_inning_events(INN_FIX, inning=5, half=1)
    assert events == {
        "62001": {"hit": True},   # 문정빈 : 중견수 왼쪽 1루타
        "62002": {"hit": True},   # 오스틴 : 좌익수 앞 1루타
        "62003": {"hit": True},   # 박해민 : 우익수 오른쪽 2루타
        "62006": {"hit": True},   # 이재원 : 좌익수 뒤 홈런 (batterRecord 없이 fallback)
    }
    assert "62004" not in events  # 강백호 : 유격수 땅볼 아웃
    assert "62005" not in events  # 송찬의 : 삼진 아웃


def test_extract_inning_events_pcode_fallback_to_current_game_state_batter():
    # 마지막 타석은 textOptions 어디에도 batterRecord가 없다(소개 없이 바로 결과) —
    # currentGameState.batter 로 pcode 를 떨어뜨려 받아야 한다.
    events = naver.extract_inning_events(INN_FIX, inning=5, half=1)
    assert events["62006"] == {"hit": True}


def test_extract_inning_events_wrong_inning_or_half_returns_empty():
    assert naver.extract_inning_events(INN_FIX, inning=6, half=0) == {}
    # half 를 뒤집으면(초<->말) 그 공수의 안타는 전혀 안 걸린다
    assert "60123" not in naver.extract_inning_events(INN_FIX, inning=5, half=1)
    assert "62001" not in naver.extract_inning_events(INN_FIX, inning=5, half=0)


def test_extract_inning_events_handles_empty_or_missing_relay():
    assert naver.extract_inning_events({}, inning=5, half=0) == {}
    assert naver.extract_inning_events(None, inning=5, half=0) == {}
    assert naver.extract_inning_events({"result": None}, inning=5, half=0) == {}
