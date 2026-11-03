package com.skhynix.quiz.quizroom.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 인원 저장소의 <b>인자 조립</b>만 본다. 만료 창(45초)·고유 계정 합산·소유자 비교 같은 Lua 의미는 실제 Redis 가 필요해
 * 여기서 검증하지 못한다(QUIZ-CQR-83, 86 은 미커버로 보고).
 */
@ExtendWith(MockitoExtension.class)
class QuizRoomPresenceStoreTest {

    @Mock
    private StringRedisTemplate redis;

    private QuizRoomPresenceStore store;

    @BeforeEach
    void setUp() {
        store = new QuizRoomPresenceStore(redis, 45, 300);
    }

    /** 스크립트 실행 호출의 (keys, 가변 인자) 를 꺼낸다 - 가변 인자는 펼쳐진 채 기록된다. */
    @SuppressWarnings("unchecked")
    private Object[] lastScriptInvocation() {
        return org.mockito.Mockito.mockingDetails(redis).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .reduce((first, second) -> second).orElseThrow().getArguments();
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysOf(Object[] invocationArguments) {
        return (List<String>) invocationArguments[1];
    }

    private static List<Object> scriptArgsOf(Object[] invocationArguments) {
        return List.of(invocationArguments).subList(2, invocationArguments.length);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("[QUIZ-CQR-83] join 은 방별 키(quiz-room: 접두)와 [instanceId, 덮어쓰기=1, 만료 45000ms, 키 TTL 300초, 계정 id...] 인자로 스크립트를 실행하고 인원을 반환한다")
    void join_buildsKeysAndArguments() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);

        long count = store.join(5L, List.of(7L, 8L), "pod-a");

        assertThat(count).isEqualTo(2L);
        Object[] call = lastScriptInvocation();
        assertThat(keysOf(call)).containsExactly("quiz-room:{5}:presence", "quiz-room:{5}:presence-owner");
        assertThat(scriptArgsOf(call)).containsExactly("pod-a", "1", "45000", "300", "7", "8");
        verify(redis, never()).opsForSet();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("[QUIZ-CQR-83] refresh 는 소유자를 빼앗지 않도록 덮어쓰기=0 으로 실행한다")
    void refresh_doesNotOverwriteOwner() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        store.refresh(5L, List.of(7L), "pod-a");

        assertThat(scriptArgsOf(lastScriptInvocation()).get(1)).isEqualTo("0");
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] 퇴장 대상 계정이 없으면 Redis 를 부르지 않는다")
    void leave_emptyUsers_noRedisCall() {
        store.leave(5L, List.of(), "pod-a");

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] leave 는 [instanceId, 계정 id...] 를 같은 방 키로 넘긴다 (소유자가 자기 파드일 때만 지우는 판정은 스크립트 몫)")
    void leave_buildsArguments() {
        store.leave(5L, List.of(7L), "pod-a");

        Object[] call = lastScriptInvocation();
        assertThat(keysOf(call)).containsExactly("quiz-room:{5}:presence", "quiz-room:{5}:presence-owner");
        assertThat(scriptArgsOf(call)).containsExactly("pod-a", "7");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("count 는 Redis 가 null 을 돌려주면 0 이다")
    void count_nullFromRedis_isZero() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object.class))).thenReturn(null);

        assertThat(store.count(5L)).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("[QUIZ-CQR-102] 인원 키는 전부 quiz-room: 접두이고 오늘의 퀴즈 투표 키(quiz:votes:)와 겹치지 않는다")
    void keys_neverUseLegacyVoteKeyPrefix() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        store.join(5L, List.of(7L), "pod-a");

        assertThat(keysOf(lastScriptInvocation())).allSatisfy(key ->
                assertThat(key).startsWith("quiz-room:").doesNotStartWith("quiz:votes:"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("[QUIZ-CQR-33 폐기] 활성 방 집합(presence-rooms)을 더 이상 쓰지 않아 join 이 Redis Set 을 건드리지 않는다")
    void join_doesNotTouchRoomRegistrySet() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        store.join(5L, List.of(7L), "pod-a");

        verify(redis, never()).opsForSet();
    }

    @Test
    @DisplayName("[QUIZ-CQR-37] countMany 는 방이 없으면 Redis 왕복 없이 빈 맵이다")
    void countMany_empty_noRoundTrip() {
        assertThat(store.countMany(List.of())).isEmpty();

        verify(redis, never()).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        verify(redis, never()).executePipelined(any(org.springframework.data.redis.core.SessionCallback.class));
    }
}
