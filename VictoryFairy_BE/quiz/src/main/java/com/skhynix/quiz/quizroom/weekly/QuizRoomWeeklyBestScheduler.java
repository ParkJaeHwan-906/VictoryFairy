package com.skhynix.quiz.quizroom.weekly;

import com.skhynix.quiz.quizroom.weekly.QuizRoomWeeklyBestService.ConfirmResult;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주간 베스트 확정 잡. 확정 대상은 언제나 "바로 앞 주" 하나다 — 기동 시({@link QuizRoomWeeklyBestStartupRunner})와 예정 시각에
 * 같은 메서드를 부르므로, 월요일 0시에 파드가 전부 내려가 있었어도 다음 기동이 그 주를 메운다. 2주 이상 밀린 주는 확정하지 않는다.
 *
 * <p>분산 락이 없다(의도). 모든 인스턴스가 동시에 돌아도 {@code uk_quiz_room_weeks_week_start} 가 하나만 남기고 나머지는
 * 지급까지 롤백시킨다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizRoomWeeklyBestScheduler {

    private final QuizRoomWeeklyBestService weeklyBestService;
    // KST 고정 클록 — 기본 존(UTC)이면 월요일 00:00~09:00 KST 동안 "바로 앞 주"를 한 주 더 앞으로 잡는다
    private final Clock kstClock;

    // zone 을 빼면 파드 기본 존(UTC) 기준이 돼 월요일 09:00 KST 에 돈다
    @Scheduled(cron = "${quiz.room.weekly-best.cron:0 1 * * * MON}", zone = "Asia/Seoul")
    public void confirmPreviousWeek() {
        LocalDate target = QuizRoomWeeks.previousWeekStart(LocalDate.now(kstClock));
        try {
            ConfirmResult result = weeklyBestService.confirm(target);
            if (result.confirmed()) {
                log.info("퀴즈방 주간 베스트 확정 week={} 순위 {}건, 지급 {}건", target,
                        result.rankedCount(), result.rewardedCount());
            }
        } catch (DataIntegrityViolationException e) {
            // 다른 인스턴스가 먼저 확정했다 — 이 실행의 확정·지급은 전부 롤백됐다
            log.info("퀴즈방 주간 베스트 동시 확정 감지 — 다른 인스턴스 결과를 둔다 week={}", target);
        } catch (RuntimeException e) {
            log.error("퀴즈방 주간 베스트 확정 실패 — 확정·지급 모두 롤백, 다음 실행이 재시도 week={}", target, e);
        }
    }
}
