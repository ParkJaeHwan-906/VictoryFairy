-- 운영 쓰기: 2026-11-10 야간 데모 경기 5건 + 시작 이벤트 1개 + 종료 이벤트 5개
-- 되돌리기: DELETE FROM games WHERE naver_game_id IN ('DEMO1110LGOB02026','DEMO1110HHKT02026','DEMO1110SKNC02026','DEMO1110LTWO02026','DEMO1110SSHT02026');
SET time_zone = '+09:00';
INSERT INTO games (game_date, away_team_id, home_team_id, stadium_id, game_status_id, naver_game_id, created_at, updated_at)
SELECT v.gd, v.away, v.home,
       (SELECT g2.stadium_id FROM games g2 WHERE g2.home_team_id = v.home AND g2.naver_game_id LIKE '2026%'
         ORDER BY g2.game_date DESC LIMIT 1),
       (SELECT id FROM game_statuses WHERE name = 'SCHEDULED'), v.ngid, NOW(6), NOW(6)
FROM (
              SELECT TIMESTAMP('2026-11-10 17:25:00') gd, 17 away, 16 home, 'DEMO1110LGOB02026' ngid  -- LG@두산
    UNION ALL SELECT TIMESTAMP('2026-11-10 17:25:00'), 22, 19, 'DEMO1110HHKT02026'  -- 한화@KT
    UNION ALL SELECT TIMESTAMP('2026-11-10 17:25:00'), 25, 23, 'DEMO1110SKNC02026'  -- SSG@NC
    UNION ALL SELECT TIMESTAMP('2026-11-10 17:25:00'), 24, 20, 'DEMO1110LTWO02026'  -- 롯데@키움
    UNION ALL SELECT TIMESTAMP('2026-11-10 17:25:00'), 18, 21, 'DEMO1110SSHT02026'  -- 삼성@KIA
) v
WHERE NOT EXISTS (SELECT 1 FROM games g WHERE g.naver_game_id = v.ngid);

DROP EVENT IF EXISTS demo_games_20261110_night_start;
CREATE EVENT demo_games_20261110_night_start ON SCHEDULE AT GREATEST(TIMESTAMP('2026-11-10 17:25:00'), CURRENT_TIMESTAMP + INTERVAL 1 MINUTE) ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1110LGOB02026','DEMO1110HHKT02026','DEMO1110SKNC02026','DEMO1110LTWO02026','DEMO1110SSHT02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- LG@두산 5:3, 23:20 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110LGOB02026;
CREATE EVENT demo_finish_DEMO1110LGOB02026 ON SCHEDULE AT '2026-11-10 23:20:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 5, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110LGOB02026';
-- 한화@KT 4:6, 22:45 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110HHKT02026;
CREATE EVENT demo_finish_DEMO1110HHKT02026 ON SCHEDULE AT '2026-11-10 22:45:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 4, home_score = 6, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110HHKT02026';
-- SSG@NC 2:1, 23:20 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110SKNC02026;
CREATE EVENT demo_finish_DEMO1110SKNC02026 ON SCHEDULE AT '2026-11-10 23:20:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 2, home_score = 1, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110SKNC02026';
-- 롯데@키움 8:9, 23:20 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110LTWO02026;
CREATE EVENT demo_finish_DEMO1110LTWO02026 ON SCHEDULE AT '2026-11-10 23:20:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 8, home_score = 9, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110LTWO02026';
-- 삼성@KIA 7:2, 22:30 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110SSHT02026;
CREATE EVENT demo_finish_DEMO1110SSHT02026 ON SCHEDULE AT '2026-11-10 22:30:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 7, home_score = 2, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110SSHT02026';
SELECT g.id, g.naver_game_id, g.game_date, g.stadium_id, s.name status, g.removed FROM games g
  JOIN game_statuses s ON s.id = g.game_status_id WHERE g.naver_game_id IN ('DEMO1110LGOB02026','DEMO1110HHKT02026','DEMO1110SKNC02026','DEMO1110LTWO02026','DEMO1110SSHT02026');
SELECT EVENT_NAME, EXECUTE_AT FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()
   AND (EVENT_NAME = 'demo_games_20261110_night_start' OR EVENT_NAME IN ('demo_finish_DEMO1110LGOB02026','demo_finish_DEMO1110HHKT02026','demo_finish_DEMO1110SKNC02026','demo_finish_DEMO1110LTWO02026','demo_finish_DEMO1110SSHT02026')) ORDER BY EXECUTE_AT;
