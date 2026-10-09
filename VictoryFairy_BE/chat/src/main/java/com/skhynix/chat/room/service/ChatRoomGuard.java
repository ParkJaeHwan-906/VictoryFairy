package com.skhynix.chat.room.service;

import com.skhynix.chat.shared.ChatClock;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 방 단위 경로의 존재 확인. 방이 있다는 것은 {@code chat:room:{gameId}} 메타가 있다는 뜻이다.
 *
 * <ul>
 *   <li>{@link #requireOrCreate}: 상세·구독·전송. 메타가 없는데 오늘 경기면 그 자리에서 만든다(CHAT-GC-107)</li>
 *   <li>{@link #requireExisting}: 히스토리·신고. 지연 생성하지 않는다(메타가 없으면 404)</li>
 * </ul>
 * 두 경우 모두 Redis 가 응답하지 않으면 503 이다(CHAT-GC-29). 퇴장은 존재를 확인하지 않는다(CHAT-GC-46).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatRoomGuard {

    private final StringRedisTemplate redisTemplate;
    private final GameRepository gameRepository;
    private final ChatClock clock;

    public void requireOrCreate(String gameId) {
        requireOrCreate(gameId, gameRepository::findByNaverGameId);
    }

    /**
     * @param gameLoader 메타가 없을 때 경기를 읽는 조회. 상세처럼 같은 요청에서 경기를 다시 쓰는 호출부가
     *                   연관을 실어 오는 조회를 넘기면 games SELECT 가 요청당 1회로 끝난다
     * @return 지연 생성을 위해 읽은 경기. 메타가 이미 있어 경기를 읽지 않았으면 빈 값
     */
    public Optional<Game> requireOrCreate(String gameId, Function<String, Optional<Game>> gameLoader) {
        if (metaExists(gameId)) {
            return Optional.empty();
        }
        Game game = gameLoader.apply(gameId)
                .filter(found -> clock.isToday(found.getGameDate()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));
        createLazily(gameId);
        return Optional.of(game);
    }

    public void requireExisting(String gameId) {
        if (!metaExists(gameId)) {
            throw new BusinessException(ErrorCode.CHATROOM_NOT_FOUND);
        }
    }

    private boolean metaExists(String gameId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(ChatRedisKeys.room(gameId)));
        } catch (DataAccessException e) {
            log.warn("방 메타 조회 실패 gameId={}", gameId, e);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        }
    }

    // NX 라 두 파드가 동시에 와도 메타는 하나다. 자정 작업과 달리 EX 를 다음 자정까지로 둔다.
    private void createLazily(String gameId) {
        try {
            redisTemplate.opsForValue().setIfAbsent(ChatRedisKeys.room(gameId),
                    ChatClock.roomDate(clock.today()),
                    Duration.ofSeconds(clock.secondsUntilNextMidnight()));
            String roomsKey = ChatRedisKeys.rooms(clock.today());
            redisTemplate.opsForSet().add(roomsKey, gameId);
            redisTemplate.expireAt(roomsKey, RoomLifecycleJob.roomsSetExpireAt(clock));
            log.info("채팅방 지연 생성 gameId={}", gameId);
        } catch (DataAccessException e) {
            log.warn("채팅방 지연 생성 실패 gameId={}", gameId, e);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        }
    }
}
