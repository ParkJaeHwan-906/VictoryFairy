package com.skhynix.user.game.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.entity.InningHalf;
import com.skhynix.domain.team.entity.Team;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link GameResponse#from}의 승률 노출·마스킹 규칙 단위 테스트. 요구사항:
 * {@code docs/requirements/user/game-win-rate.md}(USER-GWR-1 ~ 9).
 *
 * <p>{@code Game}에는 승률 setter·빌더 파라미터가 없다(채우는 주체는 수집기) — 리플렉션으로 주입한다.
 */
class GameResponseWinRateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> EXPECTED_KEYS = Set.of(
            "gameId", "stadium", "homeTeam", "homeTeamId", "awayTeam", "awayTeamId",
            "homeTeamScore", "awayTeamScore", "gameDate", "gameState", "cancelReason",
            "inning", "inningHalf", "homeTeamWinRate", "awayTeamWinRate");

    private static Team team(long id, String name) {
        Team team = Team.builder().name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static Game game(String state, String home, String away) {
        Game game = Game.builder()
                .naverGameId("20261013LGHT02026")
                .gameDate(LocalDateTime.of(2026, 10, 13, 18, 30))
                .homeTeam(team(3L, "LG"))
                .awayTeam(team(7L, "KIA"))
                .homeScore(3)
                .awayScore(5)
                .gameStatus(GameStatus.builder().name(state).build())
                .currentInning(7)
                .inningHalf(InningHalf.TOP)
                .build();
        ReflectionTestUtils.setField(game, "homeWinRate", home == null ? null : new BigDecimal(home));
        ReflectionTestUtils.setField(game, "awayWinRate", away == null ? null : new BigDecimal(away));
        return game;
    }

    private static JsonNode json(GameResponse response) {
        return MAPPER.valueToTree(response);
    }

    // ---------- GWR-1 ----------

    @Test
    @DisplayName("[USER-GWR-1] 승률이 null 이어도 직렬화 키 집합은 기존 13개 + 승률 2개 = 정확히 15개다")
    void keySet_isFifteen_evenWhenWinRatesAreNull() {
        JsonNode node = json(GameResponse.from(game("SCHEDULED", null, null)));

        assertThat(new HashSet<>(node.propertyNames())).containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
        assertThat(node.get("homeTeamWinRate").isNull()).isTrue();
        assertThat(node.get("awayTeamWinRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("[USER-GWR-1] 승률 값이 있어도 키 집합은 같은 15개다")
    void keySet_isFifteen_whenWinRatesPresent() {
        JsonNode node = json(GameResponse.from(game("IN_PROGRESS", "35.8", "64.2")));

        assertThat(new HashSet<>(node.propertyNames())).containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
    }

    // ---------- GWR-2 ----------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"IN_PROGRESS", "FINISHED", "SCHEDULED"})
    @DisplayName("[USER-GWR-2] 승률 컬럼이 채워진 경기와 비어 있는 경기의 기존 13필드 값은 동일하다")
    void existingThirteenFields_areIdentical_withAndWithoutWinRate(String state) {
        JsonNode withRate = json(GameResponse.from(game(state, "35.8", "64.2"))).deepCopy();
        JsonNode withoutRate = json(GameResponse.from(game(state, null, null))).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withRate).remove(Set.of("homeTeamWinRate", "awayTeamWinRate"));
        ((tools.jackson.databind.node.ObjectNode) withoutRate).remove(Set.of("homeTeamWinRate", "awayTeamWinRate"));

        assertThat(withRate).isEqualTo(withoutRate);
        assertThat(withRate.get("homeTeam").asString()).isEqualTo("LG");
        assertThat(withRate.get("gameState").asString()).isEqualTo(state);
        assertThat(withRate.get("inning").asInt()).isEqualTo(7);
        assertThat(withRate.get("inningHalf").asString()).isEqualTo("TOP");
    }

    // ---------- GWR-3 ----------

    @Test
    @DisplayName("[USER-GWR-3] 승률은 JSON 숫자(따옴표 없음)로 35.8 / 64.2 그대로 직렬화된다")
    void winRate_serializesAsJsonNumber() {
        String text = MAPPER.writeValueAsString(GameResponse.from(game("IN_PROGRESS", "35.8", "64.2")));
        JsonNode node = MAPPER.readTree(text);

        assertThat(text).contains("\"homeTeamWinRate\":35.8").contains("\"awayTeamWinRate\":64.2");
        assertThat(node.get("homeTeamWinRate").isNumber()).isTrue();
        assertThat(node.get("awayTeamWinRate").isNumber()).isTrue();
    }

    @ParameterizedTest(name = "{0}/{1} -> {2}/{3}")
    @CsvSource({
            "35.85,  64.15, 35.9,  64.2",   // HALF_UP: 5 는 올림
            "35.84,  64.16, 35.8,  64.2",
            "35.800003, 64.199997, 35.8, 64.2",   // 환경마다 컬럼 타입이 어긋나도 꼬리는 응답에 안 나온다
            "0.04,   99.96, 0.0,   100.0",
            "50.05,  49.95, 50.1,  50.0"
    })
    @DisplayName("[USER-GWR-3] 소수 첫째 자리 초과 입력은 HALF_UP 으로 한 자리에 맞춰 나간다")
    void winRate_roundsHalfUpToOneDecimal(String home, String away, double expectedHome, double expectedAway) {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", home, away));

        assertThat(response.homeTeamWinRate()).isEqualTo(expectedHome);
        assertThat(response.awayTeamWinRate()).isEqualTo(expectedAway);
    }

    // ---------- GWR-4 ----------

    @Test
    @DisplayName("[USER-GWR-4] IN_PROGRESS 이고 두 값이 유효하면 DB 값 35.8/64.2 를 그대로 반환한다")
    void inProgress_validPair_returnsStoredValues() {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", "35.8", "64.2"));

        assertThat(response.homeTeamWinRate()).isEqualTo(35.8);
        assertThat(response.awayTeamWinRate()).isEqualTo(64.2);
    }

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({"0.0, 100.0", "100.0, 0.0", "0.0, 0.0", "100.0, 100.0", "50.0, 50.0"})
    @DisplayName("[USER-GWR-4] 경계값 0.0 · 100.0 은 범위 안이라 그대로 노출된다")
    void inProgress_boundaryValuesInclusive_areShown(String home, String away) {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", home, away));

        assertThat(response.homeTeamWinRate()).isEqualTo(Double.parseDouble(home));
        assertThat(response.awayTeamWinRate()).isEqualTo(Double.parseDouble(away));
    }

    // ---------- GWR-5 ----------

    @Test
    @DisplayName("[USER-GWR-5] IN_PROGRESS 인데 두 컬럼 모두 NULL 이면 두 필드 null 이고 나머지 필드는 정상이다")
    void inProgress_bothNull_returnsNullPair_otherFieldsIntact() {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", null, null));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
        assertThat(response.gameId()).isEqualTo("20261013LGHT02026");
        assertThat(response.homeTeamScore()).isEqualTo(3);
        assertThat(response.awayTeamScore()).isEqualTo(5);
        assertThat(response.gameState()).isEqualTo("IN_PROGRESS");
        assertThat(response.inning()).isEqualTo(7);
    }

    // ---------- GWR-6 ----------

    @Test
    @DisplayName("[USER-GWR-6] 홈만 값이 있고 원정이 NULL 이면 두 필드 모두 null 이다")
    void inProgress_onlyHomePresent_masksBoth() {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", "35.8", null));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
    }

    @Test
    @DisplayName("[USER-GWR-6] 원정만 값이 있고 홈이 NULL 이면 두 필드 모두 null 이다")
    void inProgress_onlyAwayPresent_masksBoth() {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", null, "64.2"));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
    }

    // ---------- GWR-7 ----------

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "101.0, -1.0",
            "120.0, 30.0",
            "100.1, 50.0",   // 상한 바로 위
            "50.0,  100.1",
            "-0.1,  50.0",   // 하한 바로 아래
            "50.0,  -0.1",
            "100.1, -0.1"
    })
    @DisplayName("[USER-GWR-7] 둘 중 하나라도 0.0~100.0 을 벗어나면 짝이 유효해도 두 필드 모두 null 이다")
    void inProgress_outOfRange_masksBoth(String home, String away) {
        GameResponse response = GameResponse.from(game("IN_PROGRESS", home, away));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
        assertThat(response.gameState()).isEqualTo("IN_PROGRESS");
    }

    // ---------- GWR-8 ----------

    @Test
    @DisplayName("[USER-GWR-8] SCHEDULED 는 DB 값이 50.0/50.0 이어도 null/null 이다")
    void scheduled_masksStoredValues() {
        GameResponse response = GameResponse.from(game("SCHEDULED", "50.0", "50.0"));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
    }

    @Test
    @DisplayName("[USER-GWR-8] CANCELED(취소 사유 있음)는 DB 값이 있어도 null/null 이고 cancelReason 은 유지된다")
    void canceled_masksStoredValues_keepsCancelReason() {
        Game canceled = Game.builder()
                .naverGameId("20261013LGHT02026")
                .gameDate(LocalDateTime.of(2026, 10, 13, 18, 30))
                .homeTeam(team(3L, "LG"))
                .awayTeam(team(7L, "KIA"))
                .gameStatus(GameStatus.builder().name("CANCELED").build())
                .cancelReason("우천취소")
                .build();
        ReflectionTestUtils.setField(canceled, "homeWinRate", new BigDecimal("40.0"));
        ReflectionTestUtils.setField(canceled, "awayWinRate", new BigDecimal("60.0"));

        GameResponse response = GameResponse.from(canceled);

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
        assertThat(response.cancelReason()).isEqualTo("우천취소");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"SUSPENDED", "in_progress", "In_Progress", " IN_PROGRESS", "UNKNOWN", ""})
    @DisplayName("[USER-GWR-8] 5종 밖의 상태 값(대소문자·공백 변형 포함)은 DB 값과 무관하게 null/null 이다")
    void unknownState_masksStoredValues(String state) {
        GameResponse response = GameResponse.from(game(state, "35.8", "64.2"));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
        assertThat(response.gameState()).isEqualTo(state);
    }

    // ---------- GWR-9 ----------

    @Test
    @DisplayName("[USER-GWR-9] FINISHED 는 DB 에 마지막 값 98.7/1.3 이 남아 있어도 null/null 이다")
    void finished_masksStoredValues() {
        GameResponse response = GameResponse.from(game("FINISHED", "98.7", "1.3"));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
    }

    @Test
    @DisplayName("[USER-GWR-9] DRAW 는 DB 값이 있어도 null/null 이다")
    void draw_masksStoredValues() {
        GameResponse response = GameResponse.from(game("DRAW", "50.0", "50.0"));

        assertThat(response.homeTeamWinRate()).isNull();
        assertThat(response.awayTeamWinRate()).isNull();
    }
}
