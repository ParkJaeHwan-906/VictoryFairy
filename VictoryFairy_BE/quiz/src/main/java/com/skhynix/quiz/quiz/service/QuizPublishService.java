package com.skhynix.quiz.quiz.service;

import com.skhynix.domain.quiz.repository.QuizRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매일의 퀴즈 편성 — 그날 세트가 {@code daily-count}에 못 미치면 미편성 풀
 * ({@code quiz_date IS NULL})에서 부족분만큼 꺼내 날짜를 스탬프한다.
 *
 * <p>경기 문항은 적재가 이미 날짜를 찍으므로({@code QuizIngestService}) 여기서는 "이미 찍힌
 * 수"에 포함해 셀 뿐이다 — 편성은 언제나 <b>보충</b>이지 재배치가 아니라서, 같은 날 몇 번을
 * 다시 돌려도 찍힌 날짜를 되돌리거나 옮기지 않는다(지난 세트 동결과 같은 원칙).
 *
 * <p>풀이 부족하면 부족한 대로 편성된다(publishFromPool 반환값 < 부족분) — 세트가 작아질 뿐
 * 실패가 아니며, 풀 고갈은 로그 수치(신규 < 부족분)로 드러난다.
 */
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

    /** 오늘 세트의 부족분을 풀에서 채운다. 반환값은 새로 편성된 문제 수(충족 상태면 0). */
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
