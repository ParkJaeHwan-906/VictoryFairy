-- 구(舊) 수집기 소유 스키마 → 운영 서비스 스키마 전환 마이그레이션. MySQL 8.0.
--
-- 언제 실행하나: 운영 DB에 예전 수집기 스키마(teams가 team_code PK, players가
--   player_id PK, games가 VARCHAR game_id PK, game_players/game_batting/
--   game_pitching/game_innings/player_registrations 존재)가 있을 때 딱 한 번.
--   실행 전 `SHOW CREATE TABLE teams;` 로 구 스키마인지 반드시 확인할 것 —
--   이미 BIGINT id PK(서비스 스키마)라면 이 파일을 실행하면 안 된다.
--
-- 데이터는 백업하지 않고 버린다: 전부 원천(KBO 사이트·네이버 API)에서
--   백필로 재적재 가능하다 (records --from ... --to ... / registrations).
--
-- 실행 후 schema.sql 을 이어서 실행해 새 테이블·시드를 만든다.

-- FK 역순으로 제거
DROP TABLE IF EXISTS game_batting;
DROP TABLE IF EXISTS game_pitching;
DROP TABLE IF EXISTS game_innings;
DROP TABLE IF EXISTS games;
DROP TABLE IF EXISTS game_players;
DROP TABLE IF EXISTS player_registrations;
DROP TABLE IF EXISTS players;
DROP TABLE IF EXISTS teams;
