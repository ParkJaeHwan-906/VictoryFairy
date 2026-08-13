package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quiz.entity.QuizLike;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 별도 빈으로 뽑은 이유는 순전히 트랜잭션 경계 때문이다. UNIQUE 충돌({@code DataIntegrityViolationException})
 * 이 나면 그 트랜잭션은 이미 롤백 표시가 붙어 <b>같은 트랜잭션에서는 아무것도 더 읽을 수 없다</b>(커밋하면
 * {@code UnexpectedRollbackException}). 확정 상태 재조회는 반드시 새 트랜잭션이어야 하고, 같은 빈 안에서
 * 자기 메서드를 부르면 프록시를 지나지 않아 새 트랜잭션이 열리지 않는다. 그래서 "충돌을 흡수하는 쪽"과
 * "트랜잭션을 여는 쪽"이 서로 다른 빈에 있다.
 */
@Service
@RequiredArgsConstructor
class QuizLikeToggler {

    private final QuizLikeRepository quizLikeRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final QuizRepository quizRepository;
    private final UserAccountRepository userAccountRepository;

    @Transactional
    public QuizLikeResponse toggleOnce(Long userAccountId, Long quizId) {
        if (!quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(userAccountId, quizId)) {
            throw new BusinessException(ErrorCode.QUIZ_LIKE_NOT_ALLOWED);
        }

        QuizLike like = quizLikeRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .orElse(null);
        if (like == null) {
            // 신규 행은 빌더가 켜진 상태로만 만든다(엔티티 계약) — 여기서 켜기/끄기를 계산하지 않는다.
            like = quizLikeRepository.save(QuizLike.builder()
                    .userAccount(userAccountRepository.getReferenceById(userAccountId))
                    .quiz(quizRepository.getReferenceById(quizId))
                    .build());
        } else {
            like.toggle();
        }
        return new QuizLikeResponse(like.isLiked(),
                quizLikeRepository.countByQuiz_IdAndLikedTrue(quizId));
    }

    @Transactional(readOnly = true)
    public QuizLikeResponse settledState(Long userAccountId, Long quizId) {
        QuizLike settled = quizLikeRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_LIKE_NOT_ALLOWED));
        return new QuizLikeResponse(settled.isLiked(),
                quizLikeRepository.countByQuiz_IdAndLikedTrue(quizId));
    }
}
