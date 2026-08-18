package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizLike;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizLikeToggler}(트랜잭션 단위) 단위 테스트 — 협력 리포지토리 전부 목, DB·Spring 컨텍스트 없음.
 *
 * <p><b>거절 판정은 "제출 이력이 있는가" 한 번뿐이다</b>(QUIZ-LIKE-28) — 이 클래스 수준에서는 문제
 * 존재·편성 여부를 아예 조회하지 않으므로, 존재하지 않는 문제·미편성 풀 문제·편성됐지만 미제출한 문제
 * 세 경우가 전부 같은 한 줄({@code existsByUserAccount_IdAndQuiz_Id == false}) 분기에서 같은 예외로
 * 끝나는 것을 <b>메서드 하나로</b> 고정한다(세 경우를 구분하는 코드 경로 자체가 없다는 뜻).
 */
@ExtendWith(MockitoExtension.class)
class QuizLikeTogglerTest {

    private static final Long USER_ID = 10L;
    private static final Long QUIZ_ID = 23L;

    @Mock
    private QuizLikeRepository quizLikeRepository;

    @Mock
    private QuizUserSubmitRepository quizUserSubmitRepository;

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private QuizLikeToggler quizLikeToggler;

    private UserAccount account() {
        UserAccount account = UserAccount.builder().nickname("응시자").password("password1!").build();
        ReflectionTestUtils.setField(account, "id", USER_ID);
        return account;
    }

    private Quiz quiz() {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .content("문제")
                .answer(0)
                .build();
        ReflectionTestUtils.setField(quiz, "id", QUIZ_ID);
        return quiz;
    }

    private QuizLike likeRow(UserAccount account, Quiz quiz) {
        return QuizLike.builder().userAccount(account).quiz(quiz).build();
    }

    // ---------- AC-LIKE-28: 제출 이력 없음(존재하지 않는 문제·미편성 풀·미제출 세 경우가 여기 하나로 합쳐짐) ----------

    @Test
    @DisplayName("[AC-LIKE-28-1,2,3, AC-LIKE-28-4] 제출 이력이 없으면(사유 불문) 403 "
            + "QUIZ_LIKE_NOT_ALLOWED를 던지고 좋아요 조회·저장을 전혀 시도하지 않는다"
            + "(어떤 행도 만들거나 갱신되지 않는다)")
    void toggleOnce_noSubmission_throwsQuizLikeNotAllowedWithoutTouchingLikeRepository() {
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(false);

        assertThatThrownBy(() -> quizLikeToggler.toggleOnce(USER_ID, QUIZ_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_LIKE_NOT_ALLOWED));

        verify(quizLikeRepository, never()).findByUserAccount_IdAndQuiz_Id(any(), any());
        verify(quizLikeRepository, never()).save(any());
    }

    // ---------- AC-LIKE-6: 제출 이력 있고 좋아요 행 없음 -> 신규 생성(liked=true) ----------

    @Test
    @DisplayName("[AC-LIKE-6-1,2] 제출한 문제에 좋아요 행이 없으면 liked=true인 행을 새로 만들고 "
            + "그 상태로 응답한다")
    void toggleOnce_submittedNoExistingLike_createsLikedTrueRow() {
        UserAccount account = account();
        Quiz quiz = quiz();
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);
        given(quizLikeRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(USER_ID)).willReturn(account);
        given(quizRepository.getReferenceById(QUIZ_ID)).willReturn(quiz);
        given(quizLikeRepository.save(any(QuizLike.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(quizLikeRepository.countByQuiz_IdAndLikedTrue(QUIZ_ID)).willReturn(1L);

        QuizLikeResponse result = quizLikeToggler.toggleOnce(USER_ID, QUIZ_ID);

        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1L);
        verify(quizLikeRepository).save(any(QuizLike.class));
        // AC-LIKE-17-1,2: 제출 기록은 존재 확인(existsBy)만 하고 새로 쓰지 않으며, 계정 행 비관적 락도
        // 잡지 않는다(조회에는 getReferenceById 프록시만 쓴다) — 좋아요가 적립·제출 이력을 건드리지
        // 않는다는 구조적 근거
        verify(quizUserSubmitRepository, never()).save(any());
        verify(userAccountRepository, never()).findWithLockById(any());
    }

    // ---------- AC-LIKE-7: liked=true 상태 -> 같은 행을 false로 ----------

    @Test
    @DisplayName("[AC-LIKE-7-1,2] 이미 liked=true인 행이 있으면 새로 만들지 않고 그 행을 false로 뒤집는다")
    void toggleOnce_existingLikedTrue_flipsToFalseWithoutCreatingNewRow() {
        UserAccount account = account();
        Quiz quiz = quiz();
        QuizLike existing = likeRow(account, quiz); // builder 생성 직후는 항상 liked=true
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);
        given(quizLikeRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(existing));
        given(quizLikeRepository.countByQuiz_IdAndLikedTrue(QUIZ_ID)).willReturn(0L);

        QuizLikeResponse result = quizLikeToggler.toggleOnce(USER_ID, QUIZ_ID);

        assertThat(result.liked()).isFalse();
        assertThat(existing.isLiked()).isFalse();
        verify(quizLikeRepository, never()).save(any());
    }

    // ---------- AC-LIKE-9: liked=false 상태 -> 같은 행을 true로(UNIQUE 위반 없이) ----------

    @Test
    @DisplayName("[AC-LIKE-9-1] 이미 liked=false인 행이 있으면 그 행을 true로 되돌린다 "
            + "(새 행을 만들지 않으므로 UNIQUE 위반이 날 여지가 없다)")
    void toggleOnce_existingLikedFalse_flipsBackToTrue() {
        UserAccount account = account();
        Quiz quiz = quiz();
        QuizLike existing = likeRow(account, quiz);
        existing.toggle(); // true -> false (좋아요 취소된 상태를 재현)
        given(quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(true);
        given(quizLikeRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(existing));
        given(quizLikeRepository.countByQuiz_IdAndLikedTrue(QUIZ_ID)).willReturn(1L);

        QuizLikeResponse result = quizLikeToggler.toggleOnce(USER_ID, QUIZ_ID);

        assertThat(result.liked()).isTrue();
        assertThat(existing.isLiked()).isTrue();
        verify(quizLikeRepository, never()).save(any());
    }

    // ---------- settledState: 동시 충돌에서 진 요청이 재조회하는 확정 상태 ----------

    @Test
    @DisplayName("settledState는 재토글하지 않고 현재 저장된 상태를 그대로 읽어 반환한다")
    void settledState_readsCurrentStateWithoutTogglingAgain() {
        UserAccount account = account();
        Quiz quiz = quiz();
        QuizLike existing = likeRow(account, quiz); // liked=true (이긴 쪽이 방금 켠 상태)
        given(quizLikeRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.of(existing));
        given(quizLikeRepository.countByQuiz_IdAndLikedTrue(QUIZ_ID)).willReturn(1L);

        QuizLikeResponse result = quizLikeToggler.settledState(USER_ID, QUIZ_ID);

        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1L);
        // 재토글 금지: toggle() 호출 흔적이 없어야 하므로 liked 값이 그대로 true로 유지된다
        assertThat(existing.isLiked()).isTrue();
    }

    @Test
    @DisplayName("settledState가 행을 찾지 못하면(충돌 원인이 FK 삭제 등) 403 QUIZ_LIKE_NOT_ALLOWED로 접는다")
    void settledState_noRowFound_throwsQuizLikeNotAllowed() {
        given(quizLikeRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, QUIZ_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> quizLikeToggler.settledState(USER_ID, QUIZ_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_LIKE_NOT_ALLOWED));
    }
}
