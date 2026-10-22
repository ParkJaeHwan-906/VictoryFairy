package com.skhynix.quiz.quiz.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.entity.InningHalf;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.repository.QuizRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizSettlementService} 단위 검증 — 협력 리포지토리 전부 Mockito 목으로 대체한다
 * (DB·S3·SQS 없음).
 *
 * <p>정답 보기 index 는 ingest 시점의 고정 계약(options[0]=적중, options[1]=미적중)을 그대로
 * 재사용한다({@code QuizIngestService#ingestPrediction} javadoc) — 그래서 이 테스트는
 * {@code QuizOption}을 전혀 만들지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class QuizSettlementServiceTest {

    private static final Long GAME_PK = 42L;
    private static final String NAVER_GAME_ID = "20260812HHKT02026";
    private static final String KBO_PLAYER_ID = "54260";

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private QuizSettlementService settlementService;

    private static GameStatus status(String name) {
        return GameStatus.builder().name(name).build();
    }

    private static Game game(Long id, String statusName) {
        Game g = Game.builder()
                .gameStatus(status(statusName))
                .naverGameId(NAVER_GAME_ID)
                .build();
        // id 는 @GeneratedValue 라 Builder 에 없다 — QuizSubmitServiceTest.game() 과 같은 방식으로
        // 픽스처용 PK 를 심는다.
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    private static Player player(String kboPlayerId) {
        return Player.builder().name("문동주").average(0.0).kboPlayerId(kboPlayerId).build();
    }

    private static Quiz predictionQuiz(Game game, Player player, int inning, InningHalf half) {
        return Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .game(game)
                .player(player)
                .content("이 선수가 이 이닝에 안타를 칠까?")
                .answer(null)
                .externalId("QZ-SETTLE-" + System.nanoTime())
                .quizDate(LocalDate.of(2026, 8, 12))
                .settlementMetric("BATTER_HIT_IN_INNING")
                .settlementInning(inning)
                .settlementHalf(half.ordinal())
                .build();
    }

    // ---------- (a) 이벤트 있음 → 정답(적중) 확정 ----------

    @Test
    @DisplayName("[정산-a] events 에 그 선수가 hit=true 로 있으면 answer가 적중 인덱스(0)로 확정된다")
    void settleInningEvent_playerHit_settlesToHitIndex() {
        Game game = game(GAME_PK, "IN_PROGRESS");
        given(gameRepository.findByNaverGameId(NAVER_GAME_ID)).willReturn(Optional.of(game));
        Quiz quiz = predictionQuiz(game, player(KBO_PLAYER_ID), 7, InningHalf.TOP);
        given(quizRepository.findByGame_IdAndSettlementInningAndSettlementHalfAndAnswerIsNull(
                GAME_PK, 7, InningHalf.TOP.ordinal())).willReturn(List.of(quiz));
        InningEventFact fact = new InningEventFact(NAVER_GAME_ID, LocalDate.of(2026, 8, 12), 7,
                "TOP", Map.of(KBO_PLAYER_ID, new InningEventFact.HitEvent(true)));

        settlementService.settleInningEvent(fact);

        assertThat(quiz.getAnswer()).isZero();
        assertThat(quiz.isUnsettledPrediction()).isFalse();
    }

    // ---------- (b) 이닝은 끝났는데(=사실 도착) 이벤트 없음 → 오답(미적중) 확정 ----------

    @Test
    @DisplayName("[정산-b] events 에 그 선수 키가 없으면(관측 없음) answer가 미적중 인덱스(1)로 확정된다")
    void settleInningEvent_playerAbsentFromEvents_settlesToMissIndex() {
        Game game = game(GAME_PK, "IN_PROGRESS");
        given(gameRepository.findByNaverGameId(NAVER_GAME_ID)).willReturn(Optional.of(game));
        Quiz quiz = predictionQuiz(game, player(KBO_PLAYER_ID), 7, InningHalf.TOP);
        given(quizRepository.findByGame_IdAndSettlementInningAndSettlementHalfAndAnswerIsNull(
                GAME_PK, 7, InningHalf.TOP.ordinal())).willReturn(List.of(quiz));
        // 그 이닝에 다른 선수만 관측됐고 대상 선수는 키 자체가 없다
        InningEventFact fact = new InningEventFact(NAVER_GAME_ID, LocalDate.of(2026, 8, 12), 7,
                "TOP", Map.of("99999", new InningEventFact.HitEvent(true)));

        settlementService.settleInningEvent(fact);

        assertThat(quiz.getAnswer()).isEqualTo(1);
    }

    @Test
    @DisplayName("[정산-b'] events 에 그 선수가 hit=false 로 있어도 미적중 인덱스(1)로 확정된다")
    void settleInningEvent_playerHitFalse_settlesToMissIndex() {
        Game game = game(GAME_PK, "IN_PROGRESS");
        given(gameRepository.findByNaverGameId(NAVER_GAME_ID)).willReturn(Optional.of(game));
        Quiz quiz = predictionQuiz(game, player(KBO_PLAYER_ID), 7, InningHalf.TOP);
        given(quizRepository.findByGame_IdAndSettlementInningAndSettlementHalfAndAnswerIsNull(
                GAME_PK, 7, InningHalf.TOP.ordinal())).willReturn(List.of(quiz));
        InningEventFact fact = new InningEventFact(NAVER_GAME_ID, LocalDate.of(2026, 8, 12), 7,
                "TOP", Map.of(KBO_PLAYER_ID, new InningEventFact.HitEvent(false)));

        settlementService.settleInningEvent(fact);

        assertThat(quiz.getAnswer()).isEqualTo(1);
    }

    @Test
    @DisplayName("gameId 를 games 에서 못 찾으면 조용히 반환하고 quizRepository 를 건드리지 않는다")
    void settleInningEvent_gameNotFound_returnsWithoutTouchingQuizRepository() {
        given(gameRepository.findByNaverGameId(NAVER_GAME_ID)).willReturn(Optional.empty());
        InningEventFact fact = new InningEventFact(NAVER_GAME_ID, LocalDate.of(2026, 8, 12), 7,
                "TOP", Map.of());

        settlementService.settleInningEvent(fact);

        verifyNoInteractions(quizRepository);
    }

    // ---------- (c) sweepUnresolved: 종료된 경기의 미정산 PREDICTION을 오답으로 ----------

    @Test
    @DisplayName("[정산-c] FINISHED 경기의 미정산 PREDICTION은 오답(미적중)으로 확정된다")
    void sweepUnresolved_finishedGame_settlesToMiss() {
        Game finished = game(GAME_PK, "FINISHED");
        Quiz quiz = predictionQuiz(finished, player(KBO_PLAYER_ID), 9, InningHalf.BOTTOM);
        given(quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull())
                .willReturn(List.of(quiz));

        settlementService.sweepUnresolved();

        assertThat(quiz.getAnswer()).isEqualTo(1);
    }

    @Test
    @DisplayName("[정산-c] IN_PROGRESS 경기의 미정산 PREDICTION은 건드리지 않는다(아직 끝나지 않음)")
    void sweepUnresolved_inProgressGame_leavesUnsettled() {
        Game inProgress = game(GAME_PK, "IN_PROGRESS");
        Quiz quiz = predictionQuiz(inProgress, player(KBO_PLAYER_ID), 5, InningHalf.TOP);
        given(quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull())
                .willReturn(List.of(quiz));

        settlementService.sweepUnresolved();

        assertThat(quiz.getAnswer()).isNull();
        assertThat(quiz.isUnsettledPrediction()).isTrue();
    }

    @Test
    @DisplayName("[정산-c] SCHEDULED 경기의 미정산 PREDICTION도 건드리지 않는다")
    void sweepUnresolved_scheduledGame_leavesUnsettled() {
        Game scheduled = game(GAME_PK, "SCHEDULED");
        Quiz quiz = predictionQuiz(scheduled, player(KBO_PLAYER_ID), 1, InningHalf.TOP);
        given(quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull())
                .willReturn(List.of(quiz));

        settlementService.sweepUnresolved();

        assertThat(quiz.getAnswer()).isNull();
    }

    @Test
    @DisplayName("[정산-c] CANCELED·DRAW 등 FINISHED 가 아닌 다른 종료 상태도 오답으로 확정된다"
            + "(두 \"안 끝남\" 이름 외에는 전부 종료로 본다)")
    void sweepUnresolved_canceledGame_settlesToMiss() {
        Game canceled = game(GAME_PK, "CANCELED");
        Quiz quiz = predictionQuiz(canceled, player(KBO_PLAYER_ID), 3, InningHalf.BOTTOM);
        given(quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull())
                .willReturn(List.of(quiz));

        settlementService.sweepUnresolved();

        assertThat(quiz.getAnswer()).isEqualTo(1);
    }

    @Test
    @DisplayName("[정산-c] game FK 가 없는(해석 실패) 미정산 PREDICTION은 종료 여부를 판단할 수 없어 "
            + "건드리지 않는다")
    void sweepUnresolved_nullGame_leavesUnsettled() {
        Quiz quiz = predictionQuiz(null, player(KBO_PLAYER_ID), 3, InningHalf.BOTTOM);
        given(quizRepository.findBySettlementMetricIsNotNullAndAnswerIsNull())
                .willReturn(List.of(quiz));

        settlementService.sweepUnresolved();

        assertThat(quiz.getAnswer()).isNull();
    }
}
