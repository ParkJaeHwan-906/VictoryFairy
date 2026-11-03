package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.chat.gateway.service.PresenceStore;
import com.skhynix.chat.global.config.ChatPresenceProperties;
import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.domain.game.repository.GameRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;

/**
 * {@link PresenceStore} 의 Lua 4종(join/refresh/count/leave)이 실 Redis 에 남기는 상태를 직접 확인한다.
 * 스토어를 앱 없이 만들고 두 "파드"는 서로 다른 instanceId 문자열로 흉내 낸다(같은 Redis 를 보는 두 레지스트리와 같다).
 * 만료 시각은 Redis 서버 시각이라, score 를 Redis TIME 기준 과거로 직접 넣어 시간 경과를 만든다(sleep 없음).
 */
class PresenceStoreRedisIT extends RedisIntegrationSupport {

    private static final String POD1 = "pod-1";
    private static final String POD2 = "pod-2";

    private PresenceStore store() {
        return store(45, 90);
    }

    private PresenceStore store(long expireSeconds, long keyTtlSeconds) {
        return new PresenceStore(redis, new ChatPresenceProperties(expireSeconds, 2000, keyTtlSeconds));
    }

    private static String zkey(String room) {
        return "chat:presence:" + room;
    }

    private static String hkey(String room) {
        return "chat:presence-owner:" + room;
    }

    private static long redisNowMs() {
        Long now = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time(TimeUnit.MILLISECONDS));
        return now == null ? -1 : now;
    }

    private static Double score(String room, long user) {
        return redis.opsForZSet().score(zkey(room), String.valueOf(user));
    }

    private static Object owner(String room, long user) {
        return redis.opsForHash().get(hkey(room), String.valueOf(user));
    }

    // ---------- 인원의 정의 ----------

    @Test
    @DisplayName("[CHAT-PR-1] 서로 다른 계정 둘이 같은 방에 등록되면 인원 2다")
    void distinctAccountsAreCountedEach() {
        PresenceStore store = store();

        store.join("R1", List.of(1L), POD1);
        long count = store.join("R1", List.of(2L), POD1);

        assertThat(count).isEqualTo(2L);
        assertThat(store.count("R1")).isEqualTo(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-1] 같은 계정이 파드 1·2 에 동시에 등록돼 있어도(축출 전파 실패) 인원은 2가 아니라 1이다")
    void sameAccountOnTwoPodsIsOnePerson() {
        PresenceStore store = store();

        store.join("R1", List.of(7L), POD1);
        long count = store.join("R1", List.of(7L), POD2);

        assertThat(count).isEqualTo(1L);
        assertThat(store.count("R1")).isEqualTo(1L);
        assertThat(redis.opsForZSet().zCard(zkey("R1"))).isEqualTo(1L);
    }

    @Test
    @DisplayName("[CHAT-PR-2] 서로 다른 파드의 계정은 같은 ZSET 에 합쳐져 어느 파드에서 세어도 같은 값이다")
    void podsAreAggregatedInOneSet() {
        PresenceStore pod1 = store();
        PresenceStore pod2 = store();

        pod1.join("R1", List.of(1L), POD1);
        pod2.join("R1", List.of(2L), POD2);

        assertThat(pod1.count("R1")).isEqualTo(2L);
        assertThat(pod2.count("R1")).isEqualTo(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-2] 한 번에 500명을 넘는 등록도 청크로 나뉘어 전원이 반영된다")
    void largeJoinIsChunked() {
        PresenceStore store = store();
        List<Long> users = new ArrayList<>();
        for (long i = 1; i <= 1_200; i++) {
            users.add(i);
        }

        long count = store.join("BIG", users, POD1);

        assertThat(count).isEqualTo(1_200L);
        assertThat(redis.opsForHash().size(hkey("BIG"))).isEqualTo(1_200L);
    }

    // ---------- 등록·갱신·소유권 ----------

    @Test
    @DisplayName("[CHAT-PR-5] 등록은 member 를 넣고 그 방의 소유자를 등록한 파드로 기록하며, 나중 등록이 소유자를 덮어쓴다")
    void joinRecordsOwnerAndOverwrites() {
        PresenceStore store = store();

        store.join("R1", List.of(5L), POD1);
        assertThat(score("R1", 5L)).isNotNull();
        assertThat(owner("R1", 5L)).isEqualTo(POD1);

        store.join("R1", List.of(5L), POD2);
        assertThat(owner("R1", 5L)).as("마지막 등록자가 소유자").isEqualTo(POD2);
    }

    @Test
    @DisplayName("[CHAT-PR-6] 생존 갱신은 score 를 새 시각으로 올린다")
    void refreshAdvancesScore() {
        PresenceStore store = store();
        store.join("R1", List.of(5L), POD1);
        long old = redisNowMs() - 20_000;
        redis.opsForZSet().add(zkey("R1"), "5", old);

        store.refresh("R1", List.of(5L), POD1);

        assertThat(score("R1", 5L)).isGreaterThan((double) old + 10_000);
    }

    @Test
    @DisplayName("[CHAT-PR-6] 생존 갱신은 이미 있는 다른 파드의 소유자를 빼앗지 않는다")
    void refreshDoesNotStealOwner() {
        PresenceStore store = store();
        store.join("R1", List.of(5L), POD2);

        store.refresh("R1", List.of(5L), POD1);

        assertThat(owner("R1", 5L)).isEqualTo(POD2);
    }

    @Test
    @DisplayName("[CHAT-PR-6] 소유 기록이 없을 때만 갱신이 자기 파드를 소유자로 기록한다")
    void refreshFillsMissingOwner() {
        PresenceStore store = store();
        store.join("R1", List.of(5L), POD2);
        redis.opsForHash().delete(hkey("R1"), "5");

        store.refresh("R1", List.of(5L), POD1);

        assertThat(owner("R1", 5L)).isEqualTo(POD1);
    }

    @Test
    @DisplayName("[CHAT-PR-10] 두 키가 비워진 뒤 다음 생존 갱신이 member 와 소유 기록을 각 구독을 가진 파드로 다시 채운다")
    void refreshRepopulatesAfterKeysLost() {
        PresenceStore store = store();
        store.join("R1", List.of(1L, 2L), POD1);
        store.join("R1", List.of(3L), POD2);
        redis.delete(List.of(zkey("R1"), hkey("R1")));
        assertThat(store.count("R1")).isZero();

        store.refresh("R1", List.of(1L, 2L), POD1);
        store.refresh("R1", List.of(3L), POD2);

        assertThat(store.count("R1")).isEqualTo(3L);
        assertThat(owner("R1", 1L)).isEqualTo(POD1);
        assertThat(owner("R1", 2L)).isEqualTo(POD1);
        assertThat(owner("R1", 3L)).isEqualTo(POD2);
    }

    // ---------- 만료 ----------

    @Test
    @DisplayName("[CHAT-PR-7] 마지막 생존 시각이 45초보다 오래된 member 는 집계에서 빠지고 소유 기록도 함께 지워진다. 45초 이내는 남는다")
    void expiredMembersArePrunedWithOwnerRecord() {
        PresenceStore store = store();
        store.join("R1", List.of(1L, 2L, 3L), POD2);
        long now = redisNowMs();
        redis.opsForZSet().add(zkey("R1"), "1", now - 46_000);   // 만료
        redis.opsForZSet().add(zkey("R1"), "2", now - 40_000);   // 아직 유효

        long count = store.count("R1");

        assertThat(count).isEqualTo(2L);
        assertThat(score("R1", 1L)).isNull();
        assertThat(owner("R1", 1L)).as("만료된 계정의 소유 기록도 지워진다").isNull();
        assertThat(score("R1", 2L)).isNotNull();
        assertThat(owner("R1", 2L)).isEqualTo(POD2);
    }

    @Test
    @DisplayName("[CHAT-PR-7] 만료 판정은 Redis 서버 시각 기준이다 — 서버 시각 -44초는 남고 -46초는 빠진다(경계)")
    void expiryBoundaryUsesRedisClock() {
        PresenceStore store = store();
        store.join("R1", List.of(1L, 2L), POD1);
        long now = redisNowMs();
        redis.opsForZSet().add(zkey("R1"), "1", now - 44_000);
        redis.opsForZSet().add(zkey("R1"), "2", now - 46_000);

        store.count("R1");

        assertThat(score("R1", 1L)).isNotNull();
        assertThat(score("R1", 2L)).isNull();
    }

    @Test
    @DisplayName("[CHAT-PR-7][CHAT-PR-29] expire-seconds=20 이면 -21초가 빠지고 -19초는 남는다(설정 키가 판정에 쓰인다)")
    void expireSecondsIsConfigurable() {
        PresenceStore store = store(20, 90);
        store.join("R1", List.of(1L, 2L), POD1);
        long now = redisNowMs();
        redis.opsForZSet().add(zkey("R1"), "1", now - 21_000);
        redis.opsForZSet().add(zkey("R1"), "2", now - 19_000);

        assertThat(store.count("R1")).isEqualTo(1L);
        assertThat(score("R1", 2L)).isNotNull();
    }

    @Test
    @DisplayName("[CHAT-PR-7] 만료 걷기 뒤에는 실제로 남은 구독 수와 같다 — 죽은 파드의 계정들이 전부 빠져 오차가 남지 않는다")
    void afterDeadPodExpiresCountMatchesLiveSubscribers() {
        PresenceStore store = store();
        store.join("R1", List.of(1L), POD1);
        store.join("R1", List.of(10L, 11L, 12L), POD2);
        long now = redisNowMs();
        for (String dead : List.of("10", "11", "12")) {
            redis.opsForZSet().add(zkey("R1"), dead, now - 50_000);
        }

        assertThat(store.count("R1")).isEqualTo(1L);
        assertThat(redis.opsForHash().keys(hkey("R1"))).containsExactly("1");
    }

    // ---------- 종료 시 제거 ----------

    @Test
    @DisplayName("[CHAT-PR-8] 소유자가 자기 파드면 leave 가 member 와 소유 기록을 함께 지운다(ZSCORE·HGET 모두 nil)")
    void leaveByOwnerRemovesBoth() {
        PresenceStore store = store();
        store.join("R1", List.of(1L, 2L), POD1);

        store.leave("R1", List.of(2L), POD1);

        assertThat(score("R1", 2L)).isNull();
        assertThat(owner("R1", 2L)).isNull();
        assertThat(store.count("R1")).isEqualTo(1L);
        assertThat(score("R1", 1L)).as("다른 계정은 그대로").isNotNull();
    }

    @Test
    @DisplayName("[CHAT-PR-9] 소유자가 다른 파드면 leave 는 member 도 소유 기록도 지우지 않는다(다른 파드로 재접속한 계정)")
    void leaveByNonOwnerKeepsBoth() {
        PresenceStore store = store();
        store.join("R1", List.of(1L), POD1);
        store.join("R1", List.of(1L), POD2);   // 다른 파드로 재접속 — 소유자가 POD2 로 바뀐다

        store.leave("R1", List.of(1L), POD1);   // 옛 구독이 축출로 종료

        assertThat(score("R1", 1L)).isNotNull();
        assertThat(owner("R1", 1L)).isEqualTo(POD2);
        assertThat(store.count("R1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("[CHAT-PR-9] 소유 기록이 없는 계정은 leave 로 지우지 않는다(남의 것일 수 있다)")
    void leaveWithoutOwnerRecordKeepsMember() {
        PresenceStore store = store();
        store.join("R1", List.of(1L), POD1);
        redis.opsForHash().delete(hkey("R1"), "1");

        store.leave("R1", List.of(1L), POD1);

        assertThat(score("R1", 1L)).isNotNull();
    }

    @Test
    @DisplayName("[CHAT-PR-8] 한 번의 leave 에 소유자인 계정과 아닌 계정이 섞여 있으면 소유자인 쪽만 지운다")
    void leaveMixedBatchRemovesOnlyOwned() {
        PresenceStore store = store();
        store.join("R1", List.of(1L), POD1);
        store.join("R1", List.of(2L), POD2);

        store.leave("R1", List.of(1L, 2L), POD1);

        assertThat(score("R1", 1L)).isNull();
        assertThat(owner("R1", 1L)).isNull();
        assertThat(score("R1", 2L)).isNotNull();
        assertThat(owner("R1", 2L)).isEqualTo(POD2);
    }

    @Test
    @DisplayName("[CHAT-PR-8][CHAT-PR-9] leave(파드 1) 와 join(파드 2) 가 맞물려도 member 와 소유 기록은 늘 함께 있거나 함께 없다 — 방금 소유자가 된 등록을 지우지 않는다")
    void leaveAndJoinRaceNeverSplitsOrErasesNewOwner() throws Exception {
        PresenceStore pod1 = store();
        PresenceStore pod2 = store();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 300; round++) {
                String room = "RACE" + round;
                pod1.join(room, List.of(1L), POD1);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> leaver = pool.submit(() -> {
                    go.await();
                    pod1.leave(room, List.of(1L), POD1);
                    return null;
                });
                Future<?> joiner = pool.submit(() -> {
                    go.await();
                    pod2.join(room, List.of(1L), POD2);
                    return null;
                });
                go.countDown();
                leaver.get(10, TimeUnit.SECONDS);
                joiner.get(10, TimeUnit.SECONDS);

                // join 이 leave 뒤에 와도 앞에 와도(앞이면 소유자가 POD2 라 leave 가 무시된다) 최종은 POD2 가 등록한 상태다
                assertThat(score(room, 1L)).as(room).isNotNull();
                assertThat(owner(room, 1L)).as(room).isEqualTo(POD2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 키 수명 ----------

    @Test
    @DisplayName("[CHAT-PR-20] 등록·갱신·만료 걷기·leave 가 있을 때마다 두 키의 TTL 이 90초 이하의 양수로 연장된다")
    void ttlIsExtendedOnEveryWrite() {
        PresenceStore store = store();
        store.join("R1", List.of(1L, 2L), POD1);
        assertThat(redis.getExpire(zkey("R1"))).isBetween(85L, 90L);
        assertThat(redis.getExpire(hkey("R1"))).isBetween(85L, 90L);

        redis.expire(zkey("R1"), 10, TimeUnit.SECONDS);
        redis.expire(hkey("R1"), 10, TimeUnit.SECONDS);
        store.refresh("R1", List.of(1L, 2L), POD1);
        assertThat(redis.getExpire(zkey("R1"))).as("refresh").isBetween(85L, 90L);
        assertThat(redis.getExpire(hkey("R1"))).as("refresh").isBetween(85L, 90L);

        redis.expire(zkey("R1"), 10, TimeUnit.SECONDS);
        redis.expire(hkey("R1"), 10, TimeUnit.SECONDS);
        redis.opsForZSet().add(zkey("R1"), "2", redisNowMs() - 50_000);
        store.count("R1");
        assertThat(redis.getExpire(zkey("R1"))).as("만료 걷기").isBetween(85L, 90L);
        assertThat(redis.getExpire(hkey("R1"))).as("만료 걷기").isBetween(85L, 90L);

        redis.expire(zkey("R1"), 10, TimeUnit.SECONDS);
        redis.expire(hkey("R1"), 10, TimeUnit.SECONDS);
        store.join("R1", List.of(3L), POD1);
        store.leave("R1", List.of(3L), POD1);
        assertThat(redis.getExpire(zkey("R1"))).as("leave").isBetween(85L, 90L);
        assertThat(redis.getExpire(hkey("R1"))).as("leave").isBetween(85L, 90L);
    }

    @Test
    @DisplayName("[CHAT-PR-20] 마지막 쓰기 뒤 key-ttl-seconds 가 지나면 두 키가 모두 사라진다(종료 처리 없이 끊긴 방)")
    void keysDisappearAfterTtlWithoutAnyWrite() throws Exception {
        PresenceStore store = store(45, 2);
        store.join("R1", List.of(1L), POD1);
        assertThat(redis.hasKey(zkey("R1"))).isTrue();
        assertThat(redis.hasKey(hkey("R1"))).isTrue();

        Thread.sleep(3_200);

        assertThat(redis.hasKey(zkey("R1"))).isFalse();
        assertThat(redis.hasKey(hkey("R1"))).isFalse();
    }

    @Test
    @DisplayName("[CHAT-PR-21] 자정 정리 작업은 presence 두 키를 UNLINK 하지 않고 KEYS·SCAN 도 쓰지 않는다 — 어제 방 키만 지운다")
    void midnightCleanupLeavesPresenceKeysAlone() {
        ChatClock chatClock = new ChatClock();
        String yesterdayRooms = "chat:rooms:" + ChatClock.roomDate(chatClock.today().minusDays(1));
        redis.opsForSet().add(yesterdayRooms, "OLDROOM");
        redis.opsForValue().set("chat:room:OLDROOM", "x");
        PresenceStore store = store();
        store.join("OLDROOM", List.of(1L, 2L), POD1);
        GameRepository gameRepository = Mockito.mock(GameRepository.class);
        RoomLifecycleJob job = new RoomLifecycleJob(redis, gameRepository, chatClock);

        List<RedisMonitor.Entry> commands;
        try (RedisMonitor monitor = new RedisMonitor()) {
            int mark = monitor.mark();
            job.run();
            sleepQuietly(300);
            commands = monitor.since(mark);
        }

        assertThat(redis.hasKey("chat:room:OLDROOM")).as("어제 방 meta 는 정리됨").isFalse();
        assertThat(redis.hasKey(zkey("OLDROOM"))).isTrue();
        assertThat(redis.hasKey(hkey("OLDROOM"))).isTrue();
        assertThat(store.count("OLDROOM")).isEqualTo(2L);
        assertThat(commands).extracting(RedisMonitor.Entry::command).doesNotContain("keys", "scan");
        assertThat(commands).filteredOn(e -> e.command().equals("unlink") || e.command().equals("del"))
                .noneMatch(e -> e.touches("chat:presence"));
    }

    // ---------- 파드 시계 무관 ----------

    @Test
    @DisplayName("[CHAT-PR-30] 생존 시각 score 는 Redis 서버 시각(TIME)에서 온다 — 스크립트가 쓴 값이 서버 시각 직전이다")
    void scoreComesFromRedisServerTime() {
        PresenceStore store = store();
        long before = redisNowMs();

        store.join("R1", List.of(1L), POD1);

        long after = redisNowMs();
        assertThat(score("R1", 1L)).isBetween((double) before, (double) after);
    }

    @Test
    @DisplayName("[CHAT-PR-30] presence 의 Redis 경로는 파드 시계(System.currentTimeMillis·Instant.now·Clock)를 쓰지 않는다 — 소스에 호출이 없다")
    void presenceClassesDoNotReadPodClock() throws Exception {
        Path base = Path.of("src/main/java/com/skhynix/chat/gateway/service");
        for (String file : List.of("PresenceStore.java", "PresenceTracker.java")) {
            String code = Files.readString(base.resolve(file));
            // 주석 안의 설명 문구는 제외하고 코드 줄만 본다
            String codeOnly = code.lines().filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//")
                    && !l.trim().startsWith("/*")).reduce("", (a, b) -> a + "\n" + b);
            assertThat(codeOnly).as(file).doesNotContain("currentTimeMillis").doesNotContain("Instant.now")
                    .doesNotContain("LocalDateTime.now").doesNotContain("Clock");
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
