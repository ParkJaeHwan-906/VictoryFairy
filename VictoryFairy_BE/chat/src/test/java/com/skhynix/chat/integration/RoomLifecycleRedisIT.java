package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 방 수명 작업과 지연 생성이 실제 Redis 에 남기는 상태(키·TTL·집합)를 확인한다. */
class RoomLifecycleRedisIT extends RedisIntegrationSupport {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private RoomLifecycleJob job;
    private LocalDate today;
    private LocalDate yesterday;

    @BeforeEach
    void setUp() {
        job = new RoomLifecycleJob(redis, gameRepository, clock);
        today = clock.today();
        yesterday = today.minusDays(1);
    }

    private void todayGames(String... ids) {
        List<Game> games = java.util.Arrays.stream(ids)
                .map(id -> game(id, today.atTime(18, 30), "SCHEDULED")).toList();
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(games);
    }

    private String roomsKey(LocalDate day) {
        return "chat:rooms:" + ChatClock.roomDate(day);
    }

    @Test
    @DisplayName("[CHAT-GC-17] 오늘 경기 N건 → chat:room:* N개(TTL ≤ 86400) + chat:rooms:{오늘} 원소 N개, 경기 상태와 무관하게 전부 만든다")
    void run_createsRoomsWithMetaTtlAndSet() {
        todayGames("G1", "G2", "G3");

        job.run();

        for (String id : List.of("G1", "G2", "G3")) {
            assertThat(redis.hasKey("chat:room:" + id)).isTrue();
            assertThat(ttlSeconds("chat:room:" + id)).isBetween(86_300L, 86_400L);
        }
        assertThat(redis.opsForSet().members(roomsKey(today))).containsExactlyInAnyOrder("G1", "G2", "G3");
    }

    @Test
    @DisplayName("[CHAT-GC-25] chat:rooms:{오늘} 집합도 TTL 을 가진다 — 오늘 00:00 KST + 48h 의 절대 시각")
    void run_roomsSetHasTtl() {
        todayGames("G1");

        job.run();

        long expected = 2 * 86_400L - (86_400L - clock.secondsUntilNextMidnight());
        assertThat(ttlSeconds(roomsKey(today))).isBetween(expected - 5, expected + 1);
    }

    @Test
    @DisplayName("[CHAT-GC-16] 어제 방 집합의 방마다 meta·stream·blind 와 집합 키가 모두 사라지고, 오늘 방은 남는다")
    void run_removesYesterdayKeys() {
        redis.opsForValue().set("chat:room:OLD", "x");
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                .in("chat:game:OLD").ofMap(java.util.Map.of("content", "a")));
        redis.opsForSet().add("chat:blind:OLD", "1");
        redis.opsForSet().add(roomsKey(yesterday), "OLD");
        todayGames("NEW");

        job.run();

        assertThat(redis.hasKey("chat:room:OLD")).isFalse();
        assertThat(redis.hasKey("chat:game:OLD")).isFalse();
        assertThat(redis.hasKey("chat:blind:OLD")).isFalse();
        assertThat(redis.hasKey(roomsKey(yesterday))).isFalse();
        assertThat(redis.hasKey("chat:room:NEW")).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-16] 정리는 어제 집합에 적힌 방만 지운다 — 집합에 없는 키와 dedup·rate 키는 건드리지 않는다")
    void run_leavesUnlistedAndTransientKeys() {
        redis.opsForSet().add(roomsKey(yesterday), "OLD");
        redis.opsForValue().set("chat:room:OLD", "x");
        redis.opsForValue().set("chat:room:UNLISTED", "x");
        redis.opsForValue().set("chat:dedup:OLD:abc", "PENDING");
        redis.opsForValue().set("chat:rate:7", "1");
        todayGames();

        job.run();

        assertThat(redis.hasKey("chat:room:OLD")).isFalse();
        assertThat(redis.hasKey("chat:room:UNLISTED")).isTrue();
        assertThat(redis.hasKey("chat:dedup:OLD:abc")).isTrue();
        assertThat(redis.hasKey("chat:rate:7")).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-19] 이미 있는 방 메타는 다시 돌려도(NX) 값과 TTL 이 리셋되지 않는다")
    void run_twice_doesNotResetExistingMeta() throws Exception {
        todayGames("G1");
        job.run();
        redis.opsForValue().set("chat:room:G1", "custom", java.time.Duration.ofSeconds(500));

        job.run();

        assertThat(redis.opsForValue().get("chat:room:G1")).isEqualTo("custom");
        assertThat(ttlSeconds("chat:room:G1")).isLessThanOrEqualTo(500L);
    }

    @Test
    @DisplayName("[CHAT-GC-20] 두 파드가 동시에 같은 작업을 돌려도 에러 없이 같은 결과(방 N개, 원소 N개)가 된다")
    void run_concurrentlyFromTwoPods_sameResult() throws Exception {
        todayGames("G1", "G2");
        redis.opsForSet().add(roomsKey(yesterday), "OLD");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var f1 = pool.submit(() -> {
            go.await();
            job.run();
            return null;
        });
        var f2 = pool.submit(() -> {
            go.await();
            job.run();
            return null;
        });
        go.countDown();
        f1.get();
        f2.get();
        pool.shutdownNow();

        assertThat(redis.opsForSet().members(roomsKey(today))).containsExactlyInAnyOrder("G1", "G2");
        assertThat(redis.hasKey(roomsKey(yesterday))).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-21] 오늘 경기가 0건이면 어제 정리만 하고 chat:room:* 와 오늘 집합을 만들지 않는다")
    void run_noGames_createsNothing() {
        redis.opsForSet().add(roomsKey(yesterday), "OLD");
        redis.opsForValue().set("chat:room:OLD", "x");
        todayGames();

        job.run();

        assertThat(redis.keys("chat:*")).isEmpty();
    }

    // ---------- 지연 생성 (107) ----------

    private ChatRoomGuard guard() {
        return new ChatRoomGuard(redis, gameRepository, clock);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 메타가 없는데 오늘 경기면 지연 생성한다 — TTL 은 다음 자정까지 남은 초 이하이고 chat:rooms:{오늘} 에 등록된다")
    void lazyCreate_createsMetaUntilMidnightAndRegistersInSet() {
        Game g_LATE = game("LATE", today.atTime(19, 0), "SCHEDULED");
        given(gameRepository.findByNaverGameId("LATE")).willReturn(Optional.of(g_LATE));

        guard().requireOrCreate("LATE");

        assertThat(redis.hasKey("chat:room:LATE")).isTrue();
        assertThat(ttlSeconds("chat:room:LATE")).isBetween(1L, clock.secondsUntilNextMidnight() + 1);
        assertThat(redis.opsForSet().isMember(roomsKey(today), "LATE")).isTrue();
        assertThat(ttlSeconds(roomsKey(today))).isGreaterThan(0);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 같은 요청이 두 번(두 파드) 와도 NX 라 메타는 하나이고 처음 값이 유지된다")
    void lazyCreate_twice_keepsFirstMeta() {
        Game g_LATE = game("LATE", today.atTime(19, 0), "SCHEDULED");
        given(gameRepository.findByNaverGameId("LATE")).willReturn(Optional.of(g_LATE));
        redis.opsForValue().set("chat:room:LATE", "first", java.time.Duration.ofSeconds(1000));

        guard().requireOrCreate("LATE");

        assertThat(redis.opsForValue().get("chat:room:LATE")).isEqualTo("first");
        assertThat(ttlSeconds("chat:room:LATE")).isLessThanOrEqualTo(1000L);
    }

    @Test
    @DisplayName("[CHAT-GC-24] 어제 경기·없는 gameId 는 404 이고 아무 키도 만들지 않는다")
    void lazyCreate_notTodaysGame_is404AndCreatesNothing() {
        Game g_OLD = game("OLD", yesterday.atTime(19, 0), "FINISHED");
        given(gameRepository.findByNaverGameId("OLD")).willReturn(Optional.of(g_OLD));
        given(gameRepository.findByNaverGameId("NOPE")).willReturn(Optional.empty());

        assertBusiness(() -> guard().requireOrCreate("OLD"), ErrorCode.CHATROOM_NOT_FOUND);
        assertBusiness(() -> guard().requireOrCreate("NOPE"), ErrorCode.CHATROOM_NOT_FOUND);

        assertThat(redis.keys("chat:*")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-107] 히스토리·신고용 requireExisting 은 오늘 경기여도 메타를 만들지 않고 404 이다")
    void requireExisting_doesNotLazilyCreate() {
        Game g_LATE = game("LATE", today.atTime(19, 0), "SCHEDULED");
        given(gameRepository.findByNaverGameId("LATE")).willReturn(Optional.of(g_LATE));

        assertBusiness(() -> guard().requireExisting("LATE"), ErrorCode.CHATROOM_NOT_FOUND);

        assertThat(redis.keys("chat:*")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-23] 경기가 끝났거나 취소된 방도 자정 전까지 존재한다 — 상태가 FINISHED/CANCELED 여도 지연 생성·통과한다")
    void lazyCreate_finishedAndCanceledGames_stillOpen() {
        Game g_FIN = game("FIN", today.atTime(13, 0), "FINISHED");
        given(gameRepository.findByNaverGameId("FIN")).willReturn(Optional.of(g_FIN));
        Game g_CAN = game("CAN", today.atTime(13, 0), "CANCELED");
        given(gameRepository.findByNaverGameId("CAN")).willReturn(Optional.of(g_CAN));

        guard().requireOrCreate("FIN");
        guard().requireOrCreate("CAN");

        assertThat(redis.hasKey("chat:room:FIN")).isTrue();
        assertThat(redis.hasKey("chat:room:CAN")).isTrue();
    }
}
