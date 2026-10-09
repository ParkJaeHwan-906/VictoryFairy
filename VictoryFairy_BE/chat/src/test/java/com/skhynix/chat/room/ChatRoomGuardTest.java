package com.skhynix.chat.room;

import static com.skhynix.chat.support.ChatFixtures.NOON_KST_UTC;
import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.clockAt;
import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class ChatRoomGuardTest {

    private static final String GAME_ID = "20261009HTLG0";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private GameRepository gameRepository;

    private ChatRoomGuard guard;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(redisTemplate.opsForSet()).willReturn(setOps);
        guard = new ChatRoomGuard(redisTemplate, gameRepository, clockAt(NOON_KST_UTC));
    }

    @Test
    @DisplayName("[CHAT-GC-27] 메타가 있으면 games 를 조회하지도, 방을 다시 만들지도 않고 빈 값을 돌려준다")
    void requireOrCreate_metaExists_doesNotTouchGamesOrCreate() {
        given(redisTemplate.hasKey("chat:room:" + GAME_ID)).willReturn(true);

        Optional<Game> result = guard.requireOrCreate(GAME_ID, gameRepository::findByNaverGameId);

        assertThat(result).isEmpty();
        verifyNoInteractions(gameRepository);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[CHAT-GC-107] 메타가 없는데 오늘 경기면 SET NX EX(다음 자정까지 남은 초)와 SADD 로 지연 생성하고 경기를 돌려준다")
    void requireOrCreate_metaMissingButTodayGame_lazilyCreates() {
        Game todayGame = game(GAME_ID, LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        given(redisTemplate.hasKey("chat:room:" + GAME_ID)).willReturn(false);
        given(gameRepository.findByNaverGameId(GAME_ID)).willReturn(Optional.of(todayGame));

        Optional<Game> result = guard.requireOrCreate(GAME_ID, gameRepository::findByNaverGameId);

        assertThat(result).containsSame(todayGame);
        verify(valueOps).setIfAbsent("chat:room:" + GAME_ID, "20261009", Duration.ofSeconds(12 * 3600));
        verify(setOps).add("chat:rooms:20261009", GAME_ID);
        // 방 집합 키도 TTL 을 갖는다: 오늘 00:00 KST(= 어제 15:00 UTC) + 48h
        verify(redisTemplate).expireAt("chat:rooms:20261009", Instant.parse("2026-10-08T15:00:00Z").plus(Duration.ofHours(48)));
    }

    @Test
    @DisplayName("[CHAT-GC-28] 메타가 없고 경기가 어제 것이면 404 CHATROOM_NOT_FOUND 이고 방을 만들지 않는다")
    void requireOrCreate_yesterdayGame_throws404WithoutCreate() {
        Game yesterday = game(GAME_ID, LocalDateTime.of(2026, 10, 8, 18, 30), "FINISHED");
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(gameRepository.findByNaverGameId(GAME_ID)).willReturn(Optional.of(yesterday));

        assertBusiness(() -> guard.requireOrCreate(GAME_ID), ErrorCode.CHATROOM_NOT_FOUND);

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(setOps, never()).add(anyString(), any(String[].class));
    }

    @Test
    @DisplayName("[CHAT-GC-24] 메타가 없고 내일 경기여도 404 이다(아직 안 열림과 없음을 구분하지 않는다)")
    void requireOrCreate_tomorrowGame_throws404() {
        Game tomorrow = game(GAME_ID, LocalDateTime.of(2026, 10, 10, 0, 0), "SCHEDULED");
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(gameRepository.findByNaverGameId(GAME_ID)).willReturn(Optional.of(tomorrow));

        assertBusiness(() -> guard.requireOrCreate(GAME_ID), ErrorCode.CHATROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("[CHAT-GC-24] 메타도 없고 games 에도 없는 gameId 는 404 이다")
    void requireOrCreate_unknownGame_throws404() {
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(gameRepository.findByNaverGameId("nope")).willReturn(Optional.empty());

        assertBusiness(() -> guard.requireOrCreate("nope"), ErrorCode.CHATROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("[CHAT-GC-29] 존재 확인 중 Redis 가 응답하지 않으면 503 CHAT_BROKER_UNAVAILABLE 이다")
    void requireOrCreate_redisDownOnExistsCheck_throws503() {
        given(redisTemplate.hasKey(anyString())).willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> guard.requireOrCreate(GAME_ID), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        verifyNoInteractions(gameRepository);
    }

    @Test
    @DisplayName("[CHAT-GC-29] 지연 생성 SET NX 중 Redis 가 응답하지 않아도 503 이다")
    void requireOrCreate_redisDownOnLazyCreate_throws503() {
        Game todayGame = game(GAME_ID, LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        given(gameRepository.findByNaverGameId(GAME_ID)).willReturn(Optional.of(todayGame));
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> guard.requireOrCreate(GAME_ID), ErrorCode.CHAT_BROKER_UNAVAILABLE);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 히스토리·신고용 requireExisting 은 메타가 없으면 오늘 경기여도 지연 생성하지 않고 404 이다")
    void requireExisting_metaMissing_throws404WithoutLazyCreate() {
        given(redisTemplate.hasKey("chat:room:" + GAME_ID)).willReturn(false);

        assertBusiness(() -> guard.requireExisting(GAME_ID), ErrorCode.CHATROOM_NOT_FOUND);

        verifyNoInteractions(gameRepository);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[CHAT-GC-68] requireExisting 은 메타가 있으면 그냥 통과한다")
    void requireExisting_metaExists_passes() {
        given(redisTemplate.hasKey("chat:room:" + GAME_ID)).willReturn(true);

        guard.requireExisting(GAME_ID);

        verifyNoInteractions(gameRepository);
    }

    @Test
    @DisplayName("[CHAT-GC-29] requireExisting 도 Redis 가 응답하지 않으면 503 이다")
    void requireExisting_redisDown_throws503() {
        given(redisTemplate.hasKey(anyString())).willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> guard.requireExisting(GAME_ID), ErrorCode.CHAT_BROKER_UNAVAILABLE);
    }

    @Test
    @DisplayName("[CHAT-GC-24] hasKey 가 null 을 돌려주면 메타 없음으로 취급한다")
    void requireExisting_hasKeyNull_treatedAsMissing() {
        given(redisTemplate.hasKey(anyString())).willReturn(null);

        assertBusiness(() -> guard.requireExisting(GAME_ID), ErrorCode.CHATROOM_NOT_FOUND);
    }
}
