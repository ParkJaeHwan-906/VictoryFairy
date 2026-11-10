-- 운영 쓰기: 페르소나 계정 60명 추가(구단당 6명). gen_personas_sql.py 로 생성.
-- 되돌리기: DELETE FROM users WHERE email LIKE 'persona2-%@persona.victoryfairy.internal';  (FK CASCADE)
--   캐스케이드가 의심되면 user_support_team → users_bq → users_account → users 순서로.
SET NAMES utf8mb4;
DROP TEMPORARY TABLE IF EXISTS tmp_persona;
CREATE TEMPORARY TABLE tmp_persona (
  email VARCHAR(100) COLLATE utf8mb4_unicode_ci, nickname VARCHAR(100) COLLATE utf8mb4_unicode_ci,
  team_id BIGINT, bq BIGINT, point BIGINT) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT INTO tmp_persona VALUES
  ('persona2-001@persona.victoryfairy.internal', '잠실의밤', 17, 32, 800),
  ('persona2-002@persona.victoryfairy.internal', '트윈스사랑', 17, 155, 800),
  ('persona2-003@persona.victoryfairy.internal', '엘지직관러', 17, 7, 800),
  ('persona2-004@persona.victoryfairy.internal', '무적엘지팬', 17, 7, 300),
  ('persona2-005@persona.victoryfairy.internal', '쌍둥이응원단', 17, 0, 2000),
  ('persona2-006@persona.victoryfairy.internal', '잠실외야석', 17, 4, 500),
  ('persona2-007@persona.victoryfairy.internal', '허슬두최고', 16, 1, 1200),
  ('persona2-008@persona.victoryfairy.internal', '곰돌이팬', 16, 6, 0),
  ('persona2-009@persona.victoryfairy.internal', '베어스직관', 16, 25, 100),
  ('persona2-010@persona.victoryfairy.internal', '두산내야석', 16, 3, 100),
  ('persona2-011@persona.victoryfairy.internal', '잠실곰', 16, 18, 100),
  ('persona2-012@persona.victoryfairy.internal', '미라클두산', 16, 0, 0),
  ('persona2-013@persona.victoryfairy.internal', '라팍직관러', 18, 10, 2000),
  ('persona2-014@persona.victoryfairy.internal', '사자군단', 18, 0, 300),
  ('persona2-015@persona.victoryfairy.internal', '블루윙즈', 18, 5, 2000),
  ('persona2-016@persona.victoryfairy.internal', '대구사자팬', 18, 0, 0),
  ('persona2-017@persona.victoryfairy.internal', '삼성V9', 18, 3, 800),
  ('persona2-018@persona.victoryfairy.internal', '푸른피사나이', 18, 0, 0),
  ('persona2-019@persona.victoryfairy.internal', '수원마법사', 19, 3, 0),
  ('persona2-020@persona.victoryfairy.internal', '위즈파크', 19, 22, 2000),
  ('persona2-021@persona.victoryfairy.internal', '케이티팬', 19, 5, 1200),
  ('persona2-022@persona.victoryfairy.internal', '마법사군단', 19, 8, 1200),
  ('persona2-023@persona.victoryfairy.internal', '위즈응원단', 19, 14, 300),
  ('persona2-024@persona.victoryfairy.internal', '수원직관러', 19, 3, 300),
  ('persona2-025@persona.victoryfairy.internal', '고척돔지기', 20, 17, 100),
  ('persona2-026@persona.victoryfairy.internal', '영웅본색', 20, 23, 2000),
  ('persona2-027@persona.victoryfairy.internal', '버건디팬', 20, 4, 0),
  ('persona2-028@persona.victoryfairy.internal', '히어로즈짱', 20, 0, 0),
  ('persona2-029@persona.victoryfairy.internal', '고척외야', 20, 9, 800),
  ('persona2-030@persona.victoryfairy.internal', '키움직관러', 20, 21, 1200),
  ('persona2-031@persona.victoryfairy.internal', '챔피언스필드', 21, 16, 0),
  ('persona2-032@persona.victoryfairy.internal', '호랑이굴', 21, 48, 300),
  ('persona2-033@persona.victoryfairy.internal', '광주직관러', 21, 2, 100),
  ('persona2-034@persona.victoryfairy.internal', '타이거즈팬', 21, 5, 0),
  ('persona2-035@persona.victoryfairy.internal', '갸팬입니다', 21, 9, 0),
  ('persona2-036@persona.victoryfairy.internal', '기아응원단', 21, 9, 2000),
  ('persona2-037@persona.victoryfairy.internal', '보살의마음', 22, 2, 1200),
  ('persona2-038@persona.victoryfairy.internal', '이글스둥지', 22, 4, 0),
  ('persona2-039@persona.victoryfairy.internal', '대전직관러', 22, 2, 2000),
  ('persona2-040@persona.victoryfairy.internal', '독수리군단', 22, 5, 0),
  ('persona2-041@persona.victoryfairy.internal', '한화행복야구', 22, 3, 100),
  ('persona2-042@persona.victoryfairy.internal', '주황피', 22, 0, 300),
  ('persona2-043@persona.victoryfairy.internal', '공룡발자국', 23, 29, 1200),
  ('persona2-044@persona.victoryfairy.internal', '창원직관러', 23, 7, 500),
  ('persona2-045@persona.victoryfairy.internal', '다이노스팬', 23, 0, 2000),
  ('persona2-046@persona.victoryfairy.internal', '엔팍외야석', 23, 28, 0),
  ('persona2-047@persona.victoryfairy.internal', '공룡군단장', 23, 2, 300),
  ('persona2-048@persona.victoryfairy.internal', '마산아재', 23, 0, 2000),
  ('persona2-049@persona.victoryfairy.internal', '사직갈매기', 24, 4, 300),
  ('persona2-050@persona.victoryfairy.internal', '부산직관러', 24, 8, 1200),
  ('persona2-051@persona.victoryfairy.internal', '롯데자이언츠', 24, 5, 100),
  ('persona2-052@persona.victoryfairy.internal', '거인군단', 24, 4, 100),
  ('persona2-053@persona.victoryfairy.internal', '봉다리응원', 24, 6, 300),
  ('persona2-054@persona.victoryfairy.internal', '마린스타', 24, 9, 0),
  ('persona2-055@persona.victoryfairy.internal', '랜더스필드', 25, 0, 300),
  ('persona2-056@persona.victoryfairy.internal', '인천짠물', 25, 3, 2000),
  ('persona2-057@persona.victoryfairy.internal', '쓱직관러', 25, 16, 1200),
  ('persona2-058@persona.victoryfairy.internal', '문학야구장', 25, 6, 500),
  ('persona2-059@persona.victoryfairy.internal', '상륙작전', 25, 8, 300),
  ('persona2-060@persona.victoryfairy.internal', '랜더스팬', 25, 3, 500);

-- 이미 있는 이메일·닉네임은 건너뛴다(재실행 안전)
DELETE t FROM tmp_persona t WHERE EXISTS (SELECT 1 FROM users u WHERE u.email = t.email)
   OR EXISTS (SELECT 1 FROM users_account ua WHERE ua.nickname = t.nickname);

START TRANSACTION;
INSERT INTO users (email, email_verified, created_at, updated_at)
SELECT email, 1, NOW(6), NOW(6) FROM tmp_persona;
INSERT INTO users_account (uid, user_id, nickname, password, point, created_at, updated_at)
SELECT UUID(), u.id, t.nickname, 'LOCKED-NO-LOGIN', t.point, NOW(6), NOW(6)
  FROM tmp_persona t JOIN users u ON u.email = t.email;
INSERT INTO users_bq (user_account_id, bq_score, created_at, updated_at)
SELECT ua.id, t.bq, NOW(6), NOW(6) FROM tmp_persona t JOIN users u ON u.email = t.email JOIN users_account ua ON ua.user_id = u.id;
INSERT INTO user_support_team (user_account_id, team_id, created_at, updated_at)
SELECT ua.id, t.team_id, NOW(6), NOW(6) FROM tmp_persona t JOIN users u ON u.email = t.email JOIN users_account ua ON ua.user_id = u.id;
COMMIT;

-- 확인: 새로 들어간 수, 전체 페르소나 수, 구단 분포
SELECT COUNT(*) added FROM tmp_persona;
SELECT COUNT(*) personas FROM users u JOIN users_account ua ON ua.user_id = u.id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' AND ua.exit_at IS NULL;
SELECT t.name, COUNT(*) n FROM users u JOIN users_account ua ON ua.user_id = u.id
  JOIN user_support_team ust ON ust.user_account_id = ua.id JOIN teams t ON t.id = ust.team_id
 WHERE u.email LIKE '%@persona.victoryfairy.internal' GROUP BY t.name ORDER BY n DESC;
