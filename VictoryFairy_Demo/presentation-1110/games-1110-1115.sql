-- 운영 쓰기: 2026-11-10 ~ 11-15 발표 데모 경기 30건 + 시작 이벤트 6개 + 종료 이벤트 30개 (gen_games_sql.py 로 생성)
-- 되돌리기:
--   DELETE FROM games WHERE naver_game_id IN ('DEMO1110OBLG02026','DEMO1110KTHH02026','DEMO1110NCSK02026','DEMO1110WOLT02026','DEMO1110HTSS02026','DEMO1111LGOB02026','DEMO1111HHKT02026','DEMO1111SKNC02026','DEMO1111LTWO02026','DEMO1111SSHT02026','DEMO1112SSLG02026','DEMO1112OBHT02026','DEMO1112LTKT02026','DEMO1112HHNC02026','DEMO1112SKWO02026','DEMO1113LGSS02026','DEMO1113HTOB02026','DEMO1113KTLT02026','DEMO1113NCHH02026','DEMO1113WOSK02026','DEMO1114KTLG02026','DEMO1114OBNC02026','DEMO1114SSLT02026','DEMO1114HTHH02026','DEMO1114SKWO02026','DEMO1115LGKT02026','DEMO1115NCOB02026','DEMO1115LTSS02026','DEMO1115HHHT02026','DEMO1115WOSK02026');
--   이벤트: SELECT CONCAT('DROP EVENT ', EVENT_NAME, ';') FROM information_schema.EVENTS
--           WHERE EVENT_NAME LIKE 'demo_games_202611%' OR EVENT_NAME LIKE 'demo_finish_DEMO11%';
SET time_zone = '+09:00';
START TRANSACTION;
INSERT INTO games (game_date, away_team_id, home_team_id, stadium_id, game_status_id, naver_game_id, created_at, updated_at)
SELECT v.gd, v.away, v.home,
       (SELECT g2.stadium_id FROM games g2 WHERE g2.home_team_id = v.home AND g2.naver_game_id LIKE '2026%'
         ORDER BY g2.game_date DESC LIMIT 1),
       (SELECT id FROM game_statuses WHERE name = 'SCHEDULED'), v.ngid, NOW(6), NOW(6)
FROM (
              SELECT TIMESTAMP('2026-11-10 12:00:00') gd, 16 away, 17 home, 'DEMO1110OBLG02026' ngid  -- 두산@LG
    UNION ALL SELECT TIMESTAMP('2026-11-10 12:00:00'), 19, 22, 'DEMO1110KTHH02026'  -- KT@한화
    UNION ALL SELECT TIMESTAMP('2026-11-10 12:00:00'), 23, 25, 'DEMO1110NCSK02026'  -- NC@SSG
    UNION ALL SELECT TIMESTAMP('2026-11-10 12:00:00'), 20, 24, 'DEMO1110WOLT02026'  -- 키움@롯데
    UNION ALL SELECT TIMESTAMP('2026-11-10 12:00:00'), 21, 18, 'DEMO1110HTSS02026'  -- KIA@삼성
    UNION ALL SELECT TIMESTAMP('2026-11-11 12:00:00'), 17, 16, 'DEMO1111LGOB02026'  -- LG@두산
    UNION ALL SELECT TIMESTAMP('2026-11-11 12:00:00'), 22, 19, 'DEMO1111HHKT02026'  -- 한화@KT
    UNION ALL SELECT TIMESTAMP('2026-11-11 12:00:00'), 25, 23, 'DEMO1111SKNC02026'  -- SSG@NC
    UNION ALL SELECT TIMESTAMP('2026-11-11 12:00:00'), 24, 20, 'DEMO1111LTWO02026'  -- 롯데@키움
    UNION ALL SELECT TIMESTAMP('2026-11-11 12:00:00'), 18, 21, 'DEMO1111SSHT02026'  -- 삼성@KIA
    UNION ALL SELECT TIMESTAMP('2026-11-12 12:00:00'), 18, 17, 'DEMO1112SSLG02026'  -- 삼성@LG
    UNION ALL SELECT TIMESTAMP('2026-11-12 12:00:00'), 16, 21, 'DEMO1112OBHT02026'  -- 두산@KIA
    UNION ALL SELECT TIMESTAMP('2026-11-12 12:00:00'), 24, 19, 'DEMO1112LTKT02026'  -- 롯데@KT
    UNION ALL SELECT TIMESTAMP('2026-11-12 12:00:00'), 22, 23, 'DEMO1112HHNC02026'  -- 한화@NC
    UNION ALL SELECT TIMESTAMP('2026-11-12 12:00:00'), 25, 20, 'DEMO1112SKWO02026'  -- SSG@키움
    UNION ALL SELECT TIMESTAMP('2026-11-13 12:00:00'), 17, 18, 'DEMO1113LGSS02026'  -- LG@삼성
    UNION ALL SELECT TIMESTAMP('2026-11-13 12:00:00'), 21, 16, 'DEMO1113HTOB02026'  -- KIA@두산
    UNION ALL SELECT TIMESTAMP('2026-11-13 12:00:00'), 19, 24, 'DEMO1113KTLT02026'  -- KT@롯데
    UNION ALL SELECT TIMESTAMP('2026-11-13 12:00:00'), 23, 22, 'DEMO1113NCHH02026'  -- NC@한화
    UNION ALL SELECT TIMESTAMP('2026-11-13 12:00:00'), 20, 25, 'DEMO1113WOSK02026'  -- 키움@SSG
    UNION ALL SELECT TIMESTAMP('2026-11-14 12:00:00'), 19, 17, 'DEMO1114KTLG02026'  -- KT@LG
    UNION ALL SELECT TIMESTAMP('2026-11-14 12:00:00'), 16, 23, 'DEMO1114OBNC02026'  -- 두산@NC
    UNION ALL SELECT TIMESTAMP('2026-11-14 12:00:00'), 18, 24, 'DEMO1114SSLT02026'  -- 삼성@롯데
    UNION ALL SELECT TIMESTAMP('2026-11-14 12:00:00'), 21, 22, 'DEMO1114HTHH02026'  -- KIA@한화
    UNION ALL SELECT TIMESTAMP('2026-11-14 12:00:00'), 25, 20, 'DEMO1114SKWO02026'  -- SSG@키움
    UNION ALL SELECT TIMESTAMP('2026-11-15 12:00:00'), 17, 19, 'DEMO1115LGKT02026'  -- LG@KT
    UNION ALL SELECT TIMESTAMP('2026-11-15 12:00:00'), 23, 16, 'DEMO1115NCOB02026'  -- NC@두산
    UNION ALL SELECT TIMESTAMP('2026-11-15 12:00:00'), 24, 18, 'DEMO1115LTSS02026'  -- 롯데@삼성
    UNION ALL SELECT TIMESTAMP('2026-11-15 12:00:00'), 22, 21, 'DEMO1115HHHT02026'  -- 한화@KIA
    UNION ALL SELECT TIMESTAMP('2026-11-15 12:00:00'), 20, 25, 'DEMO1115WOSK02026'  -- 키움@SSG
) v
WHERE NOT EXISTS (SELECT 1 FROM games g WHERE g.naver_game_id = v.ngid);
COMMIT;

-- ---------------------------------------------------------------- 2026-11-10
DROP EVENT IF EXISTS demo_games_20261110_start;
CREATE EVENT demo_games_20261110_start ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 MINUTE ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1110OBLG02026','DEMO1110KTHH02026','DEMO1110NCSK02026','DEMO1110WOLT02026','DEMO1110HTSS02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- 두산@LG 4:6, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110OBLG02026;
CREATE EVENT demo_finish_DEMO1110OBLG02026 ON SCHEDULE AT '2026-11-10 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 4, home_score = 6, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110OBLG02026';
-- KT@한화 9:2, 16:15 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110KTHH02026;
CREATE EVENT demo_finish_DEMO1110KTHH02026 ON SCHEDULE AT '2026-11-10 16:15:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 9, home_score = 2, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110KTHH02026';
-- NC@SSG 3:4, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110NCSK02026;
CREATE EVENT demo_finish_DEMO1110NCSK02026 ON SCHEDULE AT '2026-11-10 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 3, home_score = 4, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110NCSK02026';
-- 키움@롯데 5:7, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110WOLT02026;
CREATE EVENT demo_finish_DEMO1110WOLT02026 ON SCHEDULE AT '2026-11-10 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 5, home_score = 7, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110WOLT02026';
-- KIA@삼성 6:5, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1110HTSS02026;
CREATE EVENT demo_finish_DEMO1110HTSS02026 ON SCHEDULE AT '2026-11-10 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 6, home_score = 5, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1110HTSS02026';

-- ---------------------------------------------------------------- 2026-11-11
DROP EVENT IF EXISTS demo_games_20261111_start;
CREATE EVENT demo_games_20261111_start ON SCHEDULE AT '2026-11-11 12:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1111LGOB02026','DEMO1111HHKT02026','DEMO1111SKNC02026','DEMO1111LTWO02026','DEMO1111SSHT02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- LG@두산 2:1, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1111LGOB02026;
CREATE EVENT demo_finish_DEMO1111LGOB02026 ON SCHEDULE AT '2026-11-11 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 2, home_score = 1, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1111LGOB02026';
-- 한화@KT 3:8, 16:20 종료
DROP EVENT IF EXISTS demo_finish_DEMO1111HHKT02026;
CREATE EVENT demo_finish_DEMO1111HHKT02026 ON SCHEDULE AT '2026-11-11 16:20:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 3, home_score = 8, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1111HHKT02026';
-- SSG@NC 7:6, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1111SKNC02026;
CREATE EVENT demo_finish_DEMO1111SKNC02026 ON SCHEDULE AT '2026-11-11 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 7, home_score = 6, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1111SKNC02026';
-- 롯데@키움 1:4, 16:40 종료
DROP EVENT IF EXISTS demo_finish_DEMO1111LTWO02026;
CREATE EVENT demo_finish_DEMO1111LTWO02026 ON SCHEDULE AT '2026-11-11 16:40:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 1, home_score = 4, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1111LTWO02026';
-- 삼성@KIA 5:3, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1111SSHT02026;
CREATE EVENT demo_finish_DEMO1111SSHT02026 ON SCHEDULE AT '2026-11-11 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 5, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1111SSHT02026';

-- ---------------------------------------------------------------- 2026-11-12
DROP EVENT IF EXISTS demo_games_20261112_start;
CREATE EVENT demo_games_20261112_start ON SCHEDULE AT '2026-11-12 12:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1112SSLG02026','DEMO1112OBHT02026','DEMO1112LTKT02026','DEMO1112HHNC02026','DEMO1112SKWO02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- 삼성@LG 3:5, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1112SSLG02026;
CREATE EVENT demo_finish_DEMO1112SSLG02026 ON SCHEDULE AT '2026-11-12 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 3, home_score = 5, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1112SSLG02026';
-- 두산@KIA 10:4, 16:10 종료
DROP EVENT IF EXISTS demo_finish_DEMO1112OBHT02026;
CREATE EVENT demo_finish_DEMO1112OBHT02026 ON SCHEDULE AT '2026-11-12 16:10:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 10, home_score = 4, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1112OBHT02026';
-- 롯데@KT 2:3, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1112LTKT02026;
CREATE EVENT demo_finish_DEMO1112LTKT02026 ON SCHEDULE AT '2026-11-12 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 2, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1112LTKT02026';
-- 한화@NC 6:4, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1112HHNC02026;
CREATE EVENT demo_finish_DEMO1112HHNC02026 ON SCHEDULE AT '2026-11-12 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 6, home_score = 4, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1112HHNC02026';
-- SSG@키움 8:7, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1112SKWO02026;
CREATE EVENT demo_finish_DEMO1112SKWO02026 ON SCHEDULE AT '2026-11-12 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 8, home_score = 7, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1112SKWO02026';

-- ---------------------------------------------------------------- 2026-11-13
DROP EVENT IF EXISTS demo_games_20261113_start;
CREATE EVENT demo_games_20261113_start ON SCHEDULE AT '2026-11-13 12:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1113LGSS02026','DEMO1113HTOB02026','DEMO1113KTLT02026','DEMO1113NCHH02026','DEMO1113WOSK02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- LG@삼성 1:2, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1113LGSS02026;
CREATE EVENT demo_finish_DEMO1113LGSS02026 ON SCHEDULE AT '2026-11-13 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 1, home_score = 2, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1113LGSS02026';
-- KIA@두산 4:7, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1113HTOB02026;
CREATE EVENT demo_finish_DEMO1113HTOB02026 ON SCHEDULE AT '2026-11-13 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 4, home_score = 7, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1113HTOB02026';
-- KT@롯데 6:3, 16:30 종료
DROP EVENT IF EXISTS demo_finish_DEMO1113KTLT02026;
CREATE EVENT demo_finish_DEMO1113KTLT02026 ON SCHEDULE AT '2026-11-13 16:30:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 6, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1113KTLT02026';
-- NC@한화 5:9, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1113NCHH02026;
CREATE EVENT demo_finish_DEMO1113NCHH02026 ON SCHEDULE AT '2026-11-13 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 5, home_score = 9, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1113NCHH02026';
-- 키움@SSG 2:0, 16:05 종료
DROP EVENT IF EXISTS demo_finish_DEMO1113WOSK02026;
CREATE EVENT demo_finish_DEMO1113WOSK02026 ON SCHEDULE AT '2026-11-13 16:05:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 2, home_score = 0, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1113WOSK02026';

-- ---------------------------------------------------------------- 2026-11-14
DROP EVENT IF EXISTS demo_games_20261114_start;
CREATE EVENT demo_games_20261114_start ON SCHEDULE AT '2026-11-14 12:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1114KTLG02026','DEMO1114OBNC02026','DEMO1114SSLT02026','DEMO1114HTHH02026','DEMO1114SKWO02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- KT@LG 7:5, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1114KTLG02026;
CREATE EVENT demo_finish_DEMO1114KTLG02026 ON SCHEDULE AT '2026-11-14 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 7, home_score = 5, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1114KTLG02026';
-- 두산@NC 3:2, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1114OBNC02026;
CREATE EVENT demo_finish_DEMO1114OBNC02026 ON SCHEDULE AT '2026-11-14 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 3, home_score = 2, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1114OBNC02026';
-- 삼성@롯데 11:3, 16:15 종료
DROP EVENT IF EXISTS demo_finish_DEMO1114SSLT02026;
CREATE EVENT demo_finish_DEMO1114SSLT02026 ON SCHEDULE AT '2026-11-14 16:15:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 11, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1114SSLT02026';
-- KIA@한화 4:6, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1114HTHH02026;
CREATE EVENT demo_finish_DEMO1114HTHH02026 ON SCHEDULE AT '2026-11-14 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 4, home_score = 6, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1114HTHH02026';
-- SSG@키움 5:8, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1114SKWO02026;
CREATE EVENT demo_finish_DEMO1114SKWO02026 ON SCHEDULE AT '2026-11-14 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 5, home_score = 8, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1114SKWO02026';

-- ---------------------------------------------------------------- 2026-11-15
DROP EVENT IF EXISTS demo_games_20261115_start;
CREATE EVENT demo_games_20261115_start ON SCHEDULE AT '2026-11-15 12:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'IN_PROGRESS'),
         home_score = 0, away_score = 0, current_inning = 1, inning_half = 0, updated_at = NOW(6)
   WHERE naver_game_id IN ('DEMO1115LGKT02026','DEMO1115NCOB02026','DEMO1115LTSS02026','DEMO1115HHHT02026','DEMO1115WOSK02026') AND game_status_id = (SELECT id FROM game_statuses WHERE name = 'SCHEDULED');
-- LG@KT 6:4, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1115LGKT02026;
CREATE EVENT demo_finish_DEMO1115LGKT02026 ON SCHEDULE AT '2026-11-15 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 6, home_score = 4, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1115LGKT02026';
-- NC@두산 2:5, 16:35 종료
DROP EVENT IF EXISTS demo_finish_DEMO1115NCOB02026;
CREATE EVENT demo_finish_DEMO1115NCOB02026 ON SCHEDULE AT '2026-11-15 16:35:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 2, home_score = 5, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1115NCOB02026';
-- 롯데@삼성 4:3, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1115LTSS02026;
CREATE EVENT demo_finish_DEMO1115LTSS02026 ON SCHEDULE AT '2026-11-15 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 4, home_score = 3, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1115LTSS02026';
-- 한화@KIA 1:7, 16:10 종료
DROP EVENT IF EXISTS demo_finish_DEMO1115HHHT02026;
CREATE EVENT demo_finish_DEMO1115HHHT02026 ON SCHEDULE AT '2026-11-15 16:10:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 1, home_score = 7, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1115HHHT02026';
-- 키움@SSG 9:8, 17:00 종료
DROP EVENT IF EXISTS demo_finish_DEMO1115WOSK02026;
CREATE EVENT demo_finish_DEMO1115WOSK02026 ON SCHEDULE AT '2026-11-15 17:00:00' ON COMPLETION NOT PRESERVE DO
  UPDATE games SET game_status_id = (SELECT id FROM game_statuses WHERE name = 'FINISHED'),
         away_score = 9, home_score = 8, last_inning = 9, current_inning = NULL, inning_half = NULL,
         home_win_rate = NULL, away_win_rate = NULL, updated_at = NOW(6) WHERE naver_game_id = 'DEMO1115WOSK02026';

-- 확인: stadium_id NULL 이 있으면 그 홈 구단의 2026 정규 실경기가 없다는 뜻 — 직접 채울 것
SELECT g.id, g.naver_game_id, g.game_date, g.away_team_id, g.home_team_id, g.stadium_id, s.name status, g.removed
FROM games g JOIN game_statuses s ON s.id = g.game_status_id WHERE g.naver_game_id IN ('DEMO1110OBLG02026','DEMO1110KTHH02026','DEMO1110NCSK02026','DEMO1110WOLT02026','DEMO1110HTSS02026','DEMO1111LGOB02026','DEMO1111HHKT02026','DEMO1111SKNC02026','DEMO1111LTWO02026','DEMO1111SSHT02026','DEMO1112SSLG02026','DEMO1112OBHT02026','DEMO1112LTKT02026','DEMO1112HHNC02026','DEMO1112SKWO02026','DEMO1113LGSS02026','DEMO1113HTOB02026','DEMO1113KTLT02026','DEMO1113NCHH02026','DEMO1113WOSK02026','DEMO1114KTLG02026','DEMO1114OBNC02026','DEMO1114SSLT02026','DEMO1114HTHH02026','DEMO1114SKWO02026','DEMO1115LGKT02026','DEMO1115NCOB02026','DEMO1115LTSS02026','DEMO1115HHHT02026','DEMO1115WOSK02026') ORDER BY g.game_date, g.id;
SELECT COUNT(*) events, MIN(EXECUTE_AT), MAX(EXECUTE_AT) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()
   AND (EVENT_NAME LIKE 'demo_games_202611%' OR EVENT_NAME LIKE 'demo_finish_DEMO11%');
SELECT @@event_scheduler, @@time_zone;
