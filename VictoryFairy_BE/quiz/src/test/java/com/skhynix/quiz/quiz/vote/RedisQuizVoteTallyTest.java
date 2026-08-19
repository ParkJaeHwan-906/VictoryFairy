package com.skhynix.quiz.quiz.vote;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisQuizVoteTally}의 명령 형태를 검증한다. {@link StringRedisTemplate}을 목으로 두고
 * {@code executePipelined}에 넘어온 {@link RedisCallback}을 목 {@link RedisConnection}으로 직접 실행시켜
 * 실제로 어떤 Redis 명령이 나가는지 확인한다 — 실제 Redis를 띄우는 통합 검증은 여기서 하지 않는다
 * (docker-runner 소관, {@code docs/requirements/quiz/quiz-vote-tally.md}).
 */
@ExtendWith(MockitoExtension.class)
class RedisQuizVoteTallyTest {

    private static final long DEFAULT_TTL_SECONDS = 43200L; // quiz.vote.ttl 기본값 12h

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnection connection;

    @Mock
    private RedisHashCommands hashCommands;

    @Mock
    private RedisKeyCommands keyCommands;

    private RedisQuizVoteTally tally;

    @BeforeEach
    void setUp() {
        tally = new RedisQuizVoteTally(redisTemplate, Duration.ofHours(12));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }

    /** executePipelined에 넘겨진 콜백을 캡처한다 — 아직 실행하지 않는다. */
    @SuppressWarnings("unchecked")
    private RedisCallback<Object> capturePipelinedCallback() {
        ArgumentCaptor<RedisCallback> captor = ArgumentCaptor.forClass(RedisCallback.class);
        verify(redisTemplate).executePipelined(captor.capture());
        return captor.getValue();
    }

    private void stubConnectionCommands() {
        given(connection.hashCommands()).willReturn(hashCommands);
        given(connection.keyCommands()).willReturn(keyCommands);
    }

    /** 캡처한 콜백을 목 커넥션으로 직접 실행해 명령이 실제로 어떻게 나가는지 관찰한다. */
    private void runPipelinedCallback() {
        stubConnectionCommands();
        capturePipelinedCallback().doInRedis(connection);
    }

    // ---------- 생성자: TTL 방어 ----------

    @Test
    @DisplayName("TTL이 0 이하면 생성 시점에 즉시 실패한다 — 조용히 표를 날리느니 기동을 막는다")
    void constructor_nonPositiveTtl_throwsImmediately() {
        assertThatCode(() -> new RedisQuizVoteTally(redisTemplate, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RedisQuizVoteTally(redisTemplate, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RedisQuizVoteTally(redisTemplate, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- increment(): 명령 형태 ----------

    @Test
    @DisplayName("[AC-VOTE-1-1,4-1,2-2] increment()는 quiz:votes:{quizId} 키의 보기 번호 필드 그대로에 "
            + "HINCRBY +1을 쌓는다(변형 없는 0-based 필드명)")
    void increment_buildsHIncrByOnQuizVotesKeyWithRawFieldName() {
        tally.increment(42L, 2);

        runPipelinedCallback();

        verify(hashCommands).hIncrBy(aryEq(bytes("quiz:votes:42")), aryEq(bytes("2")), eq(1L));
    }

    @Test
    @DisplayName("보기 번호 0도 필드 \"0\"으로 그대로 증가한다(1-based 오프셋 없음)")
    void increment_optionNoZero_incrementsFieldZero() {
        tally.increment(42L, 0);

        runPipelinedCallback();

        verify(hashCommands).hIncrBy(aryEq(bytes("quiz:votes:42")), aryEq(bytes("0")), eq(1L));
    }

    @Test
    @DisplayName("[AC-VOTE-29-1,29-2,20-1] increment()도 EXPIRE ... NX로 TTL을 건다 — 무조건 설정하는 "
            + "명령이 아니라 '없을 때만' 조건이 명령 자체에 실린다")
    void increment_appliesTtlWithNxCondition() {
        tally.increment(42L, 2);

        runPipelinedCallback();

        verify(keyCommands).expire(aryEq(bytes("quiz:votes:42")), eq(DEFAULT_TTL_SECONDS),
                eq(ExpirationOptions.Condition.NX));
    }

    @Test
    @DisplayName("[AC-VOTE-32-1,19-1] TTL 초는 리터럴이 아니라 생성자로 주입된 Duration에서 파생된다")
    void increment_ttlSecondsDerivedFromInjectedDuration() {
        RedisQuizVoteTally customTtlTally = new RedisQuizVoteTally(redisTemplate, Duration.ofMinutes(90));

        customTtlTally.increment(42L, 0);
        stubConnectionCommands();
        capturePipelinedCallback().doInRedis(connection);

        verify(keyCommands).expire(any(), eq(5400L), any());
    }

    @Test
    @DisplayName("[AC-VOTE-3-1,16-1(구조)] increment() 경로에는 HSET·HSETNX·HDEL 같은 다른 쓰기 명령이 "
            + "없다 — HINCRBY 한 종류뿐이다")
    void increment_touchesNoOtherWriteCommands() {
        tally.increment(42L, 2);

        runPipelinedCallback();

        verify(hashCommands, never()).hSet(any(), any(), any());
        verify(hashCommands, never()).hSetNX(any(), any(), any());
    }

    // ---------- initialize(): "없을 때만 쓰기"(HSETNX) ----------

    @Test
    @DisplayName("[AC-VOTE-12-1,12-2] initialize()는 HSETNX만 쓰고, 무조건 덮어쓰는 HSET·HMSET은 전혀 "
            + "부르지 않는다 — 조회 후 분기(read-modify-write)가 아니라 단일 원자 명령이다")
    void initialize_usesOnlyHSetNXNeverHSetOrHMSet() {
        tally.initialize(Map.of(42L, List.of(0, 1, 2, 3)));

        runPipelinedCallback();

        verify(hashCommands, times(4)).hSetNX(any(), any(), any());
        verify(hashCommands, never()).hSet(any(), any(), any());
        verify(hashCommands, never()).hMSet(any(), any());
    }

    @Test
    @DisplayName("[AC-VOTE-2-1] 보기 4개짜리 문제의 필드 집합은 {\"0\",\"1\",\"2\",\"3\"}이다"
            + "(\"1\"~\"4\"가 아니다)")
    void initialize_fourOptions_usesZeroBasedFieldNames() {
        tally.initialize(Map.of(42L, List.of(0, 1, 2, 3)));

        runPipelinedCallback();

        ArgumentCaptor<byte[]> fieldCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(hashCommands, times(4)).hSetNX(any(), fieldCaptor.capture(), any());
        List<String> fields = fieldCaptor.getAllValues().stream()
                .map(field -> new String(field, UTF_8)).sorted().toList();
        assertThat(fields).containsExactly("0", "1", "2", "3");
    }

    @Test
    @DisplayName("initialize()가 심는 값은 항상 문자열 \"0\"이다")
    void initialize_seedsFieldValueZero() {
        tally.initialize(Map.of(42L, List.of(0)));

        runPipelinedCallback();

        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(hashCommands).hSetNX(any(), any(), valueCaptor.capture());
        assertThat(new String(valueCaptor.getValue(), UTF_8)).isEqualTo("0");
    }

    @Test
    @DisplayName("[AC-VOTE-29-1,20-1] initialize()도 EXPIRE ... NX로 TTL을 건다(증가 경로와 동일 시맨틱)")
    void initialize_appliesTtlWithNxCondition() {
        tally.initialize(Map.of(42L, List.of(0, 1)));

        runPipelinedCallback();

        verify(keyCommands).expire(aryEq(bytes("quiz:votes:42")), eq(DEFAULT_TTL_SECONDS),
                eq(ExpirationOptions.Condition.NX));
    }

    @Test
    @DisplayName("[AC-VOTE-14-1] 문제 3건(보기 4개=12필드)이든 20건(보기 4개=80필드)이든 executePipelined "
            + "호출은 각각 1회다 — 명령 수는 늘어도 왕복(RTT)은 상수")
    void initialize_manyQuizzes_stillOneRoundTripPerCall() {
        Map<Long, List<Integer>> threeQuizzes = Map.of(
                1L, List.of(0, 1, 2, 3), 2L, List.of(0, 1, 2, 3), 3L, List.of(0, 1, 2, 3));
        tally.initialize(threeQuizzes);
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));

        Map<Long, List<Integer>> twentyQuizzes = new HashMap<>();
        for (long id = 100; id < 120; id++) {
            twentyQuizzes.put(id, List.of(0, 1, 2, 3));
        }
        tally.initialize(twentyQuizzes);
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("빈 맵이나 null이면 executePipelined 자체를 부르지 않는다(왕복 0)")
    void initialize_emptyOrNullMap_skipsRoundTripEntirely() {
        tally.initialize(Map.of());
        tally.initialize(null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("보기 목록이 비었거나 null인 문제는 필드를 만들지 않는다(방어적 스킵)")
    void initialize_emptyOrNullOptionList_skipsThatQuiz() {
        Map<Long, List<Integer>> map = new HashMap<>();
        map.put(42L, List.of());
        map.put(43L, null);

        tally.initialize(map);
        // 두 문제 모두 조기 스킵돼 connection.hashCommands()/keyCommands() 자체가 호출되지 않는다 —
        // 그 목을 스텁해 두면 "쓰이지 않는 스텁"으로 strict stubbing 이 실패하므로 여기서는 스텁하지 않는다.
        capturePipelinedCallback().doInRedis(connection);

        verify(hashCommands, never()).hSetNX(any(), any(), any());
    }

    // ---------- 프로파일 무관(QUIZ-VOTE-30) ----------

    @Test
    @DisplayName("[AC-VOTE-30-1,30-2] RedisQuizVoteTally에는 @Profile 분기가 없다 — dev·prod 동일하게 "
            + "활성화된다")
    void redisQuizVoteTally_hasNoProfileAnnotation() {
        assertThat(RedisQuizVoteTally.class.getAnnotation(Profile.class)).isNull();
    }

    // ---------- Redis 장애 삼킴(QUIZ-VOTE-23/24/25) ----------

    @Test
    @DisplayName("[AC-VOTE-23-1,25-2] increment()는 executePipelined가 던지는 예외를 삼키고 호출자에게 "
            + "전파하지 않는다")
    void increment_swallowsRedisFailureWithoutPropagating() {
        willThrow(new RuntimeException("redis down"))
                .given(redisTemplate).executePipelined(any(RedisCallback.class));

        assertThatCode(() -> tally.increment(42L, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[AC-VOTE-24-1,25-2] initialize()도 executePipelined가 던지는 예외를 삼킨다")
    void initialize_swallowsRedisFailureWithoutPropagating() {
        willThrow(new RuntimeException("redis down"))
                .given(redisTemplate).executePipelined(any(RedisCallback.class));

        assertThatCode(() -> tally.initialize(Map.of(42L, List.of(0, 1))))
                .doesNotThrowAnyException();
    }
}
