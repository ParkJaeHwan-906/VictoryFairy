package com.skhynix.quiz.quiz.settlement;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.InningHalf;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.repository.QuizRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PREDICTION(이닝 트리거 예측) 퀴즈 정산. 두 경로가 있다 — ① {@link #settleInningEvent}는 py-collector의
 * S3 {@code inning-events/*} 사실 하나를 반영(정상 경로, {@code QuizSettlementListener}가 SQS로 받아
 * 호출) ② {@link #sweepUnresolved}는 그 사실이 영영 안 오거나 유실됐을 때의 폴백(매일 적재 스케줄러
 * 끝에서 호출, {@code QuizIngestScheduler}).
 */
@Service
@RequiredArgsConstructor
public class QuizSettlementService {

    private static final Logger log = LoggerFactory.getLogger(QuizSettlementService.class);

    /**
     * BINARY 포맷 PREDICTION 후보의 보기 순서 계약({@code QuizIngestService#ingestPrediction}
     * javadoc) — {@code options[0]}=적중, {@code options[1]}=미적중. 정산은 보기 텍스트를 보지 않고
     * 이 인덱스 0/1 관례만으로 {@code answer}를 확정한다.
     */
    private static final int HIT_OPTION_INDEX = 0;
    private static final int MISS_OPTION_INDEX = 1;

    // 경기 상태 판정은 name 문자열로 한다(QuizService·QuizSubmitService 와 같은 규칙 — id 는
    // py-collector 가 만난 순서로 부여돼 환경마다 다르다). "안 끝났다"에 해당하는 두 이름만 적시하고
    // 나머지(FINISHED·DRAW·CANCELED 와 아직 없는 이름)는 전부 "끝났다"로 본다 — 상태 정의역은 앱
    // 배포 없이 늘어날 수 있다(game-statuses-init.sql 의 "UNION ALL 한 줄"로 예고된 패턴).
    private static final Set<String> NOT_ENDED_STATUSES = Set.of("SCHEDULED", "IN_PROGRESS");

    private final QuizRepository quizRepository;
    private final GameRepository gameRepository;

    /**
     * 이닝 종료 사실 하나를 반영한다. {@code fact}가 가리키는 (경기, 이닝, 초/말)과 settlement
     * 좌표가 일치하고 아직 답이 없는 PREDICTION 문제를 전부 찾아, 그 문제의
     * {@code player.kboPlayerId}가 {@code fact.events()}에 {@code hit=true}로 있으면 적중
     * ({@link #HIT_OPTION_INDEX}), 없으면(관측 없음도 포함) 미적중({@link #MISS_OPTION_INDEX})으로
     * {@code answer}를 확정한다.
     *
     * <p>게임을 못 찾거나(naverGameId 미등록) half 문자열을 해석할 수 없으면 조용히 반환한다 —
     * 대상을 아예 못 찾았으니 재시도해도 결과가 달라지지 않는다. 대상 문제가 0건이어도 마찬가지로
     * no-op(이닝에 PREDICTION 이 없었거나 이미 정산됐다는 뜻).
     *
     * <p><b>멱등</b> — {@code answer IS NULL} 조건이 이미 정산된 행을 걸러내므로, SQS 가 같은
     * 메시지를 두 번 전달해도(at-least-once) 두 번째 호출은 대상이 0건이라 아무 일도 하지 않는다.
     */
    @Transactional
    public void settleInningEvent(InningEventFact fact) {
        Game game = gameRepository.findByNaverGameId(fact.gameId()).orElse(null);
        if (game == null) {
            log.warn("inning-events 사실의 gameId 를 games 에서 못 찾음 — 정산 보류: {}", fact.gameId());
            return;
        }
        Integer half = resolveHalfOrdinal(fact.half());
        if (half == null) {
            log.warn("inning-events 사실의 half 값을 해석할 수 없음({}) — 정산 보류: gameId={}",
                    fact.half(), fact.gameId());
            return;
        }

        List<Quiz> targets = quizRepository
                .findByGame_IdAndSettlementInningAndSettlementHalfAndAnswerIsNull(
                        game.getId(), fact.inning(), half);
        for (Quiz quiz : targets) {
            settleOne(quiz, fact);
        }
    }

    private void settleOne(Quiz quiz, InningEventFact fact) {
        if (quiz.getPlayer() == null) {
            log.warn("정산 대상 PREDICTION 문제에 player FK 가 없어 적중 여부를 판정할 수 없음 — "
                    + "그대로 둠: quizId={}", quiz.getId());
            return;
        }
        String kboPlayerId = quiz.getPlayer().getKboPlayerId();
        boolean hit = fact.events() != null
                && fact.events().containsKey(kboPlayerId)
                && fact.events().get(kboPlayerId).hit();
        quiz.settle(hit ? HIT_OPTION_INDEX : MISS_OPTION_INDEX);
    }

    /**
     * 진행 중·예정이 아닌 경기의 미정산 PREDICTION 을 전부 미적중(오답)으로 확정하는 폴백 — 이닝
     * 종료 이벤트가 유실되거나(S3 알림 누락 등) 영영 안 올 경우의 안전망이다. 매일 적재 스케줄러
     * 끝에서 호출된다({@code QuizIngestScheduler#ingestDaily}).
     *
     * <p>{@code game} FK 가 비어 있는 문제(적재 시점 game 해석 실패)는 "끝났는지" 판단할 근거가
     * 없어 건드리지 않는다 — 그 경로는 적재 시점 WARN 로그로 이미 드러나 있다.
     */
    @Transactional
    public void sweepUnresolved() {
        List<Quiz> unresolved = quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull();
        int swept = 0;
        for (Quiz quiz : unresolved) {
            if (isEnded(quiz)) {
                quiz.settle(MISS_OPTION_INDEX);
                swept++;
            }
        }
        if (swept > 0) {
            log.info("종료된 경기의 미정산 PREDICTION {}건을 오답으로 확정", swept);
        }
    }

    private boolean isEnded(Quiz quiz) {
        Game game = quiz.getGame();
        if (game == null) {
            return false;
        }
        return !NOT_ENDED_STATUSES.contains(game.getGameStatus().getName());
    }

    private Integer resolveHalfOrdinal(String half) {
        try {
            return InningHalf.valueOf(half).ordinal();
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
