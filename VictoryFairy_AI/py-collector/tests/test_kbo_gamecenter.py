"""KBO 게임센터(GetKboGameList) 변환·매칭.

픽스처 값은 2026-10-26 에 실제 응답에서 옮겼다 — 진행 중(9회초 5:1), 경기 전,
종료(연장 11회 무승부), 취소(CANCEL_SC_ID=1) 네 모양이 네이버 모양으로 바뀌는지와,
네이버 id 규칙이 G_ID 와 다른 포스트시즌 경기를 대진으로 짝짓는지를 건다.
"""
import httpx
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
