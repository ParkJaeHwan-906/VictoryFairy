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
 * 좋아요 토글의 <b>트랜잭션 단위</b>. {@link QuizLikeService}만 호출한다.
 *
 * <p>별도 빈으로 뽑은 이유는 순전히 트랜잭션 경계 때문이다. UNIQUE 충돌({@code DataIntegrityViolationException})
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

    /**
     * 토글 한 번(없으면 켜진 행 생성, 있으면 뒤집기) + 확정 카운트 조회를 한 트랜잭션으로 처리한다.
     *
     * <p><b>거절 판정은 "내 제출 이력이 있는가" 한 번뿐이다.</b> 문제가 없어도, 미편성 풀이어도, 편성됐지만
     * 안 풀었어도 제출 행은 없으므로 세 경우가 같은 분기에서 같은 예외로 끝난다 — 응답이 갈릴 자리 자체가
     * 없다(사유를 구분하면 id 순회로 내일 출제분의 존재가 새어 나간다). 이 검사가 이후 조회·삽입보다 앞에
     * 있으므로 거절 시 어떤 행도 만들거나 갱신되지 않는다.
     *
     * <p>계정·문제는 {@code getReferenceById}(프록시)로 참조만 건다 — 제출 행이 있다는 것이 두 FK 가 모두
     * 유효하다는 증거라 실체를 읽을 이유가 없다.
     *
     * <p>카운트가 이번 변경을 반영하는 근거는 Hibernate 의 auto-flush 다 — 같은 테이블을 건드린 변경이
     * 걸려 있으면 조회 직전에 flush 된다. 이 메서드에 {@code readOnly = true}가 붙으면(붙일 일이 없지만)
     * flush 모드가 MANUAL 이 되어 방금 뒤집은 값이 카운트에서 빠진다.
     */
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

    /**
     * UNIQUE 충돌로 진 요청이 읽는 확정 상태 — <b>다시 토글하지 않는다</b>. 진 쪽이 재시도로 토글하면
     * 이긴 쪽이 방금 켠 좋아요를 그대로 꺼 버려, 둘 다 "켜기"를 눌렀는데 결과가 꺼짐이 된다.
     *
     * <p>행이 없으면 충돌 원인이 중복 키가 아니라 FK(그 사이 문제·계정이 사라짐)라는 뜻이다. 없는 대상에
     * 좋아요를 만들 수는 없으므로 정상 응답이 아니라 다른 거절과 같은 403 으로 접는다 —
     * {@code {liked:false, likeCount:0}} 을 200 으로 주면 "방금 취소함"과 구분되지 않는다.
     */
    @Transactional(readOnly = true)
    public QuizLikeResponse settledState(Long userAccountId, Long quizId) {
        QuizLike settled = quizLikeRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_LIKE_NOT_ALLOWED));
        return new QuizLikeResponse(settled.isLiked(),
                quizLikeRepository.countByQuiz_IdAndLikedTrue(quizId));
    }
}
