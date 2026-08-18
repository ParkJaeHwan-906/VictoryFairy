package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.quiz.repository.QuizLikeCountView;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link QuizLikeService}(파사드) 단위 테스트 — {@link QuizLikeRepository}·{@link QuizLikeToggler}는
 * 목, DB·Spring 컨텍스트 없음.
 *
 * <p>{@code toggleOnce}/{@code settledState}(트랜잭션 경계·403 판정) 자체는 {@link QuizLikeTogglerTest}가
 * 맡는다. 여기서는 <b>동시 충돌 흡수 위임</b>(AC-LIKE-11)과 <b>조회 경로 조립</b>
 * (likeOf/likesOf — N+1 금지·0건 흡수·likesOf 반환 맵 완전성)만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QuizLikeServiceTest {

    private static final Long USER_ID = 10L;

    @Mock
    private QuizLikeRepository quizLikeRepository;

    @Mock
    private QuizLikeToggler quizLikeToggler;

    private QuizLikeService quizLikeService;

    private QuizLikeCountView countView(long quizId, long count) {
        return new QuizLikeCountView() {
            @Override
            public Long getQuizId() {
                return quizId;
            }

            @Override
            public long getLikeCount() {
                return count;
            }
        };
    }

    // @BeforeEach 없이 각 테스트에서 직접 생성 — QuizLikeService 는 순수 위임/조립이라 상태가 없다.
    private void newService() {
        quizLikeService = new QuizLikeService(quizLikeRepository, quizLikeToggler);
    }

    // ---------- toggle: 정상 위임 + AC-LIKE-11 동시 충돌 흡수 ----------

    @Test
    @DisplayName("정상 토글은 QuizLikeToggler.toggleOnce의 결과를 그대로 반환한다")
    void toggle_delegatesToTogglerAndReturnsItsResult() {
        newService();
        given(quizLikeToggler.toggleOnce(USER_ID, 23L)).willReturn(new QuizLikeResponse(true, 5L));

        QuizLikeResponse result = quizLikeService.toggle(USER_ID, 23L);

        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[AC-LIKE-11-1] toggleOnce가 UNIQUE 충돌(DataIntegrityViolationException)을 던지면 "
            + "500이 아니라 settledState가 읽은 확정 상태를 200으로 반환한다")
    void toggle_uniqueViolation_fallsBackToSettledState() {
        newService();
        given(quizLikeToggler.toggleOnce(USER_ID, 23L))
                .willThrow(new DataIntegrityViolationException("uk_quizzes_like_account_quiz"));
        given(quizLikeToggler.settledState(USER_ID, 23L)).willReturn(new QuizLikeResponse(true, 1L));

        QuizLikeResponse result = quizLikeService.toggle(USER_ID, 23L);

        // 첫 좋아요의 동시 충돌에서 최종 상태는 liked=true여야 한다(요구사항 QUIZ-LIKE-11의 문구) —
        // 이 값 자체는 settledState 목의 반환값이므로, 여기서는 "재토글하지 않고 settledState 결과를
        // 그대로 쓴다"는 위임 계약을 고정한다.
        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1L);
        verify(quizLikeToggler).settledState(USER_ID, 23L);
        // 진 쪽이 재시도로 다시 토글해 이긴 쪽 상태를 뒤집으면 안 된다 — toggleOnce는 정확히 1회만.
        verify(quizLikeToggler, times(1)).toggleOnce(USER_ID, 23L);
    }

    // ---------- likeOf ----------

    @Test
    @DisplayName("likeOf는 집계 맵에 그 문제가 있으면 해당 값을 그대로 돌려준다")
    void likeOf_returnsMatchingEntryFromLikesOf() {
        newService();
        given(quizLikeRepository.countLikesByQuizIds(List.of(1L))).willReturn(List.of(countView(1L, 3L)));
        given(quizLikeRepository.findLikedQuizIds(USER_ID, List.of(1L))).willReturn(List.of(1L));

        QuizLikeResponse result = quizLikeService.likeOf(USER_ID, 1L);

        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("좋아요가 0인 문제(집계 결과에 없음)는 NPE 없이 {liked:false, likeCount:0}으로 흡수된다")
    void likeOf_quizWithZeroLikes_absorbsToNoneWithoutNpe() {
        newService();
        // group by 성질상 좋아요가 0인 문제는 countLikesByQuizIds 결과에 아예 나오지 않는다
        given(quizLikeRepository.countLikesByQuizIds(List.of(1L))).willReturn(List.of());
        given(quizLikeRepository.findLikedQuizIds(USER_ID, List.of(1L))).willReturn(List.of());

        QuizLikeResponse result = quizLikeService.likeOf(USER_ID, 1L);

        assertThat(result.liked()).isFalse();
        assertThat(result.likeCount()).isZero();
    }

    @Test
    @DisplayName("[AC-LIKE-32-2] 내가 좋아요한 적 없어도 다른 사용자의 좋아요는 likeCount에 반영된다"
            + "(liked=false, likeCount>0가 동시에 성립)")
    void likeOf_othersLikedButNotMe_returnsFalseLikedWithNonZeroCount() {
        newService();
        given(quizLikeRepository.countLikesByQuizIds(List.of(1L))).willReturn(List.of(countView(1L, 4L)));
        given(quizLikeRepository.findLikedQuizIds(USER_ID, List.of(1L))).willReturn(List.of());

        QuizLikeResponse result = quizLikeService.likeOf(USER_ID, 1L);

        assertThat(result.liked()).isFalse();
        assertThat(result.likeCount()).isEqualTo(4L);
    }

    // ---------- likesOf: N+1 금지 + 반환 맵 완전성 ----------

    @Test
    @DisplayName("[AC-LIKE-35-1] quizIds가 빈 컬렉션이면 빈 맵을 반환하고 리포지토리를 전혀 호출하지 않는다")
    void likesOf_emptyQuizIds_returnsEmptyMapWithoutRepositoryCalls() {
        newService();

        Map<Long, QuizLikeResponse> result = quizLikeService.likesOf(USER_ID, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(quizLikeRepository);
    }

    @Test
    @DisplayName("[AC-LIKE-35-1,2] quizId가 20개여도 집계·내 좋아요 조회는 각각 정확히 1회다"
            + "(N+1 금지 — 항목 수와 무관하게 고정 쿼리 수)")
    void likesOf_twentyQuizIds_callsEachRepositoryMethodExactlyOnce() {
        newService();
        List<Long> quizIds = LongStream.rangeClosed(1, 20).boxed().toList();
        given(quizLikeRepository.countLikesByQuizIds(quizIds)).willReturn(List.of());
        given(quizLikeRepository.findLikedQuizIds(USER_ID, quizIds)).willReturn(List.of());

        Map<Long, QuizLikeResponse> result = quizLikeService.likesOf(USER_ID, quizIds);

        assertThat(result).hasSize(20);
        verify(quizLikeRepository, times(1)).countLikesByQuizIds(quizIds);
        verify(quizLikeRepository, times(1)).findLikedQuizIds(eq(USER_ID), eq(quizIds));
    }

    @Test
    @DisplayName("[AC-LIKE-35-1,2] quizId가 1개여도(20개일 때와) 호출 횟수가 같다 — 각각 1회")
    void likesOf_oneQuizId_callsEachRepositoryMethodExactlyOnceSameAsTwenty() {
        newService();
        List<Long> quizIds = List.of(7L);
        given(quizLikeRepository.countLikesByQuizIds(quizIds)).willReturn(List.of(countView(7L, 2L)));
        given(quizLikeRepository.findLikedQuizIds(USER_ID, quizIds)).willReturn(List.of());

        Map<Long, QuizLikeResponse> result = quizLikeService.likesOf(USER_ID, quizIds);

        assertThat(result).hasSize(1);
        verify(quizLikeRepository, times(1)).countLikesByQuizIds(quizIds);
        verify(quizLikeRepository, times(1)).findLikedQuizIds(eq(USER_ID), eq(quizIds));
    }

    @Test
    @DisplayName("요청한 quizId 전부를 키로 채운다 — 집계·내 좋아요 어느 쪽에도 없는 문제는 "
            + "{liked:false, likeCount:0}으로 채워지고 맵에서 빠지지 않는다(NPE 방지)")
    void likesOf_fillsAllRequestedQuizIdsEvenWhenMissingFromBothQueries() {
        newService();
        List<Long> quizIds = List.of(1L, 2L, 3L);
        // 1번만 좋아요 5개, 2번은 집계에 없음(0개), 3번은 내가 좋아요 중이나 집계에도 잡힘
        given(quizLikeRepository.countLikesByQuizIds(quizIds))
                .willReturn(List.of(countView(1L, 5L), countView(3L, 2L)));
        given(quizLikeRepository.findLikedQuizIds(USER_ID, quizIds)).willReturn(List.of(3L));

        Map<Long, QuizLikeResponse> result = quizLikeService.likesOf(USER_ID, quizIds);

        assertThat(result).hasSize(3);
        assertThat(result.get(1L).liked()).isFalse();
        assertThat(result.get(1L).likeCount()).isEqualTo(5L);
        assertThat(result.get(2L).liked()).isFalse();
        assertThat(result.get(2L).likeCount()).isZero();
        assertThat(result.get(3L).liked()).isTrue();
        assertThat(result.get(3L).likeCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("QuizLikeToggler는 조회 경로(likeOf/likesOf)에서 전혀 호출되지 않는다"
            + "(조회는 리포지토리 직접 조립, 토글은 별도 경계)")
    void likesOf_neverInteractsWithToggler() {
        newService();
        given(quizLikeRepository.countLikesByQuizIds(List.of(1L))).willReturn(List.of());
        given(quizLikeRepository.findLikedQuizIds(USER_ID, List.of(1L))).willReturn(List.of());

        quizLikeService.likeOf(USER_ID, 1L);

        verify(quizLikeToggler, never()).toggleOnce(anyLong(), anyLong());
        verifyNoInteractions(quizLikeToggler);
    }
}
