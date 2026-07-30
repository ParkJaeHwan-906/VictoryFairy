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


# ── 이연 항목(Task 4 리뷰) — Task 5에서 추가 ──────────────────────

def test_standings_trend_boundary_and_delta_sign():
    """개막일 2026-04-01 + 27일 = 2026-04-28 경계.
    AA/BB는 경계 이내 경기가 있어 early에 포함되고, CC는 5월에야 등장해 제외된다.
    전체 시즌에서 BB가 역전 1위가 되므로 delta는 상승(BB, 양수)/하락(AA, 음수)을 함께 검증한다.
    """
    envs = [
        env("20260401AABB02026", "AA", "BB", 1, 0, "away"),  # 4/1 AA 승
        env("20260428AABB02026", "AA", "BB", 1, 0, "away"),  # 4/28(경계, early 포함) AA 승
        env("20260429BBAA02026", "BB", "AA", 2, 1, "away"),  # 4/29(경계 다음날, early 제외) BB 승
        env("20260501BBCC02026", "BB", "CC", 3, 0, "away"),  # BB 승
        env("20260502BBCC02026", "BB", "CC", 3, 0, "away"),  # BB 승
        env("20260503BBAA02026", "BB", "AA", 2, 0, "away"),  # BB 승
        env("20260504CCAA02026", "CC", "AA", 2, 1, "away"),  # CC 승
    ]
    games = [agg.parse_game(e) for e in envs]
    trend = agg.standings_trend(games)

    assert trend["earlyAsOf"] == "2026-04-28"
    assert set(trend["early"]) == {"AA", "BB"}          # CC는 early에 없음(경계 밖에서야 등장)
    assert trend["early"]["AA"] == 1 and trend["early"]["BB"] == 2

    assert trend["now"]["BB"] == 1 and trend["now"]["AA"] == 2 and trend["now"]["CC"] == 3
    assert "CC" not in trend["delta"]                    # early 데이터 없는 팀은 delta에서 제외
    assert trend["delta"]["BB"] > 0                       # 2위→1위: 상승(양수)
    assert trend["delta"]["AA"] < 0                       # 1위→2위: 하락(음수)


def test_yoy_excludes_teams_from_only_one_season():
    """prev_games가 비어있지 않은 정상 경로. XX는 양쪽 시즌에 모두 있어 비교 대상이지만
    YY(현재 시즌에만 존재)·ZZ(전년에만 존재)는 비교 불가라 결과에서 제외돼야 한다.
    """
    cur_envs = [
        env("20260410XXYY02026", "XX", "YY", 3, 1, "away"),   # XX 승
        env("20260411YYXX02026", "YY", "XX", 1, 2, "home"),   # XX 승
    ]
    prev_envs = [
        env("20250410XXZZ02025", "XX", "ZZ", 1, 3, "home"),   # ZZ 승
        env("20250415ZZXX02025", "ZZ", "XX", 2, 0, "away"),   # ZZ 승
    ]
    cur_games = [agg.parse_game(e) for e in cur_envs]
    prev_games = [agg.parse_game(e) for e in prev_envs]

    result = agg.yoy(cur_games, prev_games, as_of="2026-05-01")

    assert set(result) == {"XX"}                # YY·ZZ는 한쪽 시즌에만 있어 제외
    assert result["XX"] == {"prev": 0.0, "cur": 1.0, "delta": 1.0}


# ── Task 5: 스냅샷 추출·렌더·CLI ──────────────────────────────────

def test_extract_kbo_official():
    snap = {"page": "team-rank-daily", "date": "2026-07-30",
            "tables": [{"headers": ["순위", "팀", "승"], "rows": [["1", "LG", "60"]]}]}
    out = agg.extract_kbo_official({"team-rank-daily": snap})
    assert out["teamRankDaily"]["asOf"] == "2026-07-30"
    assert out["teamRankDaily"]["tables"][0]["rows"][0][1] == "LG"
    assert out["seasonLeaders"] is None   # hitter/pitcher/top5 스냅샷 없음


def test_build_season_stats_shape():
    stats = agg.build_season_stats(GAMES, today="2026-06-02")
    assert set(stats) == {"generatedAt", "asOf", "headToHead", "standings", "streaks",
                          "homeAway", "monthly", "standingsTrend", "recentScoring", "yoy"}
    assert stats["asOf"] == "2026-06-02"


def test_render_season_md_mentions_asof():
    md = agg.render_season_md(agg.build_season_stats(GAMES, today="2026-06-02"))
    assert "기준일 2026-06-02" in md and "상대전적" in md and "순위" in md


def test_cli_writes_four_files(tmp_path):
    import json
    envd = tmp_path / "env"; envd.mkdir()
    for i, e in enumerate(ENVS):
        (envd / f"{i}.json").write_text(json.dumps(e), encoding="utf-8")
    outd = tmp_path / "out"
    agg.main(["--envelopes-dir", str(envd), "--kbo-dir", str(tmp_path / "none"),
              "--out-dir", str(outd), "--date", "2026-06-02"])
    assert {p.name for p in outd.iterdir()} == \
        {"season.json", "season.md", "kbo-official.json", "kbo-official.md"}
