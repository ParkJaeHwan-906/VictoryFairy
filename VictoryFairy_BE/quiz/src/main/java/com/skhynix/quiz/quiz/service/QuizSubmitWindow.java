package com.skhynix.quiz.quiz.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * ⚠⚠ <b>기준 시각은 반드시 {@link #now()} 여야 한다 — 주입된 {@code kstClock} 을 쓰면 안 된다.</b>
 * 비교 대상인 {@code created_at} 은 Hibernate {@code @CreationTimestamp}(= JVM 기본 존의
 * {@code LocalDateTime.now()})가 찍은 값이다. 시한 판정은 "같은 존으로 찍힌 두 값의 뺄셈"이어야
 * 성립하므로, 이 비교에는 {@code created_at} 을 찍은 것과 같은 JVM 기본 존을 써야 한다.
 * {@code kstClock} 은 코드에 KST 로 고정해 둔 별개의 존이다 — 파드의 {@code TZ} 설정(k8s Deployment
 * env, dev_infra 소관)이 JVM 기본 존을 정하는데, 그 값이 빠지거나 다른 값으로 바뀌면 {@code kstClock}
 * 과 {@code created_at} 의 존이 어긋나 <b>모든 행이 시한 오판</b>(초과 쪽이든 미초과 쪽이든)에
 * 빠진다. {@code kstClock} 은 "오늘이 며칠인가"({@code quiz_date})에만 쓴다.
 */
final class QuizSubmitWindow {

    private static final Duration WINDOW = Duration.ofMinutes(8);

    private QuizSubmitWindow() {
    }

    static LocalDateTime now() {
        return LocalDateTime.now();
    }

    static LocalDateTime earliestValidCreatedAt(LocalDateTime now) {
        return now.minus(WINDOW);
    }

    static boolean isExpired(LocalDateTime createdAt, LocalDateTime now) {
        return createdAt.isBefore(earliestValidCreatedAt(now));
    }
}
