package com.skhynix.quiz.quizroom.service;

import com.skhynix.quiz.quizroom.realtime.QuizRoomPresenceStore;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REST 응답의 {@code solvingCount}. SSE {@code presence} 와 같은 Redis 집계를 읽는다(파드 로컬 구독 수가 아니다).
 *
 * <p>Redis 실패는 삼키고 0으로 채운다 — 목록·상세가 Redis 장애로 500 이 되지 않게 한다. 대가로 그동안의 0은
 * "아무도 없다"와 "못 읽었다"를 구별하지 못한다(WARN 로그로만 구별).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class QuizRoomSolvingCounter {

    private final QuizRoomPresenceStore presenceStore;

    public Map<Long, Long> counts(Collection<Long> roomIds) {
        try {
            return presenceStore.countMany(roomIds);
        } catch (RuntimeException e) {
            log.warn("퀴즈방 풀이 중 인원 조회 실패 — 0 으로 응답한다 rooms={}", roomIds.size(), e);
            return Map.of();
        }
    }

    public long count(long roomId) {
        return counts(Set.of(roomId)).getOrDefault(roomId, 0L);
    }
}
