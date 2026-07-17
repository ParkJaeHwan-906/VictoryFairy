from kbo_collector import game_records as gr

RECORD = {
    "gameInfo": {
        "gdate": 20260328, "round": 1, "gameFlag": "0", "stadium": "문학",
        "gtime": "14:00", "aCode": "HT", "hCode": "SK", "aPCode": "54640", "hPCode": "55855",
    },
    "scoreBoard": {
        "rheb": {"away": {"r": 6, "h": 12, "e": 0, "b": 4},
                 "home": {"r": 7, "h": 8, "e": 1, "b": 6}},
        "inn": {"away": [2, 0, 1, 0, 2, 0, 0, 0, 1], "home": [0, 0, 0, 0, 0, 0, 3, 0, 4]},
    },
    "pitchingResult": [
        {"pCode": "68043", "wls": "W"}, {"pCode": "63342", "wls": "L"},
    ],
    "pitchersBoxscore": {
        "away": [{"pcode": "54640", "name": "네일", "inn": "6", "bf": 84, "ab": 20,
                  "hit": 2, "r": 0, "er": 0, "hr": 0, "bbhp": 1, "kk": 5}],
        "home": [{"pcode": "68043", "name": "김민", "inn": "1 ⅓", "bf": 5, "ab": 4,
                  "hit": 1, "r": 0, "er": 0, "hr": 0, "bbhp": 0, "kk": 2}],
    },
    "battersBoxscore": {
        "away": [{"playerCode": "65653", "name": "김호령", "pos": "중", "batOrder": 1,
                  "ab": 4, "run": 1, "hit": 0, "hr": 0, "rbi": 0, "bb": 1, "sb": 0, "kk": 1}],
        "home": [{"playerCode": "55855", "name": "선발포수", "pos": "포", "batOrder": 1,
                  "ab": 3, "run": 2, "hit": 2, "hr": 1, "rbi": 3, "bb": 0, "sb": 0, "kk": 0}],
    },
}


def test_innings_to_outs():
    assert gr.innings_to_outs("6") == 18
    assert gr.innings_to_outs("6 ⅓") == 19
    assert gr.innings_to_outs("6 ⅔") == 20
    assert gr.innings_to_outs("⅔") == 2
    assert gr.innings_to_outs("") == 0
    assert gr.innings_to_outs(None) == 0
    assert gr.innings_to_outs("0") == 0


def test_parse_record_game_meta():
    g = gr.parse_record("20260328HTSK02026", RECORD)
    assert g.game_date == "2026-03-28"
    assert g.game_type == "regular"
    assert (g.away_team_code, g.home_team_code) == ("HT", "SK")
    assert (g.away_score, g.home_score) == (6, 7)
    assert g.winner == "home"
    assert (g.away_hits, g.home_errors) == (12, 1)
    assert g.away_starter_pcode == "54640"
    assert g.inn_scores["home"] == [0, 0, 0, 0, 0, 0, 3, 0, 4]


def test_parse_record_pitching_decisions_and_outs():
    g = gr.parse_record("g", RECORD)
    by = {p.pcode: p for p in g.pitching}
    assert by["68043"].decision == "W"
    assert by["68043"].ip_outs == 4          # "1 ⅓"
    assert by["54640"].decision is None
    assert by["54640"].is_home is False
    assert by["54640"].seq == 0              # 선발
    assert by["68043"].strikeouts == 2


def test_parse_record_batting_and_players():
    g = gr.parse_record("g", RECORD)
    hr = {b.pcode: b for b in g.batting}["55855"]
    assert (hr.home_runs, hr.rbi, hr.is_home) == (1, 3, True)
    assert hr.position == "포"
    # players = 투수2 + 타자2 (중복 pcode 없음)
    assert {p.pcode for p in g.players} == {"54640", "68043", "65653", "55855"}


def test_preseason_flag():
    rec = {**RECORD, "gameInfo": {**RECORD["gameInfo"], "gameFlag": "1"}}
    assert gr.parse_record("g", rec).game_type == "preseason"


def test_list_finished_games_filters():
    js = {"result": {"games": [
        {"categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "ok"},
        {"categoryId": "kbo", "statusCode": "BEFORE", "cancel": True,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "cancelled"},
        {"categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "WE", "homeTeamCode": "EA", "gameId": "allstar"},
        {"categoryId": "kbaseballetc", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "etc"},
    ]}}
    ids = [g["gameId"] for g in gr.list_finished_games(js)]
    assert ids == ["ok"]
