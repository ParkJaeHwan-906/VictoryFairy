package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.game;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skhynix.chat.shared.ChatClock;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBlockRepository;
import com.skhynix.websupport.jwt.JwtProperties;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 스프링 테스트 컨텍스트 캐시 밖에서 chat 앱을 직접 띄우고 닫는 도구(역할 플래그 조합·종료 시간 검증용).
 * DB 는 없다 — 리포지토리 4종은 목 빈이다. 로컬 .env 를 읽지 않는다.
 */
final class AppLauncher implements AutoCloseable {

    static final String JWT_SECRET = "launcher-secret-launcher-secret-launcher-secret-0123456789";

    final GameRepository gameRepository = mock(GameRepository.class);
    final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    final UserSupportTeamRepository userSupportTeamRepository = mock(UserSupportTeamRepository.class);
    final UserBlockRepository userBlockRepository = mock(UserBlockRepository.class);
    private final JwtTokenProvider tokenProvider;
    private final ChatClock clock = new ChatClock();
    ConfigurableApplicationContext context;
    int port;

    AppLauncher() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(JWT_SECRET);
        properties.setAccessTokenValidity(3_600_000L);
        properties.setRefreshTokenValidity(7_200_000L);
        this.tokenProvider = new JwtTokenProvider(properties);
    }

    /** @param extraArgs 예: "--chat.role.api=false" */
    AppLauncher start(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.import=optional:classpath:/does-not-exist.properties",
                "--spring.main.web-application-type=servlet",
                "--server.port=0",
                "--spring.autoconfigure.exclude="
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
                "--spring.kafka.bootstrap-servers=" + Containers.kafkaBootstrapServers(),
                "--spring.data.redis.host=" + Containers.redisHost(),
                "--spring.data.redis.port=" + Containers.redisPort(),
                "--jwt.secret=" + JWT_SECRET,
                "--jwt.access-token-validity=3600000",
                "--jwt.refresh-token-validity=7200000",
                "--chat.kafka.send-timeout-ms=2000",
                "--chat.gateway.batch-interval-ms=50"));
        args.addAll(List.of(extraArgs));
        context = new SpringApplicationBuilder(ChatE2eApplication.class)
                .web(WebApplicationType.SERVLET)
                .logStartupInfo(false)
                .initializers(ctx -> {
                    GenericApplicationContext generic = (GenericApplicationContext) ctx;
                    generic.registerBean("gameRepository", GameRepository.class, () -> gameRepository);
                    generic.registerBean("userAccountRepository", UserAccountRepository.class, () -> userAccountRepository);
                    generic.registerBean("userSupportTeamRepository", UserSupportTeamRepository.class,
                            () -> userSupportTeamRepository);
                    generic.registerBean("userBlockRepository", UserBlockRepository.class, () -> userBlockRepository);
                })
                .run(args.toArray(String[]::new));
        port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return this;
    }

    /** 이 앱에서 통하는 사용자 토큰(JWT 필터의 계정 조회는 목이 응답한다). */
    String userToken(long id) {
        String uid = "launcher-uid-" + id;
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.of(new ActiveAccountView(id, null)));
        UserAccount account = mock(UserAccount.class);
        when(account.getNickname()).thenReturn("닉" + id);
        given(userAccountRepository.findById(id)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(id)).willReturn(Optional.empty());
        given(userBlockRepository.findRelatedAccountIds(id)).willReturn(Set.of());
        return tokenProvider.createAccessToken(uid);
    }

    /** 오늘 경기로 존재하는 방. */
    String room() {
        String gameId = "LAUNCH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Game today = game(gameId, clock.today().atTime(18, 30), "SCHEDULED");
        given(gameRepository.findByNaverGameId(gameId)).willReturn(Optional.of(today));
        given(gameRepository.findWithDetailsByNaverGameId(gameId)).willReturn(Optional.of(today));
        return gameId;
    }

    E2eHttp http() {
        return new E2eHttp(port);
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
