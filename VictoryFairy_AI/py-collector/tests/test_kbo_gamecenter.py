"""KBO 게임센터(GetKboGameList) 변환·매칭.

픽스처 값은 2026-10-26 에 실제 응답에서 옮겼다 — 진행 중(9회초 5:1), 경기 전,
종료(연장 11회 무승부), 취소(CANCEL_SC_ID=1) 네 모양이 네이버 모양으로 바뀌는지와,
네이버 id 규칙이 G_ID 와 다른 포스트시즌 경기를 대진으로 짝짓는지를 건다.
"""
import httpx
import pytest
import respx

from kbo_collector import game_records, kbo_gamecenter

TEAM_IDS = {"HT": 1, "SS": 2, "LG": 3, "OB": 4}


def _kg(g_id, state, inn=None, tb=None, away_score="0", home_score="0", cancel="0",
        tm="18:30"):
    return {"G_ID": g_id, "G_DT": g_id[:8], "G_TM": tm, "AWAY_ID": g_id[8:10],
            "HOME_ID": g_id[10:12], "GAME_STATE_SC": state, "GAME_INN_NO": inn,
            "GAME_TB_SC": tb, "T_SCORE_CN": away_score, "B_SCORE_CN": home_score,
            "CANCEL_SC_ID": cancel}


def test_live_game_becomes_started_with_inning_and_scores():
    g = kbo_gamecenter.to_schedule_game(
        _kg("20261026HTSS0", "2", 9, "T", away_score="5", home_score="1"), "55551026HTSS02026")

    assert g["gameId"] == "55551026HTSS02026"
    assert game_records.map_status(g) == "IN_PROGRESS"
    assert game_records.parse_inning(g["statusInfo"]) == (9, 0)
    assert (g["homeTeamCode"], g["awayTeamCode"]) == ("SS", "HT")
    assert (g["homeTeamScore"], g["awayTeamScore"]) == (1, 5)
    assert g["gameDateTime"] == "2026-10-26T18:30:00"


def test_before_game_is_scheduled_without_inning():
    g = kbo_gamecenter.to_schedule_game(_kg("20261027HTSS0", "1"), "x")

    assert game_records.map_status(g) == "SCHEDULED"
    assert g["statusInfo"] == ""


def test_finished_extra_inning_draw_keeps_last_inning():
    # 종료 경기도 마지막 이닝을 실어야 last_inning 이 채워진다(네이버 statusInfo 와 같은 규칙).
    g = kbo_gamecenter.to_schedule_game(
        _kg("20260426LTHT0", "3", 11, "B", away_score="5", home_score="5", tm="14:00"), "x")

    assert game_records.map_status(g) == "DRAW"
    assert game_records.parse_inning(g["statusInfo"], max_inning=None) == (11, 1)


def test_cancelled_game_maps_to_canceled():
    g = kbo_gamecenter.to_schedule_game(_kg("20261025HTSS0", "4", 0, "T", cancel="1"), "x")

    assert game_records.map_status(g) == "CANCELED"


def test_unknown_state_is_left_to_map_status_as_unknown():
    g = kbo_gamecenter.to_schedule_game(_kg("20261026HTSS0", "9"), "x")

    assert game_records.map_status(g) is None


def test_match_by_matchup_even_when_naver_id_differs_from_g_id():
    rows = [("55551026HTSS02026", 2, 1), ("20261026LGOB02026", 4, 3)]

    assert kbo_gamecenter.match_game_id(
        _kg("20261026HTSS0", "2"), rows, TEAM_IDS) == "55551026HTSS02026"


def test_match_splits_doubleheader_by_header_digit():
    rows = [("20260705HTSS12026", 2, 1), ("20260705HTSS22026", 2, 1)]

    assert kbo_gamecenter.match_game_id(
        _kg("20260705HTSS2", "1"), rows, TEAM_IDS) == "20260705HTSS22026"


def test_match_returns_none_without_a_unique_row():
    assert kbo_gamecenter.match_game_id(_kg("20261026HTSS0", "2"), [], TEAM_IDS) is None
    # 홈·원정이 뒤집힌 행은 다른 경기다.
    assert kbo_gamecenter.match_game_id(
        _kg("20261026HTSS0", "2"), [("x", 1, 2)], TEAM_IDS) is None


@respx.mock
def test_fetch_day_posts_date_and_drops_unknown_teams(settings):
    route = respx.post(kbo_gamecenter.list_url(settings)).mock(return_value=httpx.Response(
        200, content="﻿".encode() + httpx.Response(200, json={"game": [
            _kg("20261026HTSS0", "2"),
            {**_kg("20260712EAWE0", "3"), "AWAY_ID": "EA", "HOME_ID": "WE"},  # 올스타
        ]}).content))

    with httpx.Client() as client:
        games = kbo_gamecenter.fetch_day("2026-10-26", settings=settings, client=client)

    assert [g["G_ID"] for g in games] == ["20261026HTSS0"]
    assert b"date=20261026" in route.calls[0].request.content


@respx.mock
@pytest.mark.parametrize("body, expected", [
    ({"game": [], "code": "100", "msg": "정상"}, []),   # 경기 없는 날(2026-11-10 실측)
    ({"code": "500", "msg": "error"}, None),            # game 배열 없음 = 깨진 응답
    ({"game": None}, None),
])
def test_fetch_day_tells_empty_day_from_broken_response(settings, body, expected):
    # 빈 날은 사라진 경기 판정을 하고, 깨진 응답은 하지 않는다 — 둘을 같은 [] 로 돌려주면 안 된다.
    respx.post(kbo_gamecenter.list_url(settings)).mock(return_value=httpx.Response(200, json=body))

    with httpx.Client() as client:
        assert kbo_gamecenter.fetch_day("2026-11-10", settings=settings, client=client) == expected


def test_naver_game_id_follows_naver_rule_per_series():
    # 정규시즌·준PO·PO 는 2026-10 실측 쌍, 한국시리즈(7)는 같은 규칙의 추정값.
    assert kbo_gamecenter.naver_game_id({"G_ID": "20261006KTWO0", "SR_ID": 0}) == "20261006KTWO02026"
    assert kbo_gamecenter.naver_game_id({"G_ID": "20261020HTLG0", "SR_ID": 3}) == "33331020HTLG02026"
    assert kbo_gamecenter.naver_game_id({"G_ID": "20261025HTSS0", "SR_ID": 5}) == "55551025HTSS02026"
    assert kbo_gamecenter.naver_game_id({"G_ID": "20261103SSKT0", "SR_ID": 7}) == "77771103SSKT02026"


def test_naver_game_id_is_none_for_unknown_series_or_bad_id():
    assert kbo_gamecenter.naver_game_id({"G_ID": "20260310SSKT0", "SR_ID": 1}) is None
    assert kbo_gamecenter.naver_game_id({"G_ID": "20261103SSKT0"}) is None
    assert kbo_gamecenter.naver_game_id({"G_ID": "bad", "SR_ID": 0}) is None


def test_schedule_game_carries_kbo_stadium_name():
    g = kbo_gamecenter.to_schedule_game({**_kg("20261103SSKT0", "1"), "S_NM": "수원"}, "x")

    assert g["stadium"] == "수원"
