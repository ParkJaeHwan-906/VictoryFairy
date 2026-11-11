"""KBO 게임센터 박스스코어·라인업 파싱.

픽스처는 실제 응답 그대로다 — 2026-11-01 PO5 KIA@삼성(종료, 1:6), 2026-11-03 KS1 삼성@KT 라인업
(공시 전·후 두 번 받음). 파서 값은 같은 시즌 60경기를 네이버 relay 최종 기록과 대조해 타자 1,589명·
투수 590명 중 공식 기록 정정으로 보이는 13건만 달랐다(이 모듈 작성 시 확인).
"""
import json
from pathlib import Path

from kbo_collector import game_records, kbo_boxscore

FIX = Path(__file__).parent / "fixtures" / "kbo"


def _load(name):
    return json.loads((FIX / name).read_text(encoding="utf-8"))


KG = _load("gamecenter_20261101HTSS0_game.json")
BOX = _load("gamecenter_20261101HTSS0_boxscore.json")
BOARD = _load("gamecenter_20261101HTSS0_scoreboard.json")


def _all_codes():
    """박스스코어에 나온 모든 (구단, 이름)에 가짜 코드를 붙인 표."""
    codes = {}
    for idx, team in ((0, "HT"), (1, "SS")):
        for r in kbo_boxscore._table(BOX["arrHitter"][idx]["table1"]):
            codes[(team, r[2])] = f"{team}-{r[2]}"
        for r in kbo_boxscore._table(BOX["arrPitcher"][idx]["table"]):
            codes[(team, r[0])] = f"{team}-{r[0]}"
    return codes


def test_boxscore_parses_batters_pitchers_and_team_totals():
    game, unresolved = kbo_boxscore.parse_boxscore(KG, BOX, BOARD, "55551101HTSS02026", _all_codes())

    assert unresolved == []
    assert (game.game_id, game.game_date, game.stadium) == ("55551101HTSS02026", "2026-11-01", "대구")
    assert (game.away_team_code, game.home_team_code) == ("HT", "SS")
    assert (game.away_score, game.home_score, game.winner) == (1, 6, "home")
    # 스코어보드 R·H·E·B (원정 1 5 0 3 / 홈 6 11 2 1)
    assert (game.away_hits, game.away_errors, game.away_bb) == (5, 0, 3)
    assert (game.home_hits, game.home_errors, game.home_bb) == (11, 2, 1)

    by_name = {r.pcode: r for r in game.batting}
    kim = by_name["SS-김지찬"]  # 4타수 3안타 3타점 3득점, 비고 '도루 김지찬(2회)'
    assert (kim.bat_order, kim.position, kim.is_home) == (1, "중", True)
    assert (kim.at_bats, kim.hits, kim.rbi, kim.runs, kim.stolen_bases) == (4, 3, 3, 3, 1)
    na = by_name["HT-나성범"]  # 이닝 칸: 4구 / 1땅 / 우안 / 삼진
    assert (na.walks, na.strikeouts, na.home_runs) == (1, 1, 0)
    # 교체 선수는 같은 타순으로 이어 온다 — 대타는 '타' 로 시작한다.
    sub = by_name["HT-이호연"]
    assert (sub.bat_order, sub.position) == (9, "타一")

    pit = {r.pcode: r for r in game.pitching}
    assert (pit["HT-올러"].decision, pit["HT-올러"].ip_outs, pit["HT-올러"].seq) == ("L", 6, 0)
    assert pit["HT-양현종"].ip_display == "3 2/3" and pit["HT-양현종"].ip_outs == 11
    assert pit["SS-후라도"].decision == "W"
    assert (game.away_starter_pcode, game.home_starter_pcode) == ("HT-올러", "SS-후라도")
    # 기존 records 경로의 라인업 조립을 그대로 탄다.
    lineups = game_records.build_lineups(game)
    assert sum(r.is_starter for r in lineups if r.is_home) == 10  # 타자 9 + 선발투수


def test_boxscore_reports_players_it_cannot_resolve():
    codes = _all_codes()
    del codes[("SS", "김지찬")]
    del codes[("HT", "올러")]

    game, unresolved = kbo_boxscore.parse_boxscore(KG, BOX, BOARD, "x", codes)

    assert sorted(unresolved) == ["HT:올러", "SS:김지찬"]
    assert "SS-김지찬" not in {r.pcode for r in game.batting}
    assert game.away_starter_pcode is None


def test_plate_results_split_two_plates_in_one_inning():
    # 실측 두 모양: '<br />' 로 이어 오거나, 둘째 타석 앞에 '/ ' 가 붙는다.
    assert kbo_boxscore._plate_results(["1땅<br />4구", "&nbsp;", "/ 삼진", "좌홈"]) == [
        "1땅", "4구", "삼진", "좌홈"]


def test_steals_count_repeated_and_multiple_players():
    rows = [["도루", "김지찬(2회) 박해민2(3회 7회)"], ["실책", "디아즈(3회)"]]
    assert kbo_boxscore._steals(rows) == {"김지찬": 1, "박해민": 2}


def _today_kg():
    return {"G_ID": "20261103SSKT0", "SR_ID": 7, "AWAY_ID": "SS", "HOME_ID": "KT",
            "T_PIT_P_ID": 65320, "T_PIT_P_NM": "최원태 ", "B_PIT_P_ID": 56002, "B_PIT_P_NM": "대니엘 "}


def _lineup_codes(lineup):
    codes = {}
    for meta, table in ((lineup[1], lineup[3]), (lineup[2], lineup[4])):
        for r in kbo_boxscore._table(table[0]):
            codes[(meta[0]["T_ID"], r[2])] = f"{meta[0]['T_ID']}-{r[2]}"
    return codes


def test_announced_lineup_gives_nine_batters_plus_starting_pitcher_per_team():
    lineup = _load("gamecenter_20261103SSKT0_lineup.json")

    rows, refs, unresolved = kbo_boxscore.parse_lineup(_today_kg(), lineup, _lineup_codes(lineup))

    assert unresolved == []
    away = [r for r in rows if not r.is_home]
    home = [r for r in rows if r.is_home]
    assert len(away) == len(home) == 10
    assert [r.bat_order for r in away[:9]] == list(range(1, 10))
    first = away[0]
    assert (first.pcode, first.position, first.team_code) == ("SS-김지찬", "중", "SS")
    pitcher = home[-1]
    assert (pitcher.pcode, pitcher.bat_order, pitcher.position) == ("56002", None, "투")
    assert any(r.pcode == "65320" and r.name == "최원태" for r in refs)  # 이름 끝 공백 제거
    assert all(r.is_starter for r in rows)


def test_unannounced_lineup_is_ignored_even_though_tables_are_filled():
    # 공시 전에도 표에는 각 팀의 직전 경기 라인업이 들어 있다 — 그걸 오늘 것으로 쓰면 안 된다.
    lineup = _load("gamecenter_20261103SSKT0_lineup_unannounced.json")

    assert kbo_boxscore.parse_lineup(_today_kg(), lineup, _lineup_codes(lineup)) == ([], [], [])


def test_lineup_team_with_unresolved_batter_is_held_back():
    lineup = _load("gamecenter_20261103SSKT0_lineup.json")
    codes = _lineup_codes(lineup)
    del codes[("KT", "안현민")]

    rows, _, unresolved = kbo_boxscore.parse_lineup(_today_kg(), lineup, codes)

    assert unresolved == ["KT:안현민"]
    assert {r.team_code for r in rows} == {"SS"}  # 반쪽 라인업 대신 그 팀만 다음 폴링으로 미룬다
