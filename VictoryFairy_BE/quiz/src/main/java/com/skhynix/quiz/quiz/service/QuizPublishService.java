package com.skhynix.quiz.quiz.service;

import com.skhynix.domain.quiz.repository.QuizRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuizPublishService {

    private static final Logger log = LoggerFactory.getLogger(QuizPublishService.class);

    private final QuizRepository quizRepository;
    private final int dailyCount;

    public QuizPublishService(QuizRepository quizRepository,
            @Value("${quiz.serve.daily-count:10}") int dailyCount) {
        this.quizRepository = quizRepository;
        this.dailyCount = dailyCount;
    }

    @Transactional
    public int publishDaily(LocalDate today) {
        long existing = quizRepository.countByQuizDate(today);
        int deficit = (int) (dailyCount - existing);
        int published = deficit > 0 ? quizRepository.publishFromPool(today, deficit) : 0;
        log.info("오늘의 퀴즈 편성: 기존 {}건 + 신규 {}건 (목표 {}건, {})",
                existing, published, dailyCount, today);
        return published;
    }
}
