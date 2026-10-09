package com.skhynix.chat.room;

import static com.skhynix.chat.support.ChatFixtures.NOON_KST_UTC;
import static com.skhynix.chat.support.ChatFixtures.clockAt;
import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomLifecycleJobTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private GameRepository gameRepository;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(redisTemplate.opsForSet()).willReturn(setOps);
    }

    private RoomLifecycleJob jobAt(String utc) {
        return new RoomLifecycleJob(redisTemplate, gameRepository, clockAt(utc));
    }

    private void noGames() {
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("[CHAT-GC-16] 어제 방 집합의 gameId 마다 meta·stream·blind 세 키를 이름으로 UNLINK 하고 마지막에 집합 키를 UNLINK 한다")
    void run_unlinksThreeKeysPerYesterdayRoomThenTheSet() {
        given(setOps.members("chat:rooms:20261008")).willReturn(Set.of("A", "B"));
        noGames();

        jobAt(NOON_KST_UTC).run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        InOrder order = inOrder(redisTemplate);
        order.verify(redisTemplate).unlink(keys.capture());
        order.verify(redisTemplate).unlink("chat:rooms:20261008");
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                "chat:room:A", "chat:game:A", "chat:blind:A",
                "chat:room:B", "chat:game:B", "chat:blind:B");
    }

    @Test
    @DisplayName("[CHAT-GC-16] 정리에 KEYS/SCAN/DEL 을 쓰지 않는다")
    void run_neverUsesKeysOrScan() {
        given(setOps.members(anyString())).willReturn(Set.of("A"));
        noGames();

        jobAt(NOON_KST_UTC).run();

        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate, never()).scan(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("[CHAT-GC-16] 어제 방 집합이 비어 있어도 집합 키 UNLINK 는 수행하고 방 키 UNLINK 는 호출하지 않는다")
    void run_emptyYesterdaySet_stillUnlinksSetKeyOnly() {
        given(setOps.members("chat:rooms:20261008")).willReturn(Set.of());
        noGames();

        jobAt(NOON_KST_UTC).run();

        verify(redisTemplate).unlink("chat:rooms:20261008");
        verify(redisTemplate, never()).unlink(anyCollection());
    }

    @Test
    @DisplayName("[CHAT-GC-17] 오늘 경기마다 SET NX EX 86400 과 SADD chat:rooms:{오늘} 를 수행하고 집합에 만료를 건다")
    void run_createsRoomsForTodayGames() {
        Game g1 = game("G1", LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        Game g2 = game("G2", LocalDateTime.of(2026, 10, 9, 18, 30), "CANCELED");
        given(setOps.members(anyString())).willReturn(Set.of());
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                LocalDateTime.of(2026, 10, 9, 0, 0), LocalDateTime.of(2026, 10, 10, 0, 0)))
                .willReturn(List.of(g1, g2));

        jobAt(NOON_KST_UTC).run();

        verify(valueOps).setIfAbsent("chat:room:G1", "20261009", Duration.ofSeconds(86_400));
        verify(valueOps).setIfAbsent("chat:room:G2", "20261009", Duration.ofSeconds(86_400));
        verify(setOps).add("chat:rooms:20261009", "G1", "G2");
        // 오늘 00:00 KST(= 어제 15:00 UTC) + 48h
        verify(redisTemplate).expireAt("chat:rooms:20261009", Instant.parse("2026-10-10T15:00:00Z"));
    }

    @Test
    @DisplayName("[CHAT-GC-21] 오늘 경기가 0건이면 SET·SADD 를 하지 않고 정상 종료한다")
    void run_noGames_createsNothing() {
        given(setOps.members(anyString())).willReturn(Set.of());
        noGames();

        jobAt(NOON_KST_UTC).run();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(setOps, never()).add(anyString(), any(String[].class));
        verify(redisTemplate, never()).expireAt(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("[CHAT-GC-17] naver_game_id 가 null 인 경기는 방으로 만들지 않는다")
    void run_skipsGamesWithoutNaverGameId() {
        Game noId = game(null, LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        given(setOps.members(anyString())).willReturn(Set.of());
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of(noId));

        jobAt(NOON_KST_UTC).run();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[CHAT-GC-18] UTC 15:00(=00:00 KST)에 돌면 '어제'는 20261009, '오늘'은 20261010 으로 KST 기준 판정한다")
    void run_atKstMidnightInUtcPod_usesKstDates() {
        Game g = game("G", LocalDateTime.of(2026, 10, 10, 18, 30), "SCHEDULED");
        given(setOps.members(anyString())).willReturn(Set.of());
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                LocalDateTime.of(2026, 10, 10, 0, 0), LocalDateTime.of(2026, 10, 11, 0, 0)))
                .willReturn(List.of(g));

        jobAt("2026-10-09T15:00:00Z").run();

        verify(setOps).members("chat:rooms:20261009");
        verify(redisTemplate).unlink("chat:rooms:20261009");
        verify(valueOps).setIfAbsent("chat:room:G", "20261010", Duration.ofSeconds(86_400));
        verify(setOps).add("chat:rooms:20261010", "G");
    }

    @Test
    @DisplayName("[CHAT-GC-20] UNLINK 가 0(두 번째 파드)을 돌려줘도 에러가 아니다")
    void run_unlinkReturnsZero_isNotAnError() {
        given(setOps.members(anyString())).willReturn(Set.of("A"));
        given(redisTemplate.unlink(anyCollection())).willReturn(0L);
        given(redisTemplate.unlink(anyString())).willReturn(false);
        noGames();

        jobAt(NOON_KST_UTC).run();
    }

    @Test
    @DisplayName("[CHAT-GC-22] Redis 명령이 실패하면 예외를 그대로 던져 스케줄러가 재시도할 수 있게 한다")
    void run_redisFailure_propagates() {
        given(setOps.members(anyString())).willThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> jobAt(NOON_KST_UTC).run()).isInstanceOf(QueryTimeoutException.class);
    }
}
