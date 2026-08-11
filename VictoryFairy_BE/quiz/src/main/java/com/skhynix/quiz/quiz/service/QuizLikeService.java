package com.skhynix.quiz.quiz.service;

import com.skhynix.domain.quiz.repository.QuizLikeCountView;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 좋아요 — 토글과, 조회 경로(단건 상세·풀이 이력)에 실을 좋아요 상태 조립.
 *
 * <p>{@code QuizService}(조회 조립)에 얹지 않고 나눈 이유: 좋아요는 <b>쓰기 경로</b>이고 선행조건
 * (제출 이력)·동시성 흡수라는 자기 규칙을 갖는다. 조회 서비스에 섞으면 읽기 조립과 쓰기 규칙이 한
 * 클래스에 겹친다.
 */
@Service
@RequiredArgsConstructor
public class QuizLikeService {

    private final QuizLikeRepository quizLikeRepository;
    private final QuizLikeToggler quizLikeToggler;

    /**
     * 좋아요 토글. 제출한 문제가 아니면 403 이고, 그 판정은 {@link QuizLikeToggler#toggleOnce}가 한다.
     *
     * <p><b>이 메서드에 {@code @Transactional}을 붙이면 안 된다.</b> 붙는 순간 아래 두 호출이 한
     * 트랜잭션으로 묶여, 충돌로 롤백 표시가 붙은 트랜잭션에서 재조회를 하게 되고 커밋 시점에
     * {@code UnexpectedRollbackException}(500)이 난다 — 흡수하려던 그 500 이 자리만 옮겨 돌아온다.
     *
     * <p>같은 계정이 같은 문제에 처음 좋아요를 동시에 두 번 보내면 두 번째 INSERT 가
     * {@code uk_quizzes_like_account_quiz}에 걸린다. 그건 시스템 오류가 아니라 "이미 켜졌다"이므로
     * 확정 상태를 다시 읽어 정상 응답으로 돌려준다(둘 다 켜기를 의도했으니 최종 상태는 켜짐).
     */
    public QuizLikeResponse toggle(Long userAccountId, Long quizId) {
        try {
            return quizLikeToggler.toggleOnce(userAccountId, quizId);
        } catch (DataIntegrityViolationException e) {
            return quizLikeToggler.settledState(userAccountId, quizId);
        }
    }

    /** 단건 상세용 — 문제 하나의 좋아요 상태. 조회 2건으로 끝난다(집계 1 + 내 좋아요 1). */
    @Transactional(readOnly = true)
    public QuizLikeResponse likeOf(Long userAccountId, Long quizId) {
        return likesOf(userAccountId, List.of(quizId)).getOrDefault(quizId, QuizLikeResponse.none());
    }

    /**
     * 이력용 — 여러 문제의 좋아요 상태를 <b>항목 수와 무관하게 조회 2건</b>으로 모은다(집계 1 + 내
     * 좋아요 1). 항목마다 단건 조회하면 그대로 N+1 이다({@code QuizOptionRepository}의 {@code quiz_id IN}
     * 2쿼리 방식과 같은 방법).
     *
     * <p>집계 쿼리는 {@code group by}라 <b>좋아요가 0인 문제를 결과에 아예 넣지 않고</b>, 내 좋아요 조회도
     * 켜진 것만 돌려준다(없으면 곧 {@code false} — 누른 적 없음과 취소함이 여기서 합쳐지며 그게 응답
     * 계약이다). 그래서 반환 맵은 <b>요청한 quizId 전부를 키로 채워</b> 돌려준다 — 호출부가 빠진 키를
     * 0으로 메우는 일을 잊으면 좋아요 0인 문제에서 NPE 가 난다.
     *
     * <p>호출부의 읽기 트랜잭션에 참여한다({@code open-in-view=false} — 조립은 서비스 트랜잭션 안에서
     * 끝나야 한다).
     */
    @Transactional(readOnly = true)
    public Map<Long, QuizLikeResponse> likesOf(Long userAccountId, Collection<Long> quizIds) {
        if (quizIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> countByQuizId = quizLikeRepository.countLikesByQuizIds(quizIds).stream()
                .collect(Collectors.toMap(QuizLikeCountView::getQuizId,
                        QuizLikeCountView::getLikeCount));
        Set<Long> likedQuizIds =
                Set.copyOf(quizLikeRepository.findLikedQuizIds(userAccountId, quizIds));
        return quizIds.stream()
                .distinct()
                .collect(Collectors.toMap(Function.identity(),
                        quizId -> new QuizLikeResponse(likedQuizIds.contains(quizId),
                                countByQuizId.getOrDefault(quizId, 0L))));
    }
}
