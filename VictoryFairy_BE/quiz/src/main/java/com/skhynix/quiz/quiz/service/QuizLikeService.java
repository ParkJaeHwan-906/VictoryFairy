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

@Service
@RequiredArgsConstructor
public class QuizLikeService {

    private final QuizLikeRepository quizLikeRepository;
    private final QuizLikeToggler quizLikeToggler;

    public QuizLikeResponse toggle(Long userAccountId, Long quizId) {
        try {
            return quizLikeToggler.toggleOnce(userAccountId, quizId);
        } catch (DataIntegrityViolationException e) {
            return quizLikeToggler.settledState(userAccountId, quizId);
        }
    }

    @Transactional(readOnly = true)
    public QuizLikeResponse likeOf(Long userAccountId, Long quizId) {
        return likesOf(userAccountId, List.of(quizId)).getOrDefault(quizId, QuizLikeResponse.none());
    }

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
