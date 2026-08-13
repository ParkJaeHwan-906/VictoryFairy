package com.skhynix.quiz.quiz.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "quiz.ingest.on-startup", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class QuizIngestStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuizIngestStartupRunner.class);

    private final QuizIngestScheduler scheduler;

    @Override
    public void run(ApplicationArguments args) {
        try {
            scheduler.ingestDaily();
        } catch (RuntimeException e) {
            log.warn("기동 시 퀴즈 적재 실패 — 기동은 계속한다(스케줄이 재시도): {}", e.getMessage());
        }
    }
}
