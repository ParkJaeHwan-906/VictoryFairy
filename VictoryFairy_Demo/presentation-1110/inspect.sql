-- 읽기 전용 점검: 페르소나 계정·데모 경기·이벤트
SELECT COUNT(*) personas FROM users u JOIN users_account ua ON ua.user_id = u.id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' AND ua.exit_at IS NULL;
SELECT IFNULL(t.name, '(응원 구단 없음)') team, COUNT(*) n FROM users u JOIN users_account ua ON ua.user_id = u.id
  LEFT JOIN user_support_team ust ON ust.user_account_id = ua.id LEFT JOIN teams t ON t.id = ust.team_id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' AND ua.exit_at IS NULL GROUP BY t.name ORDER BY n DESC;
SELECT g.naver_game_id, g.game_date, s.name status, g.away_score, g.home_score, g.current_inning, g.inning_half,
       g.away_win_rate, g.home_win_rate, g.stadium_id, g.removed
  FROM games g JOIN game_statuses s ON s.id = g.game_status_id
 WHERE g.game_date >= CURDATE() AND g.game_date < CURDATE() + INTERVAL 1 DAY ORDER BY g.id;
SELECT EVENT_NAME, EXECUTE_AT FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()
   AND EXECUTE_AT < CURDATE() + INTERVAL 1 DAY ORDER BY EXECUTE_AT;
SELECT r.id, r.title, r.like_count, (SELECT COUNT(DISTINCT a.user_account_id) FROM quiz_room_answers a WHERE a.room_id = r.id) solvers
  FROM quiz_rooms r WHERE r.created_at >= '2026-11-10' ORDER BY r.id;
SELECT COUNT(*) posts_since_1110 FROM community_posts p JOIN users_account ua ON ua.id = p.user_account_id
  JOIN users u ON u.id = ua.user_id WHERE u.email LIKE '%@persona.victoryfairy.internal' AND p.created_at >= '2026-11-10';
