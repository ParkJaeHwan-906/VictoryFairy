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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisQuizVoteTally}의 명령 형태·응답 조립을 검증한다. {@link StringRedisTemplate}을 목으로 두고
 * 두 갈래로 확인한다 — ① {@code executePipelined}에 넘어온 {@link RedisCallback}을 목
 * {@link RedisConnection}으로 직접 실행시켜 실제로 어떤 Redis 명령이 나가는지(HSETNX·HINCRBY·EXPIRE) ②
 * {@code executePipelined}의 반환값을 직접 스텁해 {@code initializeAndRead}가 그 결과를 어떻게 문제별
 * 분포로 조립하는지(0-based 매핑·부분 결손·개수 불일치 포기·파싱 실패). 실제 Redis를 띄우는 통합 검증은
 * 여기서 하지 않는다(docker-runner 소관, {@code docs/requirements/quiz/quiz-vote-tally.md}·
 * {@code quiz-vote-exposure.md}).
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
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        tally = new RedisQuizVoteTally(redisTemplate, Duration.ofHours(12));
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(RedisQuizVoteTally.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(RedisQuizVoteTally.class)).detachAppender(logAppender);
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

    private long warnCount() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
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

    // ---------- initializeAndRead(): 명령 형태("없을 때만 쓰기" — HSETNX) ----------

    @Test
    @DisplayName("[AC-VOTE-12-1,12-2] initializeAndRead()는 HSETNX만 쓰고, 무조건 덮어쓰는 HSET·HMSET은 "
            + "전혀 부르지 않는다 — 조회 후 분기(read-modify-write)가 아니라 단일 원자 명령이다")
    void initializeAndRead_usesOnlyHSetNXNeverHSetOrHMSet() {
        tally.initializeAndRead(Map.of(42L, List.of(0, 1, 2, 3)));

        runPipelinedCallback();

        verify(hashCommands, times(4)).hSetNX(any(), any(), any());
        verify(hashCommands, never()).hSet(any(), any(), any());
        verify(hashCommands, never()).hMSet(any(), any());
    }

    @Test
    @DisplayName("[AC-VOTE-2-1] 보기 4개짜리 문제의 필드 집합은 {\"0\",\"1\",\"2\",\"3\"}이다"
            + "(\"1\"~\"4\"가 아니다)")
    void initializeAndRead_fourOptions_usesZeroBasedFieldNames() {
        tally.initializeAndRead(Map.of(42L, List.of(0, 1, 2, 3)));

        runPipelinedCallback();

        ArgumentCaptor<byte[]> fieldCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(hashCommands, times(4)).hSetNX(any(), fieldCaptor.capture(), any());
        List<String> fields = fieldCaptor.getAllValues().stream()
                .map(field -> new String(field, UTF_8)).sorted().toList();
        assertThat(fields).containsExactly("0", "1", "2", "3");
    }

    @Test
    @DisplayName("initializeAndRead()가 심는 값은 항상 문자열 \"0\"이다")
    void initializeAndRead_seedsFieldValueZero() {
        tally.initializeAndRead(Map.of(42L, List.of(0)));

        runPipelinedCallback();

        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(hashCommands).hSetNX(any(), any(), valueCaptor.capture());
        assertThat(new String(valueCaptor.getValue(), UTF_8)).isEqualTo("0");
    }

    @Test
    @DisplayName("[AC-VOTE-29-1,20-1] initializeAndRead()도 EXPIRE ... NX로 TTL을 건다(증가 경로와 동일 "
            + "시맨틱)")
    void initializeAndRead_appliesTtlWithNxCondition() {
        tally.initializeAndRead(Map.of(42L, List.of(0, 1)));

        runPipelinedCallback();

        verify(keyCommands).expire(aryEq(bytes("quiz:votes:42")), eq(DEFAULT_TTL_SECONDS),
                eq(ExpirationOptions.Condition.NX));
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-10-1] HSETNX -> EXPIRE NX -> HGETALL 순서로 명령을 쌓는다 — 초기화가 끝난 "
            + "뒤에 읽어야 아무도 안 고른 보기도 0으로 실리는 계약이 구조적으로 보장된다")
    void initializeAndRead_queuesHGetAllAfterInitializationAndExpire() {
        tally.initializeAndRead(Map.of(42L, List.of(0, 1)));

        runPipelinedCallback();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(hashCommands, keyCommands);
        inOrder.verify(hashCommands, times(2)).hSetNX(any(), any(), any());
        inOrder.verify(keyCommands).expire(any(), eq(DEFAULT_TTL_SECONDS), any());
        inOrder.verify(hashCommands).hGetAll(aryEq(bytes("quiz:votes:42")));
    }

    @Test
    @DisplayName("[AC-VOTE-14-1] 문제 3건(보기 4개=12필드)이든 20건(보기 4개=80필드)이든 executePipelined "
            + "호출은 각각 1회다 — 명령 수는 늘어도 왕복(RTT)은 상수")
    void initializeAndRead_manyQuizzes_stillOneRoundTripPerCall() {
        Map<Long, List<Integer>> threeQuizzes = Map.of(
                1L, List.of(0, 1, 2, 3), 2L, List.of(0, 1, 2, 3), 3L, List.of(0, 1, 2, 3));
        tally.initializeAndRead(threeQuizzes);
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));

        Map<Long, List<Integer>> twentyQuizzes = new HashMap<>();
        for (long id = 100; id < 120; id++) {
            twentyQuizzes.put(id, List.of(0, 1, 2, 3));
        }
        tally.initializeAndRead(twentyQuizzes);
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("빈 맵이나 null이면 executePipelined 자체를 부르지 않는다(왕복 0) — 반환값도 빈 맵이다")
    void initializeAndRead_emptyOrNullMap_skipsRoundTripEntirely() {
        assertThat(tally.initializeAndRead(Map.of())).isEmpty();
        assertThat(tally.initializeAndRead(null)).isEmpty();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("보기 목록이 전부 비었거나 null인 문제뿐이면 정규화 후 대상이 없어 왕복 자체가 발생하지 "
            + "않는다(방어적 스킵)")
    void initializeAndRead_allEmptyOrNullOptionLists_skipsRoundTripEntirely() {
        Map<Long, List<Integer>> map = new HashMap<>();
        map.put(42L, List.of());
        map.put(43L, null);

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(map);

        assertThat(result).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("보기 목록이 비었거나 null인 문제가 섞여 있으면 그 문제만 걸러지고 유효한 문제만 "
            + "명령이 나간다")
    void initializeAndRead_mixedValidAndInvalidOptionLists_skipsOnlyInvalidQuiz() {
        Map<Long, List<Integer>> map = new LinkedHashMap<>();
        map.put(42L, List.of()); // 스킵 대상
        map.put(43L, null); // 스킵 대상
        map.put(44L, List.of(0, 1)); // 유효

        tally.initializeAndRead(map);

        runPipelinedCallback();

        verify(hashCommands, times(2)).hSetNX(aryEq(bytes("quiz:votes:44")), any(), any());
        verify(hashCommands, never()).hSetNX(aryEq(bytes("quiz:votes:42")), any(), any());
    }

    // ---------- 프로파일 무관(QUIZ-VOTE-30) ----------

    @Test
    @DisplayName("[AC-VOTE-30-1,30-2] RedisQuizVoteTally에는 @Profile 분기가 없다 — dev·prod 동일하게 "
            + "활성화된다")
    void redisQuizVoteTally_hasNoProfileAnnotation() {
        assertThat(RedisQuizVoteTally.class.getAnnotation(Profile.class)).isNull();
    }

    // ---------- Redis 장애 삼킴(QUIZ-VOTE-23/24/25, QUIZ-VOTEVIEW-20/21/25) ----------

    @Test
    @DisplayName("[AC-VOTE-23-1,25-2] increment()는 executePipelined가 던지는 예외를 삼키고 호출자에게 "
            + "전파하지 않는다")
    void increment_swallowsRedisFailureWithoutPropagating() {
        willThrow(new RuntimeException("redis down"))
                .given(redisTemplate).executePipelined(any(RedisCallback.class));

        assertThatCode(() -> tally.increment(42L, 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-20-1,21-1,25-1,25-2] initializeAndRead()가 예외를 삼키면 빈 맵을 반환하고 "
            + "WARN 로그가 정확히 1건 남는다(호출자에게 전파되지 않는다) — 호출부는 이 빈 맵을 전 보기 0으로 "
            + "채운다")
    void initializeAndRead_swallowsRedisFailure_returnsEmptyMapAndLogsWarnOnce() {
        willThrow(new RuntimeException("redis down"))
                .given(redisTemplate).executePipelined(any(RedisCallback.class));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(42L, List.of(0, 1)));

        assertThat(result).isEmpty();
        assertThat(warnCount()).isEqualTo(1);
    }

    // ---------- 응답 조립(QUIZ-VOTEVIEW-9/22/23/24) — executePipelined 반환값을 직접 스텁 ----------

    @Test
    @DisplayName("[AC-VOTEVIEW-9-1,9-2] 필드 이름은 no와 같은 0-based 축으로 그대로 매핑된다 — 문제 둘의 "
            + "필드에 서로 다른 값을 심어 한 칸이라도 밀리면 잡히게 한다")
    void initializeAndRead_mapsFieldsToZeroBasedOptionNumbersWithoutShifting() {
        Map<Long, List<Integer>> targets = new LinkedHashMap<>();
        targets.put(1L, List.of(0, 1));
        targets.put(2L, List.of(0, 1, 2, 3));
        given(redisTemplate.executePipelined(any(RedisCallback.class))).willReturn(List.of(
                Map.of("0", "5", "1", "9"),
                Map.of("0", "1", "1", "2", "2", "3", "3", "4")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(targets);

        assertThat(result.get(1L)).containsEntry(0, 5L).containsEntry(1, 9L);
        // quiz2의 필드 "3"이 값 4를 갖는다 — 밀렸다면 quiz1의 값(5,9)이나 엉뚱한 no에 붙는다
        assertThat(result.get(2L)).containsEntry(0, 1L).containsEntry(1, 2L)
                .containsEntry(2, 3L).containsEntry(3, 4L);
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-9-2] O/X 문제의 필드 \"0\"은 text=\"O\" 보기(no=0)에 대응한다는 축 자체는 "
            + "응답 조립 쪽(QuizResponse) 책임이지만, 여기서는 필드 \"0\"이 no=0으로 매핑됨을 고정한다")
    void initializeAndRead_oxQuiz_fieldZeroMapsToOptionZero() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("0", "3", "1", "1")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(1L, List.of(0, 1)));

        assertThat(result.get(1L)).containsEntry(0, 3L).containsEntry(1, 1L);
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-23-1] 보기 4개 문제의 해시에 필드 \"1\"만 있으면 반환 맵에도 \"1\"만 담긴다 "
            + "— no=0,2,3의 0 채움은 호출부(QuizService)의 몫이라 여기서는 없는 필드를 만들어 채우지 않는다")
    void initializeAndRead_partialHashFields_returnsOnlyPresentFields() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("1", "7")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(42L, List.of(0, 1, 2, 3)));

        assertThat(result.get(42L)).containsOnly(java.util.Map.entry(1, 7L));
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-24-1] 값이 정수로 해석되지 않으면(예: \"abc\") 그 항목만 버리고 나머지는 "
            + "정상 값을 유지한다")
    void initializeAndRead_nonNumericValue_dropsThatFieldOnly() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("0", "abc", "1", "5")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(1L, List.of(0, 1)));

        assertThat(result.get(1L)).containsOnly(java.util.Map.entry(1, 5L));
    }

    @Test
    @DisplayName("음수 값도 0 이상의 정수로 해석되지 않는 것으로 취급해 그 항목을 버린다")
    void initializeAndRead_negativeValue_dropsThatFieldOnly() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("0", "-3", "1", "5")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(1L, List.of(0, 1)));

        assertThat(result.get(1L)).containsOnly(java.util.Map.entry(1, 5L));
    }

    @Test
    @DisplayName("필드 이름이 정수로 해석되지 않으면(오염된 데이터) 그 필드를 버린다")
    void initializeAndRead_nonNumericFieldName_dropsThatField() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("x", "5", "1", "7")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(1L, List.of(0, 1)));

        assertThat(result.get(1L)).containsOnly(java.util.Map.entry(1, 7L));
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-quiz-count-mismatch] 파이프라인 결과의 해시 개수가 요청한 문제 수와 다르면 "
            + "부분 매핑하지 않고 통째로 포기한다(빈 맵) — WARN 로그도 1건 남는다")
    void initializeAndRead_resultCountMismatch_givesUpEntirelyAndLogsWarnOnce() {
        Map<Long, List<Integer>> targets = new LinkedHashMap<>();
        targets.put(1L, List.of(0, 1));
        targets.put(2L, List.of(0, 1));
        // 문제 2건을 요청했는데 해시가 1개만 온다(개수 불일치)
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of("0", "5", "1", "9")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(targets);

        assertThat(result).isEmpty();
        assertThat(warnCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("파이프라인 결과에 Map이 아닌 원소(HSETNX·EXPIRE의 Boolean 응답 등)가 섞여 있어도 Map만 "
            + "골라 순서대로 문제와 짝짓는다")
    void initializeAndRead_ignoresNonMapResultsAndPicksHashesInOrder() {
        Map<Long, List<Integer>> targets = new LinkedHashMap<>();
        targets.put(1L, List.of(0, 1));
        targets.put(2L, List.of(0, 1));
        given(redisTemplate.executePipelined(any(RedisCallback.class))).willReturn(List.of(
                true, true, Map.of("0", "1", "1", "2"),
                true, true, Map.of("0", "3", "1", "4")));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(targets);

        assertThat(result.get(1L)).containsEntry(0, 1L).containsEntry(1, 2L);
        assertThat(result.get(2L)).containsEntry(0, 3L).containsEntry(1, 4L);
    }

    @Test
    @DisplayName("완전히 비어 있는(누구도 안 고른) 해시는 빈 맵으로 반환된다 — 호출부가 없는 값을 0으로 "
            + "채운다(첫 서빙 시나리오, AC-VOTEVIEW-10-1)")
    void initializeAndRead_emptyHash_returnsEmptyMapForThatQuiz() {
        given(redisTemplate.executePipelined(any(RedisCallback.class)))
                .willReturn(List.of(Map.of()));

        Map<Long, Map<Integer, Long>> result = tally.initializeAndRead(Map.of(1L, List.of(0, 1)));

        assertThat(result).doesNotContainKey(1L);
    }
}
