package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.game;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skhynix.chat.shared.ChatClock;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBlockRepository;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실 Redis·실 Kafka(Testcontainers) 위에 chat 앱 전체를 띄우는 E2E 공통 부모. DB 는 없다 — 리포지토리 4종은 목이다.
 *
 * <p>로컬 .env 가 원격 DB 를 가리키므로 {@code spring.config.import} 를 비워 .env 를 읽지 않게 하고, JPA·DataSource
 * 자동설정을 배제한다. 서브클래스가 같은 속성 집합을 공유하므로 스프링 컨텍스트는 한 번만 만들어진다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = ChatE2eApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=optional:classpath:/does-not-exist.properties",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.metrics.HibernateMetricsAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "jwt.secret=e2e-secret-e2e-secret-e2e-secret-e2e-secret-0123456789",
                "jwt.access-token-validity=3600000",
                "jwt.refresh-token-validity=7200000",
                "chat.kafka.send-timeout-ms=2000",
                "chat.gateway.batch-interval-ms=50",
                "chat.gateway.write-timeout-ms=1000",
                "chat.recovery.batch-size=5",
                "chat.recovery.max-batches=2"
        })
abstract class E2eSupport {

    private static final AtomicLong USER_SEQ = new AtomicLong(1_000);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.kafka.bootstrap-servers", Containers::kafkaBootstrapServers);
        registry.add("spring.data.redis.host", Containers::redisHost);
        registry.add("spring.data.redis.port", Containers::redisPort);
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenProvider tokenProvider;
    @Autowired
    protected com.skhynix.chat.realtime.SseEmitterRegistry registry;

    @MockitoBean
    protected GameRepository gameRepository;
    @MockitoBean
    protected UserAccountRepository userAccountRepository;
    @MockitoBean
    protected UserSupportTeamRepository userSupportTeamRepository;
    @MockitoBean
    protected UserBlockRepository userBlockRepository;

    protected E2eHttp http;
    protected final ChatClock clock = new ChatClock();

    @BeforeEach
    void createHttpClient() {
        http = new E2eHttp(port);
    }

    /** 테스트 사용자. 토큰은 실제 JWT 이고 JwtAuthenticationFilter 의 계정 조회는 목이 응답한다. */
    protected record TestUser(long id, String uid, String token) {
    }

    protected TestUser user() {
        return user("OB", "user-profile-img/u.jpg");
    }

    /** @param teamCode null 이면 응원 구단이 없는 계정 */
    protected TestUser user(String teamCode, String profileImg) {
        long id = USER_SEQ.incrementAndGet();
        String uid = "uid-" + id;
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.of(new ActiveAccountView(id, null)));
        UserAccount account = mock(UserAccount.class);
        when(account.getNickname()).thenReturn("닉네임" + id);
        when(account.getProfileImgUrl()).thenReturn(profileImg);
        given(userAccountRepository.findById(id)).willReturn(Optional.of(account));
        if (teamCode == null) {
            given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(id)).willReturn(Optional.empty());
        } else {
            Team team = mock(Team.class);
            when(team.getCode()).thenReturn(teamCode);
            UserSupportTeam support = mock(UserSupportTeam.class);
            when(support.getTeam()).thenReturn(team);
            given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(id)).willReturn(Optional.of(support));
        }
        given(userBlockRepository.findRelatedAccountIds(id)).willReturn(java.util.Set.of());
        return new TestUser(id, uid, tokenProvider.createAccessToken(uid));
    }

    /** 오늘 경기로 존재하는 새 방. 메타는 아직 없다 — 첫 요청이 지연 생성한다. */
    protected String room() {
        String gameId = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Game todayGame = game(gameId, clock.today().atTime(18, 30), "SCHEDULED");
        given(gameRepository.findByNaverGameId(gameId)).willReturn(Optional.of(todayGame));
        given(gameRepository.findWithDetailsByNaverGameId(gameId)).willReturn(Optional.of(todayGame));
        return gameId;
    }

    protected static String sendBody(String content, String clientMsgId) {
        return "{\"content\":" + E2eHttp.MAPPER.writeValueAsString(content)
                + ",\"clientMsgId\":" + (clientMsgId == null ? "null" : "\"" + clientMsgId + "\"") + "}";
    }

    protected static String newClientMsgId() {
        return UUID.randomUUID().toString();
    }

    protected E2eHttp.Resp send(TestUser user, String room, String content, String clientMsgId) {
        return http.post("/chat/rooms/" + room + "/messages", user.token(), sendBody(content, clientMsgId));
    }

    protected E2eHttp.Resp sendOk(TestUser user, String room, String content) {
        E2eHttp.Resp resp = send(user, room, content, newClientMsgId());
        if (resp.status() != 202) {
            throw new AssertionError("전송이 202 가 아님: " + resp.status() + " " + resp.body());
        }
        return resp;
    }

    protected long msgIdOf(E2eHttp.Resp accepted) {
        return accepted.json().get("data").get("msgId").asLong();
    }

    /** 방 메타를 만든다(상세 조회가 지연 생성한다). */
    protected void openRoom(TestUser user, String room) {
        E2eHttp.Resp resp = http.get("/chat/rooms/" + room, user.token());
        if (resp.status() != 200) {
            throw new AssertionError("방 열기 실패: " + resp.status() + " " + resp.body());
        }
    }

    protected void seedStreamEntry(String room, long offset, long senderId, String content) {
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                .in("chat:game:" + room)
                .withId(org.springframework.data.redis.connection.stream.RecordId.of(offset + "-1"))
                .ofMap(java.util.Map.of("senderId", String.valueOf(senderId), "senderNickname", "닉" + offset,
                        "content", content, "sentAt", "2026-10-09T19:03:21.123+09:00")));
    }

    /**
     * 서버 레지스트리에 그 사용자의 구독이 올라올 때까지 기다린다. 구독 응답 헤더는 첫 쓰기(하트비트 ≤15초 또는 첫 이벤트)
     * 전까지 클라이언트에 도달하지 않으므로(ChatSubscriptionFlowIT 의 헤더 지연 테스트 참고) HTTP 상태로 성립을 판단하지 않는다.
     */
    protected void awaitRegistered(TestUser user, String room) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() ->
                registry.subscriptions(room).stream().anyMatch(s -> java.util.Objects.equals(s.userAccountId(), user.id())));
    }

    protected void anyGame() {
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(java.util.List.of());
    }
}
