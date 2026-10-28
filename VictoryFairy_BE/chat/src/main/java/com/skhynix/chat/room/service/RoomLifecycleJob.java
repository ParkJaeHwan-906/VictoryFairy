package com.skhynix.chat.room.service;

import com.skhynix.chat.shared.ChatClock;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 방 수명 작업 한 바퀴: 어제 방 정리(CHAT-GC-16) → 오늘 방 생성(CHAT-GC-17).
 * 전부 멱등이라(UNLINK·SET NX·SADD·EXPIREAT) 여러 파드가 동시에 돌아도 결과가 같다(CHAT-GC-20).
 * 실패는 그대로 던진다. 재시도는 {@link RoomLifecycleScheduler} 가 맡는다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RoomLifecycleJob {

    private final StringRedisTemplate redisTemplate;
    private final GameRepository gameRepository;
    private final ChatClock clock;

    public void run() {
        LocalDate today = clock.today();
        cleanup(today.minusDays(1));
        createRooms(today);
    }

    /**
     * 오늘 방 집합의 만료 시각 = 오늘 00:00 KST + 48h. 절대 시각이라 지연 생성이 몇 번 다시 걸어도 늘어나지 않고,
     * 다음 날 자정 정리가 이 집합을 읽을 때까지는 반드시 살아 있다.
     */
    static Instant roomsSetExpireAt(ChatClock clock) {
        return clock.today().atStartOfDay(ChatClock.ZONE).toInstant().plus(ChatRedisKeys.ROOMS_SET_TTL);
    }

    // 집합에 적힌 방만 이름으로 지운다. KEYS/SCAN 금지(CHAT-GC-16).
    private void cleanup(LocalDate day) {
        String roomsKey = ChatRedisKeys.rooms(day);
        Set<String> gameIds = redisTemplate.opsForSet().members(roomsKey);
        if (gameIds != null && !gameIds.isEmpty()) {
            List<String> keys = new ArrayList<>(gameIds.size() * 3);
            for (String gameId : gameIds) {
                keys.add(ChatRedisKeys.room(gameId));
                keys.add(ChatRedisKeys.stream(gameId));
                keys.add(ChatRedisKeys.blind(gameId));
            }
            redisTemplate.unlink(keys);
        }
        // 집합 키는 마지막에 지운다. 중간에 실패하면 재시도가 같은 목록으로 다시 지울 수 있어야 한다.
        redisTemplate.unlink(roomsKey);
        log.info("어제 채팅방 정리 date={} rooms={}", day, gameIds == null ? 0 : gameIds.size());
    }

    private void createRooms(LocalDate today) {
        List<String> gameIds = gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                        clock.startOfToday(), clock.startOfTomorrow())
                .stream()
                .map(Game::getNaverGameId)
                .filter(Objects::nonNull)
                .toList();
        if (gameIds.isEmpty()) {
            log.info("오늘 경기가 없어 채팅방을 만들지 않는다 date={}", today);
            return;
        }
        String meta = ChatClock.roomDate(today);
        for (String gameId : gameIds) {
            // NX: 이미 있으면 메타를 리셋하지 않는다(기동 시 재실행 포함, CHAT-GC-19).
            redisTemplate.opsForValue().setIfAbsent(ChatRedisKeys.room(gameId), meta, ChatRedisKeys.ROOM_META_TTL);
        }
        String roomsKey = ChatRedisKeys.rooms(today);
        redisTemplate.opsForSet().add(roomsKey, gameIds.toArray(String[]::new));
        redisTemplate.expireAt(roomsKey, roomsSetExpireAt(clock));
        log.info("오늘 채팅방 생성 date={} rooms={}", today, gameIds.size());
    }
}
