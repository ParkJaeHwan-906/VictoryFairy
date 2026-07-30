import aggregate_stats as agg


def env(gid, away, home, a, h, winner):
    return {"docType": "game_result", "docId": f"game_result:{gid}",
            "entities": {"teamCodes": [away, home], "gameId": gid},
            "payload": {"gameId": gid, "awayScore": a, "homeScore": h, "winner": winner}}


# LG-OB 3연전 + LG-HT 1경기 (gameId 앞 8자리 = 날짜)
ENVS = [
    env("20260501LGOB02026", "LG", "OB", 3, 5, "home"),
    env("20260502LGOB02026", "LG", "OB", 7, 2, "away"),
    env("20260503LGOB02026", "LG", "OB", 4, 4, "draw"),
    env("20260601HTLG02026", "HT", "LG", 1, 2, "home"),
]
GAMES = [agg.parse_game(e) for e in ENVS]


def test_parse_game():
    g = GAMES[0]
    assert (g.date, g.away, g.home, g.winner) == ("2026-05-01", "LG", "OB", "home")
    assert agg.parse_game({"payload": {}}) is None


def test_head_to_head():
    h2h = agg.head_to_head(GAMES)
    lg_ob = h2h["LG|OB"]
    assert lg_ob["wins"] == {"LG": 1, "OB": 1} and lg_ob["draws"] == 1
    assert lg_ob["last"]["date"] == "2026-05-03"


def test_standings_rank_and_pct():
    rows = {r["team"]: r for r in agg.standings(GAMES)}
    assert rows["LG"]["wins"] == 2 and rows["LG"]["losses"] == 1 and rows["LG"]["draws"] == 1
    assert rows["LG"]["winPct"] == 0.667
    assert rows["HT"]["losses"] == 1 and rows["HT"]["rank"] >= rows["LG"]["rank"]


def test_streaks():
    s = agg.streaks(GAMES)
    assert s["LG"] == {"kind": "W", "length": 1}   # 무승부(5/3)로 끊긴 뒤 6/1 승


def test_home_away_and_monthly():
    ha = agg.home_away(GAMES)
    assert ha["OB"]["home"]["wins"] == 1
    m = agg.monthly(GAMES)
    assert m["LG"]["2026-05"]["wins"] == 1 and m["LG"]["2026-06"]["wins"] == 1


def test_recent_scoring_window():
    rs = agg.recent_scoring(GAMES, end_date="2026-05-03", days=7)
    assert rs["LG"]["games"] == 3 and rs["LG"]["runsFor"] == 14


def test_yoy_none_without_prev():
    assert agg.yoy(GAMES, [], as_of="2026-06-02") is None
