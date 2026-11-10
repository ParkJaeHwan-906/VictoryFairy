"""2026-11-10 ~ 11-15 발표 데모 경기 SQL 생성기. python gen_games_sql.py  (games-1110-1115.sql 을 쓴다)

경기 행은 DEMO{MMDD}{원정}{홈}02026(DEMO% 만 수집기 '사라진 경기' 판정에서 빠진다, #668).
시작은 날짜별 이벤트 하나(12:00), 종료는 경기마다 이벤트 하나 — 일부 경기는 17:00 전에 끝난다.
livegame.py 가 종료 이벤트(demo_finish_<ngid>)의 시각과 최종 스코어를 읽어 그 사이에 1~9회를 펼친다.
"""
CODE = {16: "OB", 17: "LG", 18: "SS", 19: "KT", 20: "WO", 21: "HT", 22: "HH", 23: "NC", 24: "LT", 25: "SK"}
NAME = {16: "두산", 17: "LG", 18: "삼성", 19: "KT", 20: "키움", 21: "KIA", 22: "한화", 23: "NC", 24: "롯데", 25: "SSG"}
# (날짜, [(원정, 홈, 원정점수, 홈점수, 종료 HH:MM)]) — 동점 없음, 잠실(LG·두산) 홈 경기는 하루 하나
DAYS = [
    ("2026-11-10", [(16, 17, 4, 6, "17:00"), (19, 22, 9, 2, "16:15"), (23, 25, 3, 4, "17:00"),
                    (20, 24, 5, 7, "17:00"), (21, 18, 6, 5, "17:00")]),
    ("2026-11-11", [(17, 16, 2, 1, "17:00"), (22, 19, 3, 8, "16:20"), (25, 23, 7, 6, "17:00"),
                    (24, 20, 1, 4, "16:40"), (18, 21, 5, 3, "17:00")]),
    ("2026-11-12", [(18, 17, 3, 5, "17:00"), (16, 21, 10, 4, "16:10"), (24, 19, 2, 3, "17:00"),
                    (22, 23, 6, 4, "17:00"), (25, 20, 8, 7, "17:00")]),
    ("2026-11-13", [(17, 18, 1, 2, "17:00"), (21, 16, 4, 7, "17:00"), (19, 24, 6, 3, "16:30"),
                    (23, 22, 5, 9, "17:00"), (20, 25, 2, 0, "16:05")]),
    ("2026-11-14", [(19, 17, 7, 5, "17:00"), (16, 23, 3, 2, "17:00"), (18, 24, 11, 3, "16:15"),
                    (21, 22, 4, 6, "17:00"), (25, 20, 5, 8, "17:00")]),
    ("2026-11-15", [(17, 19, 6, 4, "17:00"), (23, 16, 2, 5, "16:35"), (24, 18, 4, 3, "17:00"),
                    (22, 21, 1, 7, "16:10"), (20, 25, 9, 8, "17:00")]),
]


def ngid(d, a, h):
    return f"DEMO{d[5:7]}{d[8:10]}{CODE[a]}{CODE[h]}02026"


NL = chr(10)

if __name__ == "__main__":   # gen_night_sql.py 가 CODE·NAME·ngid 만 가져다 쓴다 — import 로 파일을 다시 쓰지 않게
    ids = [ngid(d, a, h) for d, gs in DAYS for a, h, *_ in gs]
    assert len(ids) == len(set(ids))
    for d, gs in DAYS:
        homes = [h for _, h, *_ in gs]
        assert not (16 in homes and 17 in homes), d
        assert all(x != y for _, _, x, y, _ in gs), d

    q = lambda xs: ",".join(f"'{x}'" for x in xs)
    out = []
    w = out.append
    w("-- 운영 쓰기: 2026-11-10 ~ 11-15 발표 데모 경기 30건 + 시작 이벤트 6개 + 종료 이벤트 30개 (gen_games_sql.py 로 생성)")
    w("-- 되돌리기:")
    w(f"--   DELETE FROM games WHERE naver_game_id IN ({q(ids)});")
    w("--   이벤트: SELECT CONCAT('DROP EVENT ', EVENT_NAME, ';') FROM information_schema.EVENTS")
    w("--           WHERE EVENT_NAME LIKE 'demo_games_202611%' OR EVENT_NAME LIKE 'demo_finish_DEMO11%';")
    w("SET time_zone = '+09:00';")
    w("START TRANSACTION;")
    w("INSERT INTO games (game_date, away_team_id, home_team_id, stadium_id, game_status_id, naver_game_id, created_at, updated_at)")
    w("SELECT v.gd, v.away, v.home,")
    w("       (SELECT g2.stadium_id FROM games g2 WHERE g2.home_team_id = v.home AND g2.naver_game_id LIKE '2026%'")
    w("         ORDER BY g2.game_date DESC LIMIT 1),")
    w("       (SELECT id FROM game_statuses WHERE name = 'SCHEDULED'), v.ngid, NOW(6), NOW(6)")
    w("FROM (")
    first = True
    for d, gs in DAYS:
        for a, h, *_ in gs:
            pre = "          SELECT" if first else "UNION ALL SELECT"
            cols = (f"TIMESTAMP('{d} 12:00:00') gd, {a} away, {h} home, '{ngid(d, a, h)}' ngid" if first
                    else f"TIMESTAMP('{d} 12:00:00'), {a}, {h}, '{ngid(d, a, h)}'")
            w(f"    {pre} {cols}  -- {NAME[a]}@{NAME[h]}")
            first = False
    w(") v")
    w("WHERE NOT EXISTS (SELECT 1 FROM games g WHERE g.naver_game_id = v.ngid);")
    w("COMMIT;")
    w("")
    for i, (d, gs) in enumerate(DAYS):
        dn = d.replace("-", "")
        day_ids = [ngid(d, a, h) for a, h, *_ in gs]
        # 첫날은 이 SQL 을 넣는 시점에 이미 정오가 지났다 — 과거 시각 이벤트는 실행되지 않고 바로 지워지므로 1분 뒤로
        at = "CURRENT_TIMESTAMP + INTERVAL 1 MINUTE" if i == 0 else f"'{d} 12:00:00'"
        w(f"-- ---------------------------------------------------------------- {d}")
        w(f"DROP EVENT IF EXISTS demo_games_{dn}_start;")
        w(f"CREATE EVENT demo_games_{dn}_start ON SCHEDULE AT {at} ON COMPLETION NOT PRESERVE DO")
        w("  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),")
        w("         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)")
        w(f"   WHERE naver_game_id IN ({q(day_ids)}) AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');")
        for a, h, sa, sh, end in gs:
            g = ngid(d, a, h)
            w(f"-- {NAME[a]}@{NAME[h]} {sa}:{sh}, {end} 종료")
            w(f"DROP EVENT IF EXISTS demo_finish_{g};")
            w(f"CREATE EVENT demo_finish_{g} ON SCHEDULE AT '{d} {end}:00' ON COMPLETION NOT PRESERVE DO")
            w("  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),")
            w(f"         away_score = {sa}, home_score = {sh}, last_inning = 9, current_inning = NULL, inning_half = NULL,")
            w(f"         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = '{g}';")
        w("")
    w("-- 확인: stadium_id NULL 이 있으면 그 홈 구단의 2026 정규 실경기가 없다는 뜻 — 직접 채울 것")
    w("SELECT g.id, g.naver_game_id, g.game_date, g.away_team_id, g.home_team_id, g.stadium_id, s.name status, g.removed")
    w(f"FROM games g JOIN game_statuses s ON s.id = g.game_status_id WHERE g.naver_game_id IN ({q(ids)}) ORDER BY g.game_date, g.id;")
    w("SELECT COUNT(*) events, MIN(EXECUTE_AT), MAX(EXECUTE_AT) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()")
    w("   AND (EVENT_NAME LIKE 'demo_games_202611%' OR EVENT_NAME LIKE 'demo_finish_DEMO11%');")
    w("SELECT @@event_scheduler, @@time_zone;")
    open("games-1110-1115.sql", "w", encoding="utf-8", newline=NL).write("\n".join(out) + "\n")
