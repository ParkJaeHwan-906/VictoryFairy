package com.skhynix.user.player.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 부팅 시 KBO 공식 선수 명단(리소스 {@code data/kbo_players_2026.json})을 {@code players} 에
 * 반영하고 선수코드 두 컬럼(naver_pcode/kbo_player_id)을 정합화하는 시더.
 * py-collector {@code tools/upload_kbo_players.py} 와 같은 로직의 자바 이식판으로,
 * 운영 DB 가 외부 IP 를 차단해도 앱은 VPC 안에서 부팅하므로 여기서 실행한다.
 *
 * <p>실측상 네이버 record API 의 pcode == KBO 공식 playerId(2026-07 교집합 전수 일치)라
 * JSON 의 playerId 로 두 컬럼을 모두 채운다. 행 매칭(선수 1명당):
 * <ul>
 *   <li>kbo 행만 → pcode 채움</li>
 *   <li>pcode 행만(records 잡 산물) → kbo_player_id 채움</li>
 *   <li>두 행으로 분열(동명이인 매칭 포기 부산물) → 라인업 재지정 후 병합</li>
 *   <li>둘 다 없음 → (이름, 팀) 유일 매칭 무코드 행 백필, 아니면 신규 INSERT</li>
 * </ul>
 * 마지막 sync pass 가 JSON 에 없는 선수의 한쪽 코드도 동치값으로 채운다. 전 과정이
 * 자연키 기준이라 재부팅마다 돌아도 멱등이다.
 *
 * <p><b>채우기 전용</b>: 기존 행의 이름·소속팀은 덮어쓰지 않는다. JSON 은 크롤 시점 스냅샷이라
 * 부팅마다 재단언하면 일일 registrations 잡이 반영한 최신 상태(이적 등)를 배포 때마다 되돌리게
 * 된다. 명단 신선도는 일일 잡 소관이고, 이 시더는 빠진 것(코드·행)만 채운다 — 그래서 계속
 * 켜둬도 안전하다. average 는 다른 잡이 쓰지 않는 값이라 예외로 JSON 의 타자 타율을 반영한다.
 *
 * <p>{@code app.seed.kbo-players=true} 일 때만 활성화되며(운영 프로파일에 설정), 실패해도
 * 부팅을 막지 않는다 — 시딩은 서비스 가용성보다 우선하지 않는다(메일 설정 기본값과 같은 철학).
 * average 는 타자(statsType=batting)의 2026 타율만 반영한다(투수 AVG 는 피안타율).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.kbo-players", havingValue = "true")
public class PlayerCodeSeeder implements ApplicationRunner {

    private static final String RESOURCE = "data/kbo_players_2026.json";

    private static final String TEAM_IDS = "SELECT code, id FROM teams WHERE code IS NOT NULL";
    private static final String TEAM_INSERT = "INSERT INTO teams (code, name) VALUES (?, ?)";
    private static final String BY_KBO =
            "SELECT id, naver_pcode FROM players WHERE kbo_player_id = ?";
    private static final String BY_PCODE =
            "SELECT id, kbo_player_id FROM players WHERE naver_pcode = ?";
    private static final String CANDS_NO_CODE =
            "SELECT id FROM players WHERE name = ? AND team_id = ? "
            + "AND kbo_player_id IS NULL AND naver_pcode IS NULL";
    private static final String INSERT_FULL =
            "INSERT INTO players (kbo_player_id, naver_pcode, name, team_id, average) "
            + "VALUES (?, ?, ?, ?, ?)";
    private static final String SET_PCODE = "UPDATE players SET naver_pcode = ? WHERE id = ?";
    private static final String SET_KBO = "UPDATE players SET kbo_player_id = ? WHERE id = ?";
    private static final String SET_BOTH =
            "UPDATE players SET kbo_player_id = ?, naver_pcode = ? WHERE id = ?";
    private static final String UPDATE_AVERAGE = "UPDATE players SET average = ? WHERE id = ?";
    /** 분열 병합용. UNIQUE (game_id, player_id) 충돌 행은 건너뛰고 선수 삭제 시 CASCADE 로 정리. */
    private static final String REPOINT_LINEUPS =
            "UPDATE IGNORE game_lineups SET player_id = ? WHERE player_id = ?";
    private static final String DELETE_PLAYER = "DELETE FROM players WHERE id = ?";
    private static final String HALF_CODED =
            "SELECT id, name, naver_pcode, kbo_player_id FROM players "
            + "WHERE (naver_pcode IS NULL) != (kbo_player_id IS NULL)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    private int filledPcode, filledKbo, merged, backfilled, inserted;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Boot 4 는 Jackson 3(tools.jackson) 빈을 노출하므로, 이 시더는 컨테이너와 무관하게
            // jackson-databind 2.x 를 직접 쓴다 (부팅 1회 파싱이라 빈 공유가 필요 없다).
            JsonNode doc = new ObjectMapper().readTree(new ClassPathResource(RESOURCE).getInputStream());
            transactionTemplate.executeWithoutResult(tx -> seed(doc));
            log.info("PlayerCodeSeeder: pcode 채움 {} / kbo_id 채움 {} / 병합 {} / "
                    + "무코드 백필 {} / 신규 {}", filledPcode, filledKbo, merged,
                    backfilled, inserted);
        } catch (Exception e) {
            log.error("PlayerCodeSeeder 실패 — 부팅은 계속한다", e);
        }
    }

    private void seed(JsonNode doc) {
        Map<String, Long> teamIds = new java.util.HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(TEAM_IDS)) {
            teamIds.put((String) row.get("code"), ((Number) row.get("id")).longValue());
        }
        for (JsonNode team : doc.get("teams")) {
            String code = team.get("teamCode").asText();
            if (!teamIds.containsKey(code)) {  // 기존 팀 이름은 서비스 데이터라 건드리지 않는다
                jdbc.update(TEAM_INSERT, code, team.get("teamName").asText());
                teamIds.put(code, jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
            }
        }
        for (JsonNode team : doc.get("teams")) {
            long teamId = teamIds.get(team.get("teamCode").asText());
            for (JsonNode p : team.get("players")) {
                applyPlayer(p.get("playerId").asText(), p.get("name").asText(), teamId,
                        battingAverage(p));
            }
        }
        syncPass();
    }

    private void applyPlayer(String code, String name, long teamId, Double avg) {
        List<Map<String, Object>> a = jdbc.queryForList(BY_KBO, code);
        List<Map<String, Object>> b = jdbc.queryForList(BY_PCODE, code);
        Long pid;
        if (!a.isEmpty() && !b.isEmpty() && !a.get(0).get("id").equals(b.get(0).get("id"))) {
            pid = ((Number) a.get(0).get("id")).longValue();
            mergeSplit(pid, ((Number) b.get(0).get("id")).longValue(), code, name);
        } else if (!a.isEmpty()) {
            pid = ((Number) a.get(0).get("id")).longValue();
            if (a.get(0).get("naver_pcode") == null) {
                filledPcode++;
                jdbc.update(SET_PCODE, code, pid);
            }
        } else if (!b.isEmpty()) {
            pid = ((Number) b.get(0).get("id")).longValue();
            filledKbo++;
            log.info("kbo_player_id 채움: {} (pcode 행 {}) ← {}", name, pid, code);
            jdbc.update(SET_KBO, code, pid);
        } else {
            List<Map<String, Object>> cands = jdbc.queryForList(CANDS_NO_CODE, name, teamId);
            if (cands.size() == 1) {
                pid = ((Number) cands.get(0).get("id")).longValue();
                backfilled++;
                jdbc.update(SET_BOTH, code, code, pid);
            } else {
                if (cands.size() > 1) {
                    log.warn("동명이인 매칭 포기(신규 INSERT): {} x{}", name, cands.size());
                }
                inserted++;
                jdbc.update(INSERT_FULL, code, code, name, teamId, avg == null ? 0 : avg);
                return;  // INSERT 가 average 까지 반영했다
            }
        }
        if (avg != null) {
            jdbc.update(UPDATE_AVERAGE, avg, pid);
        }
    }

    /** JSON 에 없는 선수 포함, 한쪽 코드만 있는 행을 동치값으로 채운다. */
    private void syncPass() {
        for (Map<String, Object> row : jdbc.queryForList(HALF_CODED)) {
            long pid = ((Number) row.get("id")).longValue();
            String name = (String) row.get("name");
            String pcode = (String) row.get("naver_pcode");
            String kbo = (String) row.get("kbo_player_id");
            if (kbo == null) {  // pcode 전용 행
                List<Map<String, Object>> other = jdbc.queryForList(BY_KBO, pcode);
                if (!other.isEmpty() && ((Number) other.get(0).get("id")).longValue() != pid) {
                    mergeSplit(((Number) other.get(0).get("id")).longValue(), pid, pcode,
                            name + "(sync)");
                } else {
                    filledKbo++;
                    jdbc.update(SET_KBO, pcode, pid);
                }
            } else {  // kbo 전용 행 — 같은 코드를 pcode 로 쥔 분열 행이 있으면 병합
                List<Map<String, Object>> other = jdbc.queryForList(BY_PCODE, kbo);
                if (!other.isEmpty() && ((Number) other.get(0).get("id")).longValue() != pid) {
                    mergeSplit(pid, ((Number) other.get(0).get("id")).longValue(), kbo,
                            name + "(sync)");
                } else {
                    filledPcode++;
                    jdbc.update(SET_PCODE, kbo, pid);
                }
            }
        }
    }

    /** pcode-전용 행(drop)의 라인업을 kbo 행(keep)으로 재지정하고 병합한다. */
    private void mergeSplit(long keepId, long dropId, String code, String label) {
        merged++;
        log.info("분열 병합: {} — 행 {}(pcode 전용) → 행 {}, code={}", label, dropId, keepId, code);
        jdbc.update(REPOINT_LINEUPS, keepId, dropId);
        jdbc.update(DELETE_PLAYER, dropId);
        jdbc.update(SET_PCODE, code, keepId);
    }

    private Double battingAverage(JsonNode p) {
        if (!"batting".equals(p.path("statsType").asText())) {
            return null;
        }
        JsonNode avg = p.path("stats2026").path("AVG");
        return avg.isNumber() ? avg.asDouble() : null;
    }
}
