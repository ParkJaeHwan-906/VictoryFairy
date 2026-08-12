package com.skhynix.quiz.quiz.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 제출 시한 — <b>행의 {@code created_at}(= 그 문제를 받은 시각) + 8분</b>. 서빙({@link QuizService})과
 * 제출({@link QuizSubmitService})이 같은 기준을 봐야 "목록에서는 사라졌는데 제출은 되는" 어긋남이
 * 생기지 않으므로 한 자리에 둔다.
 *
 * <p>8분의 근거는 <b>문제당 10~20초 × 최대 20문제 ≒ 7분</b>이다. 서버는 문제당 제한 시간에 개입하지
 * 않는다(FE 가 재고 초과 시 직접 NULL 을 보낸다) — 이 시한이 다루는 유일한 상황은 <b>앱이 강제 종료돼
 * 아무 요청도 오지 않는 경우</b>이고, 그때 미제출이 확정되는 상한이 여기다.
 *
 * <p><b>연장 수단은 없다.</b> {@code /today} 재호출도 {@code created_at} 을 갱신하지 않고(기존 행은
 * 어떤 필드도 안 바뀐다), 제출 실패도 시한을 늘리지 않는다.
 *
 * <p>⚠⚠ <b>기준 시각은 반드시 {@link #now()} 여야 한다 — 주입된 {@code kstClock} 을 쓰면 안 된다.</b>
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

    /** {@code created_at} 과 같은 기준(JVM 기본 존)의 현재 시각. */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * {@code now} 기준으로 아직 시한이 남은 행의 <b>최소 {@code created_at}</b>. 이보다 이른 행은 시한
     * 초과다({@code created_at + 8분 < now} 와 같은 말).
     */
    static LocalDateTime earliestValidCreatedAt(LocalDateTime now) {
        return now.minus(WINDOW);
    }

    /** 미답 행의 시한 초과 여부. <b>저장된 플래그가 아니라 조회 시각 기준 계산</b>이다. */
    static boolean isExpired(LocalDateTime createdAt, LocalDateTime now) {
        return createdAt.isBefore(earliestValidCreatedAt(now));
    }
}
