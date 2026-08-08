package com.skhynix.quiz.quiz.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/**
 * 기동 시 1회 적재 — 스케줄(10:30 KST)의 사각을 메운다: 그 시각 이후에 배포·재시작된 파드는
 * 다음 날까지 그날 퀴즈를 모르는 채가 되는데, 기동 적재가 멱등이라(externalId) 이미 들어간
 * 날은 전건 스킵으로 끝나 반복 비용이 사실상 없다. 로컬 실측 검증의 진입점이기도 하다.
 *
 * <p><b>실패해도 기동을 막지 않는다</b> — S3 자격증명이 없는 환경(슬라이스 테스트·오프라인
 * 로컬)에서 부팅이 죽으면 안 되고, 적재는 어차피 스케줄이 다시 시도한다.
 * {@code quiz.ingest.on-startup=false}로 끌 수 있다.
 */
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
