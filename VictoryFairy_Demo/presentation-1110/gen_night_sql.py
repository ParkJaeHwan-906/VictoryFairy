"""11/10 하루만 추가하는 야간 데모 경기 5건(17:25 시작, 22:30~23:20 종료). python gen_night_sql.py

낮 경기(gen_games_sql.py)와 대진이 달라 naver_game_id 가 겹치지 않는다. livegame 은 종료 이벤트 demo_finish_<ngid>
의 시각까지 1~9회를 펼치므로 그대로 붙는다.
"""
from gen_games_sql import CODE, NAME, ngid

D = "2026-11-10"
START = "17:25"
GAMES = [(17, 16, 5, 3, "23:20"), (22, 19, 4, 6, "22:45"), (25, 23, 2, 1, "23:20"),
         (24, 20, 8, 9, "23:20"), (18, 21, 7, 2, "22:30")]
ids = [ngid(D, a, h) for a, h, *_ in GAMES]
q = ",".join(f"'{x}'" for x in ids)
out = [f"-- 운영 쓰기: {D} 야간 데모 경기 5건 + 시작 이벤트 1개 + 종료 이벤트 5개",
       f"-- 되돌리기: DELETE FROM games WHERE naver_game_id IN ({q});",
       "SET time_zone = '+09:00';",
       "INSERT INTO games (game_date, away_team_id, home_team_id, stadium_id, game_status_id, naver_game_id, created_at, updated_at)",
       "SELECT v.gd, v.away, v.home,",
       "       (SELECT g2.stadium_id FROM games g2 WHERE g2.home_team_id = v.home AND g2.naver_game_id LIKE '2026%'",
       "         ORDER BY g2.game_date DESC LIMIT 1),",
       "       (SELECT id FROM game_statuses WHERE name = 'SCHEDULED'), v.ngid, NOW(6), NOW(6)",
       "FROM ("]
for i, (a, h, *_rest) in enumerate(GAMES):
    pre = "          SELECT" if i == 0 else "UNION ALL SELECT"
    cols = (f"TIMESTAMP('{D} {START}:00') gd, {a} away, {h} home, '{ngid(D, a, h)}' ngid" if i == 0
            else f"TIMESTAMP('{D} {START}:00'), {a}, {h}, '{ngid(D, a, h)}'")
    out.append(f"    {pre} {cols}  -- {NAME[a]}@{NAME[h]}")
out += [") v", "WHERE NOT EXISTS (SELECT 1 FROM games g WHERE g.naver_game_id = v.ngid);", "",
        "DROP EVENT IF EXISTS demo_games_20261110_night_start;",
        # 지난 시각의 일회성 이벤트는 실행되지 않고 바로 지워진다(11/10 17:27 실제로 겪음) — 늦게 넣어도 돌게 GREATEST
        f"CREATE EVENT demo_games_20261110_night_start ON SCHEDULE AT GREATEST(TIMESTAMP('{D} {START}:00'), "
        "CURRENT_TIMESTAMP + INTERVAL 1 MINUTE) ON COMPLETION NOT PRESERVE DO",
        "  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),",
        "         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)",
        f"   WHERE naver_game_id IN ({q}) AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');"]
for a, h, sa, sh, end in GAMES:
    g = ngid(D, a, h)
    out += [f"-- {NAME[a]}@{NAME[h]} {sa}:{sh}, {end} 종료",
            f"DROP EVENT IF EXISTS demo_finish_{g};",
            f"CREATE EVENT demo_finish_{g} ON SCHEDULE AT '{D} {end}:00' ON COMPLETION NOT PRESERVE DO",
            "  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),",
            f"         away_score = {sa}, home_score = {sh}, last_inning = 9, current_inning = NULL, inning_half = NULL,",
            f"         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = '{g}';"]
out += ["SELECT g.id, g.naver_game_id, g.game_date, g.stadium_id, s.name status, g.removed FROM games g",
        f"  JOIN game_statuses s ON s.id = g.game_status_id WHERE g.naver_game_id IN ({q});",
        "SELECT EVENT_NAME, EXECUTE_AT FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()",
        "   AND (EVENT_NAME = 'demo_games_20261110_night_start' OR EVENT_NAME IN ("
        + ",".join(f"'demo_finish_{x}'" for x in ids) + ")) ORDER BY EXECUTE_AT;"]
open("games-1110-night.sql", "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(ids))
