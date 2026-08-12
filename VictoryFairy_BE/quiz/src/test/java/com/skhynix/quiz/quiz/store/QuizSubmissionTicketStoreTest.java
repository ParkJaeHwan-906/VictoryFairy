package com.skhynix.quiz.quiz.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore.Ticket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link QuizSubmissionTicketStore} 단위 테스트 — {@link StringRedisTemplate}을 목으로 대체한다(저장소
 * 전체에 embedded Redis 가 없다는 제약은 요구사항 문서 "테스트 환경 제약" 참고 — Redis 계열은 슬라이스·목
 * 기반 검증만 가능하다).
 *
 * <p>{@code issue()}의 파이프라인 콜백은 실제 {@link RedisConnection}을 태워야 커맨드 인자를 검증할 수
 * 있으므로, {@code executePipelined} 스텁이 콜백을 직접 호출해 준다({@link #givenPipelineExecutesCallback()}).
 */
@ExtendWith(MockitoExtension.class)
class QuizSubmissionTicketStoreTest {

    private static final long USER_ID = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnection connection;

    @Mock
    private RedisStringCommands stringCommands;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private QuizSubmissionTicketStore ticketStore;

    @BeforeEach
    void setUp() {
        ticketStore = new QuizSubmissionTicketStore(redisTemplate);
    }

    @SuppressWarnings("unchecked")
    private void givenPipelineExecutesCallback() {
        given(connection.stringCommands()).willReturn(stringCommands);
        given(redisTemplate.executePipelined(any(RedisCallback.class))).willAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return List.of();
        });
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- issue(): 왕복 횟수(AC-INN-36) ----------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("[AC-INN-36-1] 항목 1건이든 20건이든 파이프라인 실행(왕복)은 정확히 1회다")
    void issue_oneAndTwentyItems_executesPipelineExactlyOnce() {
        givenPipelineExecutesCallback();

        ticketStore.issue(USER_ID, Map.of(1L, 6));
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));

        Map<Long, Integer> twenty = IntStream.rangeClosed(1, 20).boxed()
                .collect(Collectors.toMap(i -> (long) i, i -> i, (a, b) -> a, LinkedHashMap::new));
        ticketStore.issue(USER_ID + 1, twenty);
        // 누적 2회(호출당 1회) — 항목 수(1 vs 20)가 왕복 횟수를 늘리지 않는다는 것이 핵심
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("발급 대상이 없으면(빈 맵) Redis를 아예 호출하지 않는다")
    void issue_emptyMap_doesNotTouchRedis() {
        ticketStore.issue(USER_ID, Map.of());

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    // ---------- issue(): 키·값 포맷, TTL(AC-INN-11,12,36-2,40) ----------

    @Test
    @DisplayName("[AC-INN-12-1,12-3,11-2] 키는 quiz:inning:{계정}:{문제}, 값은 이닝의 문자열 표현이며 "
            + "TTL은 480초(8분)다")
    void issue_setsKeyValueAndTtl() {
        givenPipelineExecutesCallback();

        ticketStore.issue(USER_ID, Map.of(23L, 6));

        verify(stringCommands).setEx(bytes("quiz:inning:7:23"), 480L, bytes("6"));
    }

    @Test
    @DisplayName("[AC-INN-40-1,40-2] 이닝을 확보하지 못한 문제도 티켓은 발급되며 값은 미상 마커(\"-\")다")
    void issue_unknownInning_usesMarkerValueButStillIssuesTicket() {
        givenPipelineExecutesCallback();

        Map<Long, Integer> withUnknown = new LinkedHashMap<>();
        withUnknown.put(24L, null);
        ticketStore.issue(USER_ID, withUnknown);

        verify(stringCommands).setEx(bytes("quiz:inning:7:24"), 480L, bytes("-"));
    }

    @Test
    @DisplayName("[AC-INN-36-2] 여러 건을 한 번에 발급해도 TTL은 항목마다 개별로 걸린다(일괄 전송이 "
            + "만료를 공유하지 않는다)")
    void issue_multipleItems_eachGetsOwnTtlCommand() {
        givenPipelineExecutesCallback();

        ticketStore.issue(USER_ID, Map.of(1L, 3, 2L, 7));

        verify(stringCommands, times(2)).setEx(any(byte[].class), eq(480L), any(byte[].class));
    }

    @Test
    @DisplayName("[AC-INN-14-1,14-2,14-3] 같은 문제를 다시 받으면(재발급) 같은 키에 새 값을 다시 쓰고 "
            + "TTL도 480초로 다시 걸린다 — 값이 마지막으로 받은 이닝으로 갱신되고 시한도 연장된다"
            + "(Q6 확정 계약: SET NX 등 조건부 쓰기로 '최초 값 유지'로 바뀌면 이 테스트가 잡는다)")
    void issue_calledAgainForSameQuiz_overwritesValueAndRefreshesTtl() {
        givenPipelineExecutesCallback();

        ticketStore.issue(USER_ID, Map.of(23L, 3)); // 최초: 3회에 받음
        ticketStore.issue(USER_ID, Map.of(23L, 5)); // 재수신: 5회에 다시 받음(TTL 갱신)

        // 같은 키(quiz:inning:7:23)에 두 번 모두 무조건 SETEX가 나간다 — 조건부 쓰기(SET NX 등)로
        // "최초 값만 유지"하는 구현으로 바뀌면 두 번째 호출에서 새 값이 안 실려 이 단언이 깨진다.
        verify(stringCommands).setEx(bytes("quiz:inning:7:23"), 480L, bytes("3"));
        verify(stringCommands).setEx(bytes("quiz:inning:7:23"), 480L, bytes("5"));
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    // ---------- find(): 자격 조회(AC-INN-18,29,40) ----------

    @Test
    @DisplayName("값이 숫자 문자열이면 그 이닝을 담은 Ticket을 반환한다")
    void find_numericValue_returnsTicketWithInning() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("quiz:inning:7:23")).willReturn("6");

        Optional<Ticket> result = ticketStore.find(USER_ID, 23L);

        assertThat(result).isPresent();
        assertThat(result.get().inning()).isEqualTo(6);
    }

    @Test
    @DisplayName("[AC-INN-40-3] 값이 미상 마커(\"-\")면 자격은 있으나 이닝은 null인 Ticket을 반환한다")
    void find_unknownMarker_returnsTicketWithNullInning() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("quiz:inning:7:24")).willReturn("-");

        Optional<Ticket> result = ticketStore.find(USER_ID, 24L);

        assertThat(result).isPresent();
        assertThat(result.get().inning()).isNull();
    }

    @Test
    @DisplayName("[AC-INN-29 기반] 키가 없으면(미발급 또는 시한 경과) 빈 Optional을 반환한다")
    void find_missingKey_returnsEmpty() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("quiz:inning:7:99")).willReturn(null);

        Optional<Ticket> result = ticketStore.find(USER_ID, 99L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("요구사항 미기재 경계: 우리가 쓰지 않은 형식의 값이 들어와도 예외 없이 자격은 인정하되 "
            + "이닝은 null로 흡수한다(방어적 설계 — 키 포맷이 바뀐 배포 직후 제출 전면 차단을 막기 위함)")
    void find_unparsableValue_returnsTicketWithNullInningInsteadOfThrowing() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("quiz:inning:7:23")).willReturn("garbage");

        Optional<Ticket> result = ticketStore.find(USER_ID, 23L);

        assertThat(result).isPresent();
        assertThat(result.get().inning()).isNull();
    }

    // ---------- delete(): 정리(AC-INN-21) ----------

    @Test
    @DisplayName("delete()는 정확히 그 (계정, 문제) 키 하나만 지운다")
    void delete_removesExactSingleKey() {
        ticketStore.delete(USER_ID, 23L);

        verify(redisTemplate).delete("quiz:inning:7:23");
    }
}
