package com.skhynix.user.game.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.user.game.dto.GameResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 승률 변화가 S3 알림 -> {@link GameStateEventListener} -> {@link GameUpdateService}(DB 재조회) ->
 * {@link InMemoryGameEventPublisher} -> {@link GameSseRegistry} 로 실제 클래스를 이어 붙인 경로로 구독자에게
 * 닿는지 증명한다(S3·SQS·Repository 만 목). 요구사항: {@code docs/requirements/user/game-win-rate.md}
 * (USER-GWR-1 의 SSE 두 갈래, 10 ~ 13).
 *
 * <p>{@link SseEmitter}는 응답이 열리기 전의 send 를 버퍼에 가둬 내용을 볼 수 없어, {@code send} 를 가로채
 * 실려 나간 데이터 객체를 JSON 으로 직렬화해 보는 캡처용 하위 클래스를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class GameWinRateRealtimeFlowTest {

    private static final String GAME_ID = "20261013LGHT02026";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 13);
    private static final String NOTIFICATION = """
            {"Records":[{"s3":{"bucket":{"name":"b"},
             "object":{"key":"game-state-events/2026-10-13/20261013LGHT02026/x.json"}}}]}
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "gameId", "stadium", "homeTeam", "homeTeamId", "awayTeam", "awayTeamId",
            "homeTeamScore", "awayTeamScore", "gameDate", "gameState", "cancelReason",
            "inning", "inningHalf", "homeTeamWinRate", "awayTeamWinRate");

    @Mock
    private SqsClient sqsClient;
    @Mock
    private S3Client s3Client;
    @Mock
    private GameRepository gameRepository;

    private GameSseRegistry registry;
    private GameStateEventListener listener;

    /** 보낸 데이터 객체를 모아 두는 emitter. */
    static class CapturingEmitter extends SseEmitter {
        final List<Object> payloads = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                Object data = part.getData();
                if (!(data instanceof String)) {
                    payloads.add(data);
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        registry = new GameSseRegistry();
        listener = new GameStateEventListener(sqsClient, s3Client, MAPPER,
                new GameUpdateService(gameRepository), new InMemoryGameEventPublisher(registry), "q");
    }

    private static Team team(long id, String name) {
        Team team = Team.builder().name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static Game game(long homeId, long awayId, String state, String home, String away) {
        Game game = Game.builder()
                .naverGameId(GAME_ID)
                .gameDate(LocalDateTime.of(2026, 10, 13, 18, 30))
                .homeTeam(team(homeId, "H" + homeId))
                .awayTeam(team(awayId, "A" + awayId))
                .homeScore(3)
                .awayScore(5)
                .gameStatus(GameStatus.builder().name(state).build())
                .build();
        ReflectionTestUtils.setField(game, "homeWinRate", home == null ? null : new BigDecimal(home));
        ReflectionTestUtils.setField(game, "awayWinRate", away == null ? null : new BigDecimal(away));
        return game;
    }

    private void deliver(String changedJson, Game dbRow) {
        String document = "{\"gameId\":\"" + GAME_ID + "\",\"changed\":" + changedJson
                + ",\"observedAt\":\"2026-10-13T09:30:00+00:00\"}";
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), document.getBytes(StandardCharsets.UTF_8)));
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID)).willReturn(Optional.of(dbRow));
        listener.handle(Message.builder().messageId("m").receiptHandle("rh").body(NOTIFICATION).build());
    }

    private static JsonNode only(CapturingEmitter emitter) {
        assertThat(emitter.payloads).hasSize(1);
        return MAPPER.valueToTree(emitter.payloads.get(0));
    }

    @Test
    @DisplayName("[USER-GWR-10/11] 승률만 바뀐 문서(changed=[winRate])는 날짜 구독자에게 DB 재조회 값 41.2 의 "
            + "game-update 1건으로 닿고 changed 가 그대로다")
    void winRateOnlyDocument_reachesDateSubscriber_withFreshValue() {
        CapturingEmitter dateSubscriber = new CapturingEmitter();
        registry.register(GameSseRegistry.dateKey(DATE), dateSubscriber);

        deliver("[\"winRate\"]", game(3, 7, "IN_PROGRESS", "41.2", "58.8"));

        JsonNode event = only(dateSubscriber);
        assertThat(event.get("changed").toString()).isEqualTo("[\"winRate\"]");
        assertThat(event.get("game").get("homeTeamWinRate").asDouble()).isEqualTo(41.2);
        assertThat(event.get("game").get("awayTeamWinRate").asDouble()).isEqualTo(58.8);
    }

    @Test
    @DisplayName("[USER-GWR-1/10] game-update 의 data.game 은 15개 키이고 승률은 JSON 숫자다")
    void gameUpdate_gameHasFifteenKeys_withNumericWinRate() {
        CapturingEmitter dateSubscriber = new CapturingEmitter();
        registry.register(GameSseRegistry.dateKey(DATE), dateSubscriber);

        deliver("[\"winRate\"]", game(3, 7, "IN_PROGRESS", "35.8", "64.2"));

        JsonNode game = only(dateSubscriber).get("game");
        assertThat(new HashSet<>(game.propertyNames())).containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
        assertThat(game.get("homeTeamWinRate").isNumber()).isTrue();
        assertThat(game.get("awayTeamWinRate").isNumber()).isTrue();
    }

    @Test
    @DisplayName("[USER-GWR-11] changed=[homeScore, winRate] 는 구독자에게 이벤트 1건, 두 토큰 모두 포함으로 간다")
    void mixedChanged_reachesSubscriberOnce_withBothTokens() {
        CapturingEmitter dateSubscriber = new CapturingEmitter();
        registry.register(GameSseRegistry.dateKey(DATE), dateSubscriber);

        deliver("[\"homeScore\",\"winRate\"]", game(3, 7, "IN_PROGRESS", "41.2", "58.8"));

        JsonNode event = only(dateSubscriber);
        assertThat(event.get("changed").toString()).isEqualTo("[\"homeScore\",\"winRate\"]");
    }

    @Test
    @DisplayName("[USER-GWR-12] 종료로 승률이 null 이 되어도 이벤트는 전달되고 FINISHED + null/null 이다"
            + "(두 키는 존재)")
    void finishedWithNullWinRate_isStillDelivered() {
        CapturingEmitter dateSubscriber = new CapturingEmitter();
        registry.register(GameSseRegistry.dateKey(DATE), dateSubscriber);

        deliver("[\"inning\",\"status\",\"winRate\"]", game(3, 7, "FINISHED", null, null));

        JsonNode game = only(dateSubscriber).get("game");
        assertThat(game.get("gameState").asString()).isEqualTo("FINISHED");
        assertThat(game.has("homeTeamWinRate")).isTrue();
        assertThat(game.get("homeTeamWinRate").isNull()).isTrue();
        assertThat(game.has("awayTeamWinRate")).isTrue();
        assertThat(game.get("awayTeamWinRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("[USER-GWR-13] 구단 6 구독자는 6 이 참여한 경기의 승률 전용 game-update 를 받고, "
            + "6 이 없는 경기의 것은 받지 않는다")
    void supportTeamSubscriber_receivesOnlyInvolvedTeamWinRateUpdates() {
        CapturingEmitter team6 = new CapturingEmitter();
        registry.register(GameSseRegistry.teamKey(DATE, 6L), team6);

        deliver("[\"winRate\"]", game(6, 3, "IN_PROGRESS", "41.2", "58.8"));
        assertThat(only(team6).get("game").get("homeTeamWinRate").asDouble()).isEqualTo(41.2);
        team6.payloads.clear();

        // 6 이 없는 경기(홈 3, 원정 7)
        deliver("[\"winRate\"]", game(3, 7, "IN_PROGRESS", "50.0", "50.0"));
        assertThat(team6.payloads).isEmpty();
    }

    @Test
    @DisplayName("[USER-GWR-13] 구단 6 이 원정으로 참여한 경기의 승률 갱신도 구단 구독자에게 간다")
    void supportTeamSubscriber_receivesWhenTeamIsAway() {
        CapturingEmitter team6 = new CapturingEmitter();
        registry.register(GameSseRegistry.teamKey(DATE, 6L), team6);

        deliver("[\"winRate\"]", game(3, 6, "IN_PROGRESS", "41.2", "58.8"));

        assertThat(only(team6).get("game").get("awayTeamWinRate").asDouble()).isEqualTo(58.8);
    }

    @Test
    @DisplayName("[USER-GWR-1] snapshot 항목도 15개 키이고 승률 값이 없으면 두 키가 null 로 존재한다")
    void snapshot_itemsHaveFifteenKeys() {
        CapturingEmitter emitter = new CapturingEmitter();
        List<GameResponse> games = List.of(
                GameResponse.from(game(3, 7, "IN_PROGRESS", "35.8", "64.2")),
                GameResponse.from(game(3, 7, "SCHEDULED", null, null)));

        registry.sendSnapshot(emitter, games);

        JsonNode list = only(emitter);
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(2);
        for (JsonNode item : list) {
            assertThat(new HashSet<>(item.propertyNames())).containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
        }
        assertThat(list.get(0).get("homeTeamWinRate").asDouble()).isEqualTo(35.8);
        assertThat(list.get(1).get("homeTeamWinRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("[USER-GWR-10] 처리가 끝나면 메시지는 지워진다(승률 이벤트도 기존 ack 경로)")
    void winRateDocument_isAcknowledged() {
        deliver("[\"winRate\"]", game(3, 7, "IN_PROGRESS", "41.2", "58.8"));

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }
}
