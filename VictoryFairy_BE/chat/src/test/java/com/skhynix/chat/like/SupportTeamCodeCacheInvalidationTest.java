package com.skhynix.chat.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.like.service.SupportTeamCodeCache;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 변경 신호에 의한 무효화(LK-53·59·60). 조회 지연은 래치로 만들어 sleep 에 기대지 않는다. */
class SupportTeamCodeCacheInvalidationTest {

    private UserSupportTeamRepository repository;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        repository = mock(UserSupportTeamRepository.class);
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private SupportTeamCodeCache cache(long ttlSeconds) {
        return new SupportTeamCodeCache(repository, new ChatLikesProperties(
                new ChatLikesProperties.RateLimit(10), ttlSeconds, 100, 10_000));
    }

    private static Optional<UserSupportTeam> support(String code) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(code);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        return Optional.of(support);
    }

    /** doReturn 형식 — 이미 블로킹 응답이 걸린 목을 다시 스텁할 때 그 응답이 실행되지 않게 한다. */
    private void teamIs(long userId, String code) {
        doReturn(support(code)).when(repository).findWithTeamByUserAccount_IdAndOpposeIsNull(userId);
    }

    private void selects(long userId, int times) {
        verify(repository, times(times)).findWithTeamByUserAccount_IdAndOpposeIsNull(userId);
    }

    /** 사용자 조회를 DB 를 읽은 직후에 붙잡아 두는 장치. */
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("조회가 DB 까지 들어갔다").isTrue();
        }
    }

    private Gate gateLookup(long userId, String readValue) {
        Gate gate = new Gate();
        Optional<UserSupportTeam> read = support(readValue);
        given(repository.findWithTeamByUserAccount_IdAndOpposeIsNull(userId)).willAnswer(inv -> {
            gate.entered.countDown();
            assertThat(gate.release.await(5, TimeUnit.SECONDS)).isTrue();
            return read;
        });
        return gate;
    }

    @Test
    @DisplayName("[CHAT-LK-53] invalidate 하면 그 사용자의 다음 조회가 DB 를 다시 치고 새 코드를 돌려준다")
    void invalidate_nextLookupReselects() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(60);
        assertThat(cache.teamCode(1L)).contains("HT");

        teamIs(1L, "LG");
        cache.invalidate(1L);

        assertThat(cache.teamCode(1L)).contains("LG");
        selects(1L, 2);
    }

    @Test
    @DisplayName("[CHAT-LK-53] 한 사용자를 invalidate 해도 다른 사용자의 캐시 항목은 남는다")
    void invalidate_keepsOtherUsers() {
        teamIs(1L, "HT");
        teamIs(2L, "LG");
        SupportTeamCodeCache cache = cache(60);
        cache.teamCode(1L);
        cache.teamCode(2L);

        cache.invalidate(1L);

        assertThat(cache.teamCode(2L)).contains("LG");
        selects(2L, 1);
    }

    @Test
    @DisplayName("[CHAT-LK-53] 캐시에 없는 id 를 invalidate 해도 예외 없이 이후 조회가 정상 적재된다")
    void invalidate_unknownId_isNoop() {
        teamIs(7L, "OB");
        SupportTeamCodeCache cache = cache(60);

        cache.invalidate(7L);

        assertThat(cache.teamCode(7L)).contains("OB");
        assertThat(cache.teamCode(7L)).contains("OB");
        selects(7L, 1);
    }

    @Test
    @DisplayName("[CHAT-LK-59] invalidateAll 하면 모든 사용자의 다음 조회가 각각 DB 를 다시 친다")
    void invalidateAll_clearsEveryUser() {
        teamIs(1L, "HT");
        teamIs(2L, "LG");
        SupportTeamCodeCache cache = cache(60);
        cache.teamCode(1L);
        cache.teamCode(2L);

        teamIs(1L, "SS");
        teamIs(2L, "OB");
        cache.invalidateAll();

        assertThat(cache.teamCode(1L)).contains("SS");
        assertThat(cache.teamCode(2L)).contains("OB");
        selects(1L, 2);
        selects(2L, 2);
    }

    @Test
    @DisplayName("[CHAT-LK-14] 무효화 뒤 응원 행이 사라졌으면 empty 를 돌려주고 옛 값을 되살리지 않는다")
    void invalidate_thenRowGone_returnsEmpty() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(60);
        cache.teamCode(1L);
        doReturn(Optional.empty()).when(repository).findWithTeamByUserAccount_IdAndOpposeIsNull(1L);

        cache.invalidate(1L);

        assertThat(cache.teamCode(1L)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-LK-60] 캐시 미스 조회 중에 그 사용자가 무효화되면 그 요청은 읽은 값(HT)을 돌려주되 캐시에 넣지 않아 다음 조회가 DB 값(LG)을 읽는다")
    void invalidateDuringLookup_notCached_requestKeepsReadValue() throws Exception {
        SupportTeamCodeCache cache = cache(60);
        Gate gate = gateLookup(1L, "HT");
        Future<Optional<String>> inFlight = pool.submit(() -> cache.teamCode(1L));
        gate.awaitEntered();

        cache.invalidate(1L);
        gate.release.countDown();

        assertThat(inFlight.get(5, TimeUnit.SECONDS)).as("그 요청은 읽은 값").contains("HT");
        teamIs(1L, "LG");
        assertThat(cache.teamCode(1L)).as("적재되지 않았으므로 DB 재조회").contains("LG");
        selects(1L, 2);
    }

    @Test
    @DisplayName("[CHAT-LK-60] 다른 사용자의 무효화는 진행 중인 조회의 적재를 막지 않는다 — 다음 조회에 DB 를 치지 않는다")
    void invalidateOtherUserDuringLookup_stillCached() throws Exception {
        SupportTeamCodeCache cache = cache(60);
        Gate gate = gateLookup(1L, "HT");
        Future<Optional<String>> inFlight = pool.submit(() -> cache.teamCode(1L));
        gate.awaitEntered();

        cache.invalidate(2L);
        gate.release.countDown();

        assertThat(inFlight.get(5, TimeUnit.SECONDS)).contains("HT");
        assertThat(cache.teamCode(1L)).contains("HT");
        selects(1L, 1);
    }

    @Test
    @DisplayName("[CHAT-LK-60] 전체 비우기(invalidateAll)도 진행 중인 조회의 적재를 막는다")
    void invalidateAllDuringLookup_notCached() throws Exception {
        SupportTeamCodeCache cache = cache(60);
        Gate gate = gateLookup(1L, "HT");
        Future<Optional<String>> inFlight = pool.submit(() -> cache.teamCode(1L));
        gate.awaitEntered();

        cache.invalidateAll();
        gate.release.countDown();

        assertThat(inFlight.get(5, TimeUnit.SECONDS)).contains("HT");
        teamIs(1L, "LG");
        assertThat(cache.teamCode(1L)).contains("LG");
        selects(1L, 2);
    }

    @Test
    @DisplayName("[CHAT-LK-60] 조회 시작 전에 끝난 무효화는 그 뒤 시작한 조회의 적재를 막지 않는다")
    void invalidateBeforeLookupStarts_doesNotBlockLoad() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(60);
        cache.invalidate(1L);
        cache.invalidateAll();

        assertThat(cache.teamCode(1L)).contains("HT");
        assertThat(cache.teamCode(1L)).contains("HT");
        selects(1L, 1);
    }

    @Test
    @DisplayName("[CHAT-LK-60] 전체 비우기 때 진행 중이던 조회가 끝난 뒤에도, 비우기 전에 적재된 다른 사용자 항목은 되살아나지 않는다")
    void invalidateAllDuringLookup_doesNotResurrectEarlierEntries() throws Exception {
        SupportTeamCodeCache cache = cache(60);
        teamIs(2L, "LG");
        Gate gate = gateLookup(1L, "HT");
        Future<Optional<String>> inFlight = pool.submit(() -> cache.teamCode(1L));
        gate.awaitEntered();
        cache.teamCode(2L);

        cache.invalidateAll();
        gate.release.countDown();
        inFlight.get(5, TimeUnit.SECONDS);

        cache.teamCode(2L);
        selects(2L, 2);
    }

    @Test
    @DisplayName("[CHAT-LK-60] evictExpired 는 TTL 이 지난 무효화 기록을 걷고, TTL 안의 기록은 진행 중 조회를 위해 남긴다")
    void evictExpired_sweepsInvalidationMarksByTtl() throws Exception {
        SupportTeamCodeCache expiring = cache(0);
        expiring.invalidate(1L);
        assertThat(marks(expiring)).containsKey(1L);
        expiring.evictExpired();
        assertThat(marks(expiring)).as("ttl 0 → 즉시 만료").isEmpty();

        SupportTeamCodeCache live = cache(300);
        live.invalidate(1L);
        live.evictExpired();
        assertThat(marks(live)).containsKey(1L);
    }

    @Test
    @DisplayName("[CHAT-LK-60] evictExpired 는 전체 비우기에 덮인 단건 무효화 기록을 TTL 과 무관하게 걷는다")
    void evictExpired_sweepsMarksCoveredByClearAll() throws Exception {
        SupportTeamCodeCache cache = cache(300);
        cache.invalidate(1L);
        cache.invalidate(2L);
        cache.invalidateAll();

        cache.evictExpired();

        assertThat(marks(cache)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-LK-60] 기록이 걷힌 뒤에도 그 뒤 시작한 조회는 정상 적재된다")
    void afterEvict_lookupStillCaches() {
        teamIs(1L, "HT");
        SupportTeamCodeCache cache = cache(300);
        cache.invalidate(1L);
        cache.evictExpired();

        cache.teamCode(1L);
        cache.teamCode(1L);

        selects(1L, 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, ?> marks(SupportTeamCodeCache cache) throws Exception {
        Field field = SupportTeamCodeCache.class.getDeclaredField("invalidations");
        field.setAccessible(true);
        return (Map<Long, ?>) field.get(cache);
    }
}
