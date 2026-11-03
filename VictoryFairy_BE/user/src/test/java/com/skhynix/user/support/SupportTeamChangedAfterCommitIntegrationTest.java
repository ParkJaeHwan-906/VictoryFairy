package com.skhynix.user.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.support.listener.SupportTeamChangedSignalPublisher;
import com.skhynix.user.support.service.SupportService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 스프링 트랜잭션 프록시 + 실제 {@code SupportService} + 실제 {@code @TransactionalEventListener} 를 엮어
 * "커밋 후에만 발행"을 검증한다. DB 가 없어 트랜잭션 매니저만 기록용 가짜({@link RecordingTransactionManager})로 바꿨고,
 * 리포지토리·Redis 는 목이다. 동기화(커밋 훅) 동작은 실제 {@code AbstractPlatformTransactionManager} 의 것이다.
 * 요구사항: {@code docs/requirements/user/support-selection.md} USER-SP-47, 48, 50.
 *
 * <p>비동기 부재는 sleep 대신 FIFO 센티널로 증명한다 - 단일 발행 스레드라, 나중에 보낸 신호가 Redis 에 도달했다면
 * 앞서 큐에 들어간 신호는 이미 처리된 것이다.
 */
class SupportTeamChangedAfterCommitIntegrationTest {

    private static final long AWAIT_SECONDS = 5;
    private static final Long ACCOUNT_ID = 1L;
    private static final Long OTHER_ACCOUNT_ID = 2L;
    private static final Team KIA = teamOf(6L, "KIA", "HT");
    private static final Team LG = teamOf(8L, "LG", "LG");

    /** 트랜잭션 경계와 Redis 발행을 한 목록에 순서대로 기록한다. */
    private static final List<String> TIMELINE = new CopyOnWriteArrayList<>();
    private static final BlockingQueue<String> PUBLISHED = new LinkedBlockingQueue<>();

    private AnnotationConfigApplicationContext context;
    private SupportService supportService;
    private TransactionTemplate transactionTemplate;
    private StringRedisTemplate redis;
    private UserSupportTeamRepository supportTeamRepository;
    private UserAccountRepository accountRepository;

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        UserSupportTeamRepository userSupportTeamRepository() {
            return mock(UserSupportTeamRepository.class);
        }

        @Bean
        UserSupportPlayerRepository userSupportPlayerRepository() {
            return mock(UserSupportPlayerRepository.class);
        }

        @Bean
        TeamRepository teamRepository() {
            return mock(TeamRepository.class);
        }

        @Bean
        PlayerRepository playerRepository() {
            return mock(PlayerRepository.class);
        }

        @Bean
        UserAccountRepository userAccountRepository() {
            return mock(UserAccountRepository.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        SupportService supportService(UserSupportTeamRepository teams, UserSupportPlayerRepository players,
                TeamRepository teamRepository, PlayerRepository playerRepository,
                UserAccountRepository accounts, org.springframework.context.ApplicationEventPublisher publisher) {
            return new SupportService(teams, players, teamRepository, playerRepository, accounts, publisher);
        }

        @Bean
        SupportTeamChangedSignalPublisher supportTeamChangedSignalPublisher(StringRedisTemplate redis,
                ObjectMapper objectMapper) {
            return new SupportTeamChangedSignalPublisher(redis, objectMapper);
        }
    }

    /** 가짜 매니저 - begin/commit/rollback 을 TIMELINE 에 남기고, 기존 트랜잭션 참여 판정만 흉내 낸다. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active.get();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            active.set(true);
            TIMELINE.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            TIMELINE.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            TIMELINE.add("rollback");
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.set(false);
        }
    }

    private static Team teamOf(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static UserAccount account() {
        return UserAccount.builder().nickname("응원러").password("encoded").build();
    }

    @BeforeEach
    void setUp() {
        TIMELINE.clear();
        PUBLISHED.clear();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        supportService = context.getBean(SupportService.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        redis = context.getBean(StringRedisTemplate.class);
        supportTeamRepository = context.getBean(UserSupportTeamRepository.class);
        accountRepository = context.getBean(UserAccountRepository.class);
        TeamRepository teamRepository = context.getBean(TeamRepository.class);
        UserSupportPlayerRepository playerSupportRepository = context.getBean(UserSupportPlayerRepository.class);

        given(teamRepository.findById(8L)).willReturn(Optional.of(LG));
        given(playerSupportRepository.findAllByUserAccount_IdAndOpposeIsNull(anyLongValue()))
                .willReturn(Collections.emptyList());
        for (Long id : List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)) {
            given(accountRepository.findWithLockById(id)).willReturn(Optional.of(account()));
            given(accountRepository.getReferenceById(id)).willReturn(account());
            given(supportTeamRepository.findByUserAccount_IdAndOpposeIsNull(id))
                    .willReturn(Optional.of(UserSupportTeam.builder().userAccount(account()).team(KIA).build()));
            given(supportTeamRepository.findByUserAccount_IdAndTeam_Id(id, 8L)).willReturn(Optional.empty());
        }
        given(supportTeamRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> call.getArgument(0));
        given(redis.convertAndSend(anyString(), anyString())).willAnswer(call -> {
            TIMELINE.add("publish");
            PUBLISHED.add(call.getArgument(1));
            return 1L;
        });
    }

    private static Long anyLongValue() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("[USER-SP-47] 구단을 바꾸면 트랜잭션이 커밋된 뒤에야 support:team-changed 가 1건 발행된다(커밋 전 발행 아님)")
    void changedTeam_publishesAfterCommit() throws Exception {
        supportService.selectTeam(ACCOUNT_ID, 8L);

        assertThat(PUBLISHED.poll(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("{\"userAccountId\":1}");
        assertThat(TIMELINE).containsExactly("begin", "commit", "publish");
        assertThat(PUBLISHED).isEmpty();
    }

    @Test
    @DisplayName("[USER-SP-48] 구단 변경이 포함된 트랜잭션이 롤백되면 이벤트가 이미 발행돼 있었어도 Redis 발행은 일어나지 않는다")
    void rolledBackTransaction_doesNotPublish() throws Exception {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            supportService.selectTeam(ACCOUNT_ID, 8L); // 바깥 트랜잭션에 참여, 이벤트는 커밋 대기 상태
            throw new IllegalStateException("이후 단계 실패");
        })).isInstanceOf(IllegalStateException.class);

        // 이후 정상 커밋된 다른 계정의 신호가 처리됐다면, FIFO 상 롤백된 신호가 있었다면 먼저 도착했어야 한다.
        supportService.selectTeam(OTHER_ACCOUNT_ID, 8L);
        assertThat(PUBLISHED.poll(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("{\"userAccountId\":2}");
        assertThat(PUBLISHED).isEmpty();
        assertThat(TIMELINE).contains("rollback");
    }

    @Test
    @DisplayName("[USER-SP-50] Redis 발행이 실패해도 구단 변경 호출은 정상 반환하고 커밋은 유지된다")
    void redisFailure_doesNotAffectCommittedChange() throws Exception {
        // given(...) 로 재스텁하면 기존 스텁이 그 자리에서 실행돼 TIMELINE 이 오염된다 - doAnswer 를 쓴다.
        org.mockito.Mockito.doAnswer(call -> {
            TIMELINE.add("publish-failed");
            PUBLISHED.add("failed");
            throw new IllegalStateException("redis down");
        }).when(redis).convertAndSend(anyString(), anyString());

        var response = supportService.selectTeam(ACCOUNT_ID, 8L);

        assertThat(response.id()).isEqualTo(8L);
        assertThat(response.name()).isEqualTo("LG");
        assertThat(PUBLISHED.poll(AWAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("failed");
        assertThat(TIMELINE).containsExactly("begin", "commit", "publish-failed");
    }
}
