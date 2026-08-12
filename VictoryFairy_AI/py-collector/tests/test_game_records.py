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


def test_build_lineups_marks_starters_and_pitchers():
    g = gr.parse_record("g", RECORD)
    rows = {r.pcode: r for r in gr.build_lineups(g)}
    # 타자: 각 (팀, 타순) 첫 행이 선발
    assert rows["65653"].is_starter and rows["65653"].bat_order == 1
    assert rows["55855"].is_starter and rows["55855"].position == "포"
    # 투수: gameInfo 선발 pcode 와 일치해야 선발, 아니면 구원
    assert rows["54640"].is_starter and rows["54640"].position == "투"
    assert rows["54640"].bat_order is None
    assert not rows["68043"].is_starter
    assert rows["68043"].decision == "W"


def test_build_lineups_substitute_same_order_not_starter():
    rec = {**RECORD, "battersBoxscore": {
        "away": [
            {"playerCode": "65653", "name": "김호령", "pos": "중", "batOrder": 1},
            {"playerCode": "70001", "name": "대타왕", "pos": "타", "batOrder": 1},
        ],
        "home": [],
    }}
    g = gr.parse_record("g", rec)
    rows = {r.pcode: r for r in gr.build_lineups(g)}
    assert rows["65653"].is_starter          # 타순 1 첫 등장
    assert not rows["70001"].is_starter      # 같은 타순 두 번째 = 교체
    assert rows["70001"].position == "타"


def test_build_lineups_merges_batting_and_pitching_same_player():
    # 홈 선발투수(55855)가 타자 명단에도 있으면 한 행으로 병합된다
    g = gr.parse_record("g", RECORD)
    rec_pit = {**RECORD, "pitchersBoxscore": {
        "away": RECORD["pitchersBoxscore"]["away"],
        "home": [{"pcode": "55855", "name": "선발포수", "inn": "1"}],
    }}
    g = gr.parse_record("g", {**rec_pit,
                              "gameInfo": {**RECORD["gameInfo"], "hPCode": "55855"}})
    rows = [r for r in gr.build_lineups(g) if r.pcode == "55855"]
    assert len(rows) == 1
    assert rows[0].bat_order == 1 and rows[0].position == "포"  # 타자 정보 유지
    assert rows[0].is_starter


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


# --------------------------------------------------------------------------- games_sync (Task 8)
def test_list_kbo_games_includes_cancelled_scheduled_and_live():
    # list_finished_games 와 달리 취소/예정/진행 경기도 포함해야 한다(games_sync용).
    js = {"result": {"games": [
        {"categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "finished"},
        {"categoryId": "kbo", "statusCode": "BEFORE", "cancel": True,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "cancelled"},
        {"categoryId": "kbo", "statusCode": "BEFORE", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "scheduled"},
        {"categoryId": "kbo", "statusCode": "LIVE", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "live"},
        {"categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "WE", "homeTeamCode": "EA", "gameId": "allstar"},
        {"categoryId": "kbaseballetc", "statusCode": "RESULT", "cancel": False,
         "awayTeamCode": "HT", "homeTeamCode": "SK", "gameId": "etc"},
    ]}}
    ids = [g["gameId"] for g in gr.list_kbo_games(js)]
    assert ids == ["finished", "cancelled", "scheduled", "live"]


def test_map_status_cancelled_game_wins_over_winner_field():
    # 2026-07-08 NCHH 실측: 취소 경기는 BEFORE + cancel:true + winner:"DRAW"로 온다.
    g = {"statusCode": "BEFORE", "cancel": True, "winner": "DRAW",
         "homeTeamScore": 0, "awayTeamScore": 0}
    assert gr.map_status(g) == "CANCELED"


def test_map_status_result_draw_by_scores():
    g = {"statusCode": "RESULT", "cancel": False,
         "homeTeamScore": 5, "awayTeamScore": 5}
    assert gr.map_status(g) == "DRAW"


def test_map_status_unknown_returns_none():
    # 표본은 실재하지 않는 코드여야 한다. 예전엔 "READY" 를 썼는데 그건 **실재하는
    # 경기 당일 미시작 상태**라, 이 테스트가 초록인 채로 당일 경기가 통째로 스킵되는
    # 갭을 덮고 있었다(2026-08-12 실측으로 발견).
    assert gr.map_status({"statusCode": "NOT_A_REAL_STATUS", "cancel": False}) is None


def test_map_status_before_is_scheduled():
    g = {"statusCode": "BEFORE", "cancel": False}
    assert gr.map_status(g) == "SCHEDULED"


def test_map_status_ready_is_scheduled():
    # 당일 경기는 첫 투구 전까지 "READY"(statusNum=1, statusInfo="경기전") 로 온다
    # — 2026-08-12 18:25 KST, 19:00 시작 5경기 전수 실측. 먼 날짜의 "BEFORE" 와
    # 코드만 다르고 둘 다 미시작이다.
    #
    # 이걸 놓치면 하필 선발 공시가 뜨는 구간의 경기가 통째로 스킵돼, 상태 동기화도
    # preview 라인업 적재도 일어나지 않는다.
    assert gr.map_status({"statusCode": "READY", "cancel": False}) == "SCHEDULED"


def test_map_status_cancel_flag_wins_over_ready():
    assert gr.map_status({"statusCode": "READY", "cancel": True}) == "CANCELED"


def test_map_status_started_or_live_is_in_progress():
    # 실측(2026-08-04 실황 3경기): 진행 중은 "STARTED". "LIVE" 는 호환 유지.
    assert gr.map_status({"statusCode": "STARTED", "cancel": False}) == "IN_PROGRESS"
    assert gr.map_status({"statusCode": "LIVE", "cancel": False}) == "IN_PROGRESS"


def test_map_status_result_non_draw_is_finished():
    g = {"statusCode": "RESULT", "cancel": False,
         "homeTeamScore": 7, "awayTeamScore": 3}
    assert gr.map_status(g) == "FINISHED"


# --------------------------------------------------------------------------- 이닝
def test_parse_inning_reads_live_status_info():
    # 진행 중 statusInfo 실측 포맷(2026-08-12 19:00 시작 5경기). 접미사가 붙지 않는다.
    assert gr.parse_inning("1회초") == (1, 0)
    assert gr.parse_inning("3회말") == (3, 1)
    # 연장. 2026 정규시즌 전수 스캔 상한이 11회였다(11회 18경기).
    assert gr.parse_inning("11회말") == (11, 1)


def test_parse_inning_half_matches_domain_ordinal():
    # domain InningHalf 는 ORDINAL 저장이다 — TOP=0(초)/BOTTOM=1(말). 이 값이
    # 뒤집히면 초/말이 통째로 반대로 적재되는데 타입은 멀쩡해서 안 걸린다.
    assert gr.parse_inning("5회초")[1] == 0
    assert gr.parse_inning("5회말")[1] == 1


def test_parse_inning_non_inning_text_is_none():
    for text in ("경기전", "경기취소", "", None, "9회", "회말", "우천중단", "9회초 2아웃"):
        assert gr.parse_inning(text) == (None, None), text


def test_parse_inning_beyond_check_constraint_is_none():
    # games 에 CHECK ck_games_current_inning(1~11) 이 걸려 있다(prod 실측). 12 를
    # 그대로 넘기면 INSERT 가 거부돼 그 경기가 아니라 잡이 죽는다 — 값을 버린다.
    assert gr.parse_inning("12회초") == (None, None)
    assert gr.parse_inning("15회말") == (None, None)
    assert gr.parse_inning("0회초") == (None, None)


# --------------------------------------------------------------------------- preview 라인업
# 2026-08-11 HHOB(한화:두산) 실측 응답에서 라인업 부분만 추린 것. 이 배열 순서가
# record 박스스코어의 batOrder 와 전수 일치함을 확인하고 파서의 근거로 삼았다.
PREVIEW = {"result": {"previewData": {
    "gameInfo": {"aCode": "HH", "hCode": "OB", "aPCode": "56719", "hPCode": "68220"},
    "awayTeamLineUp": {"fullLineUp": [
        {"positionName": "선발투수", "playerName": "왕옌청", "playerCode": "56719", "position": "1"},
        {"positionName": "중견수", "playerName": "이원석", "playerCode": "68700", "position": "8"},
        {"positionName": "우익수", "playerName": "페라자", "playerCode": "54730", "position": "9"},
        {"positionName": "좌익수", "playerName": "문현빈", "playerCode": "53764", "position": "7"},
        {"positionName": "지명타자", "playerName": "강백호", "playerCode": "68050", "position": "0"},
        {"positionName": "3루수", "playerName": "노시환", "playerCode": "69737", "position": "5"},
        {"positionName": "1루수", "playerName": "채은성", "playerCode": "79192", "position": "3"},
        {"positionName": "포수", "playerName": "허인서", "playerCode": "52764", "position": "2"},
        {"positionName": "2루수", "playerName": "이도윤", "playerCode": "65703", "position": "4"},
        {"positionName": "유격수", "playerName": "심우준", "playerCode": "64006", "position": "6"},
    ]},
    "homeTeamLineUp": {"fullLineUp": [
        {"positionName": "선발투수", "playerName": "곽빈", "playerCode": "68220", "position": "1"},
        {"positionName": "우익수", "playerName": "김대한", "playerCode": "69238", "position": "9"},
        {"positionName": "1루수", "playerName": "박지훈", "playerCode": "50204", "position": "3"},
        {"positionName": "2루수", "playerName": "박준순", "playerCode": "55252", "position": "4"},
        {"positionName": "지명타자", "playerName": "양의지", "playerCode": "76232", "position": "0"},
        {"positionName": "좌익수", "playerName": "김민석", "playerCode": "53554", "position": "7"},
        {"positionName": "3루수", "playerName": "안재석", "playerCode": "51203", "position": "5"},
        {"positionName": "유격수", "playerName": "박찬호", "playerCode": "64646", "position": "6"},
        {"positionName": "포수", "playerName": "윤준호", "playerCode": "53296", "position": "2"},
        {"positionName": "중견수", "playerName": "조수행", "playerCode": "66209", "position": "8"},
    ]},
}}}


def test_parse_preview_lineups_maps_array_order_to_bat_order():
    rows, refs = gr.parse_preview_lineups(PREVIEW)

    assert len(rows) == 20 and len(refs) == 20
    away = {r.bat_order: r.pcode for r in rows if not r.is_home}
    # record 박스스코어 batOrder 와 대조한 실측 타순
    assert away == {None: "56719", 1: "68700", 2: "54730", 3: "53764", 4: "68050",
                    5: "69737", 6: "79192", 7: "52764", 8: "65703", 9: "64006"}
    home = {r.bat_order: r.pcode for r in rows if r.is_home}
    assert home == {None: "68220", 1: "69238", 2: "50204", 3: "55252", 4: "76232",
                    5: "53554", 6: "51203", 7: "64646", 8: "53296", 9: "66209"}
    assert {r.team_code for r in rows} == {"HH", "OB"}


def test_parse_preview_lineups_converts_position_codes_to_record_notation():
    # positions 코드테이블이 preview·record 두 원천에서 갈라지지 않도록 한자 표기로 되돌린다.
    rows, _ = gr.parse_preview_lineups(PREVIEW)
    by_pcode = {r.pcode: r.position for r in rows}
    assert by_pcode["56719"] == "투" and by_pcode["68700"] == "중"
    assert by_pcode["68050"] == "지" and by_pcode["79192"] == "一"
    assert by_pcode["52764"] == "포" and by_pcode["64006"] == "유"


def test_parse_preview_lineups_marks_every_row_starter_without_decision():
    # 경기 전이라 승패·세이브는 정해지지 않았다 — decision 은 records 잡 몫.
    rows, _ = gr.parse_preview_lineups(PREVIEW)
    assert all(r.is_starter for r in rows)
    assert all(r.decision is None for r in rows)


def test_parse_preview_lineups_drops_team_with_only_starting_pitcher():
    # 공시 전 preview 는 선발투수 1행만 온다(2026-08-12 14:23 실측). 그대로 적재하면
    # 타순 없는 반쪽 라인업이 API 로 나가므로 그 팀은 통째로 버린다.
    import copy
    payload = copy.deepcopy(PREVIEW)
    payload["result"]["previewData"]["awayTeamLineUp"]["fullLineUp"] = [
        {"positionName": "선발투수", "playerName": "왕옌청", "playerCode": "56719", "position": "1"}]

    rows, _ = gr.parse_preview_lineups(payload)

    assert all(r.is_home for r in rows)  # 원정은 버리고 홈만 남는다
    assert len(rows) == 10


def test_parse_preview_lineups_tolerates_missing_preview_data():
    assert gr.parse_preview_lineups({}) == ([], [])
    assert gr.parse_preview_lineups({"result": {}}) == ([], [])
    assert gr.parse_preview_lineups({"result": {"previewData": {}}}) == ([], [])


def test_parse_preview_lineups_prefers_batorder_field_over_array_order():
    # 원천이 batorder 를 명시하므로 그게 1순위다 — 배열이 뒤섞여 와도 타순은 옳아야 한다.
    import copy
    payload = copy.deepcopy(PREVIEW)
    full = payload["result"]["previewData"]["homeTeamLineUp"]["fullLineUp"]
    for i, entry in enumerate(full):
        entry["batorder"] = None if i == 0 else i
    payload["result"]["previewData"]["homeTeamLineUp"]["fullLineUp"] = (
        [full[0]] + list(reversed(full[1:])))

    rows, _ = gr.parse_preview_lineups(payload)

    home = {r.bat_order: r.pcode for r in rows if r.is_home}
    assert home[1] == "69238" and home[9] == "66209"   # 뒤집기 전 타순 그대로
    assert home[None] == "68220"                        # 선발투수는 타순 없음


def test_parse_preview_lineups_falls_back_to_order_without_batorder_field():
    # batorder 가 통째로 없는 응답(과거 판본 등)에서도 배열 순서로 동작해야 한다.
    rows, _ = gr.parse_preview_lineups(PREVIEW)  # PREVIEW 픽스처엔 batorder 가 없다
    away = {r.bat_order: r.pcode for r in rows if not r.is_home}
    assert away[1] == "68700" and away[9] == "64006" and away[None] == "56719"


def test_parse_preview_lineups_keeps_pitcher_who_also_bats():
    # 지명타자를 안 쓰면 선발투수에게도 타순이 붙는다. position 으로 투수를 거르면
    # 타자가 8명이 돼 팀 전체가 드롭되므로, 판정 근거는 batorder 유무여야 한다.
    import copy
    payload = copy.deepcopy(PREVIEW)
    full = payload["result"]["previewData"]["awayTeamLineUp"]["fullLineUp"]
    full[0]["batorder"] = 9          # 선발투수가 9번 타자
    for i, entry in enumerate(full[1:], start=1):
        entry["batorder"] = i
    full[-1]["batorder"] = None      # 원래 9번이던 야수는 라인업에서 빠졌다 치고
    full[-1]["position"] = "0"

    rows, _ = gr.parse_preview_lineups(payload)
    away = {r.bat_order: r.pcode for r in rows if not r.is_home}
    assert away[9] == "56719"        # 투수가 타순 9번으로 살아 있다
    assert len([r for r in rows if not r.is_home and r.bat_order]) == 9
