package com.skhynix.quiz.quiz.service;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

/**
 * 퀴즈 조회. '오늘'은 KST 다({@code kstClock} — 파드 JVM 은 UTC 라 기본 클록이면 자정~09시에
 * 하루가 어긋난다).
 *
 * <p>보기는 {@code quiz_id IN (...)} 한 방으로 받아 메모리에서 묶는 <b>2쿼리 방식</b>이다 —
 * 문제마다 단건 조회하면 N+1 이고, {@code Quiz}에 {@code @OneToMany options}가 없어
 * {@code @EntityGraph}로는 못 막는다({@code QuizOptionRepository} javadoc 의 지시).
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<QuizResponse> getTodayQuizzes() {
        List<Quiz> quizzes = quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(clock));
        if (quizzes.isEmpty()) {
            return List.of();
        }
        List<Long> quizIds = quizzes.stream().map(Quiz::getId).toList();
        Map<Long, List<QuizOption>> optionsByQuizId = quizOptionRepository
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIds).stream()
                .collect(Collectors.groupingBy(option -> option.getQuiz().getId()));
        return quizzes.stream()
                .map(quiz -> QuizResponse.of(quiz, optionsByQuizId.getOrDefault(quiz.getId(), List.of())))
                .toList();
    }
}
