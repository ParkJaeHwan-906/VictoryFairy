package com.skhynix.quiz.quiz.ingest;

import com.skhynix.quiz.quiz.service.QuizPublishService;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/**
 * 매일 S3 후보를 RDB 로 끌어오는 스케줄 진입점.
 *
 * <p><b>시각(10:30 KST)의 근거</b>: 생성 루틴이 08:50 KST 에 시작해 상한 60분이라 업로드는 늦어도
 * ~09:50 에 끝난다. 30분 여유를 두면 루틴이 늦는 날에도 대부분 첫 실행에 잡힌다.
 *
 * <p><b>최근 3일 catch-up</b>: 앱이 그 시각에 죽어 있던 날의 파티션이 영영 빠지지 않도록 오늘만이
 * 아니라 [오늘-2, 오늘]을 훑는다. 적재가 멱등이라(externalId) 이미 들어간 날짜는 전건 스킵으로
 * 끝난다 — 반복 비용이 사실상 0 이어서 창을 넓게 잡았다.
 *
 * <p><b>파드가 여러 개여도 잠금이 없다(의도)</b>: 동시에 돌면 한쪽 INSERT 만 성공하고 다른 쪽은
 * {@code uk_quizzes_external_id} 위반으로 걸린다 — 그걸 여기서 "이미 적재됨"으로 해석해 삼키므로
 * ShedLock 같은 분산 락 장치가 필요 없다. DB 제약이 곧 심판이다.
 *
 * <p><b>후보 단위 격리</b>: 한 후보의 실패(계약 위반 등)가 그날 나머지 적재를 막지 않는다.
 * {@link QuizIngestService#ingest}가 후보당 한 트랜잭션이라 실패 후보만 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class QuizIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuizIngestScheduler.class);

    private static final int CATCH_UP_DAYS = 2;

    private final QuizCandidateReader reader;
    private final QuizIngestService ingestService;
    private final QuizPublishService publishService;
    // KST 고정 클록(QuizConfig) — 파드 기본 존은 k8s Deployment env 의 TZ 에 달려 있어, LocalDate.now()
    // 기본값에 기대면 그 설정 하나로 자정~09시 하루 어긋남이 재발할 수 있다
    private final Clock clock;

    @Scheduled(cron = "${quiz.ingest.cron:0 30 10 * * *}", zone = "Asia/Seoul")
    public void ingestDaily() {
        LocalDate today = LocalDate.now(clock);
        for (int offset = CATCH_UP_DAYS; offset >= 0; offset--) {
            ingestDate(today.minusDays(offset));
        }
        // 적재 후 오늘 세트의 부족분을 풀에서 편성한다. 과거 날짜에는 편성하지 않는다 —
        // 이미 지나간 세트는 동결이다(뒤늦게 문제를 끼워 넣으면 그날 이미 푼 사용자와
        // 세트가 갈려 전원 동일 세트 전제가 깨진다). catch-up 은 적재(멱등)에만 해당한다.
        try {
            publishService.publishDaily(today);
        } catch (RuntimeException e) {
            // 편성 실패가 적재 성공을 삼키면 안 된다 — 적재분은 이미 커밋됐고, 편성은 다음
            // 실행(스케줄·기동 적재)이 다시 시도한다
            log.error("오늘의 퀴즈 편성 실패 — 적재 결과는 유지, 다음 실행이 재시도: {}", today, e);
        }
    }

    /** 날짜 하나를 적재하고 결과를 요약 로그로 남긴다. 스케줄 밖(테스트·수동 실행)에서도 쓸 수 있다. */
    public void ingestDate(LocalDate date) {
        var candidates = reader.readCandidates(date);
        if (candidates.isEmpty()) {
            log.info("quiz-candidates/{} 파티션 비어 있음(루틴 미실행·실패일 수 있음) — 넘어감", date);
            return;
        }
        int loaded = 0;
        int skipped = 0;
        int failed = 0;
        for (QuizCandidate candidate : candidates) {
            try {
                switch (ingestService.ingest(candidate, date)) {
                    case LOADED -> loaded++;
                    case SKIPPED_DUPLICATE, SKIPPED_PREDICTION -> skipped++;
                }
            } catch (DataIntegrityViolationException e) {
                // exists 선검사와 INSERT 사이에 다른 파드가 먼저 넣은 경우 — 실패가 아니라 멱등의 정상 경로
                log.info("동시 적재 감지(externalId UNIQUE) — 스킵: {}", candidate.quizId());
                skipped++;
            } catch (RuntimeException e) {
                log.error("후보 적재 실패 — 이 후보만 건너뜀: {}", candidate.quizId(), e);
                failed++;
            }
        }
        log.info("quiz-candidates/{} 적재 완료: 신규 {}건, 스킵 {}건, 실패 {}건 (총 {}건)",
                date, loaded, skipped, failed, candidates.size());
    }
}
