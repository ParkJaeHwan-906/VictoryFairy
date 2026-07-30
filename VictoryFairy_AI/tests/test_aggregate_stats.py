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

def test_standings_trend_boundary_inclusion_exclusion():
    """개막일 2026-04-01 + 27일 = 2026-04-28(경계)만 early에 포함되고 다음날
    (2026-04-29)은 제외돼야 한다 — 그냥 날짜만 대조하는 게 아니라, 경계일 경기(g3)
    포함 여부가 AA/BB의 early 순위 그 자체를 바꾸도록 설계했다: g1·g2만으로는
    AA·BB가 1승1패로 동률(사전순 타이브레이크로 AA가 1위)이고, g3(경계일)가
    있어야만 BB가 2승1패로 앞서 1위가 된다. 즉 `<=`를 `<`로 바꾸는 뮤테이션이
    일어나면 g3가 빠지면서 trend["early"]["BB"]/["AA"]의 값 자체가 뒤집힌다
    (리뷰에서 발견된, 경계일 경기가 있어도 없어도 결과가 같았던 기존 픽스처의
    결함을 수정한 버전).
    CC는 경계 다음날(g4)에야 처음 등장하므로 early에서 완전히 빠져야 한다.
    """
    envs = [
        env("20260401AABB02026", "AA", "BB", 1, 0, "away"),  # 4/1(개막일) AA 승
        env("20260415BBAA02026", "BB", "AA", 1, 0, "away"),  # 4/15 BB 승 → AA·BB 1승1패 동률
        env("20260428BBAA02026", "BB", "AA", 1, 0, "away"),  # 4/28(경계, early 포함) BB 승 → BB가 앞서게 하는 결정구
        env("20260429CCAA02026", "CC", "AA", 1, 0, "away"),  # 4/29(경계 다음날, early 제외) CC의 유일한 초반 경기
    ]
    games = [agg.parse_game(e) for e in envs]
    trend = agg.standings_trend(games)

    assert trend["earlyAsOf"] == "2026-04-28"
    assert set(trend["early"]) == {"AA", "BB"}    # CC는 경계 다음날에야 등장 → early에 없음
    assert "CC" not in trend["delta"]
    # 경계일 경기(g3) 포함 시 BB 2승1패(.667) 1위, AA 1승2패(.333) 2위.
    # g3가 빠지면(뮤테이션 `<`) AA·BB 1승1패 동률 → 사전순 타이브레이크로 AA가 1위가 되어
    # 아래 두 값이 뒤바뀐다.
    assert trend["early"]["BB"] == 1
    assert trend["early"]["AA"] == 2


def test_standings_trend_delta_sign():
    """early(개막 초반) 대비 delta의 부호를 검증한다: BB는 초반 0승2패 열세에서
    시즌 중 역전해 최종 1위가 되므로 delta가 양수(상승), AA는 초반 1위에서 밀려나
    2위가 되므로 음수(하락)여야 한다. CC는 5월에야 처음 등장해 early 자체가 없으므로
    delta에서 제외된다. (경계일과는 무관하게 여유 있는 날짜로 구성 — 경계 정확성은
    test_standings_trend_boundary_inclusion_exclusion이 별도로 검증한다.)
    """
    envs = [
        env("20260401AABB02026", "AA", "BB", 1, 0, "away"),   # 4/1 AA 승
        env("20260402AABB02026", "AA", "BB", 1, 0, "away"),   # 4/2 AA 승 (early: AA 2승0패, BB 0승2패)
        env("20260501BBAA02026", "BB", "AA", 1, 0, "away"),   # 5/1 BB 승
        env("20260502BBCC02026", "BB", "CC", 1, 0, "away"),   # 5/2 BB 승
        env("20260503BBCC02026", "BB", "CC", 1, 0, "away"),   # 5/3 BB 승
        env("20260504CCAA02026", "CC", "AA", 1, 0, "away"),   # 5/4 CC 승
    ]
    games = [agg.parse_game(e) for e in envs]
    trend = agg.standings_trend(games)

    assert trend["early"] == {"AA": 1, "BB": 2}          # 4월 초 기준 AA 1위, BB 2위
    assert "CC" not in trend["early"] and "CC" not in trend["delta"]   # 5월에야 등장

    assert trend["now"]["BB"] == 1                        # 시즌 전체에서는 BB가 역전 1위
    assert trend["delta"]["BB"] > 0                        # 상승 = 양수
    assert trend["delta"]["AA"] < 0                        # 하락 = 음수


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
