package com.skhynix.quiz.quizroom.weekly;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기동 시 1회 — 예정 시각에 파드가 없었던 주를 메운다. 실패는 스케줄러가 삼키므로 기동을 막지 않는다. */
@Component
@ConditionalOnProperty(name = "quiz.room.weekly-best.on-startup", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class QuizRoomWeeklyBestStartupRunner implements ApplicationRunner {

    private final QuizRoomWeeklyBestScheduler scheduler;

    @Override
    public void run(ApplicationArguments args) {
        scheduler.confirmPreviousWeek();
    }
}
