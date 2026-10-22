package com.skhynix.quiz.quiz.ingest;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.InningHalf;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizTypeRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuizIngestService {

    private static final Logger log = LoggerFactory.getLogger(QuizIngestService.class);

    /** 후보 {@code format} → {@code quiz_type.name} 시드값. BINARY(2지선다)는 O/X 토글이 아니라
     * 보기 2개짜리 선택지이므로 객관식으로 흡수한다. */
    private static final String TYPE_MULTIPLE = "객관식";
    private static final String TYPE_OX = "O/X";

    private static final String KIND_PREDICTION = "PREDICTION";

    /** 지금 적재를 지원하는 유일한 PREDICTION 지표. 그 외 지표는 settlement 유무와 무관하게 스킵. */
    private static final String METRIC_BATTER_HIT_IN_INNING = "BATTER_HIT_IN_INNING";

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizTypeRepository quizTypeRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final GameRepository gameRepository;

    public enum Result { LOADED, SKIPPED_DUPLICATE, SKIPPED_PREDICTION }

    @Transactional
    public Result ingest(QuizCandidate candidate, LocalDate quizDate) {
        // PREDICTION 은 지원 지표(BATTER_HIT_IN_INNING) 한정으로 별도 분기를 탄다 — 그 외는
        // ingestPrediction 내부에서도 SKIPPED_PREDICTION 으로 떨어진다(기존 스킵 로그 그대로).
        if (KIND_PREDICTION.equals(candidate.kind())) {
            return ingestPrediction(candidate, quizDate);
        }
        if (!"KNOWLEDGE".equals(candidate.kind())) {
            log.info("PREDICTION 후보는 아직 미지원 — 스킵: {}", candidate.quizId());
            return Result.SKIPPED_PREDICTION;
        }
        if (quizRepository.existsByExternalId(candidate.quizId())) {
            return Result.SKIPPED_DUPLICATE;
        }

        QuizType quizType = resolveQuizType(candidate);
        int answerIndex = resolveAnswerIndex(candidate);

        // 게임 귀속(naverGameId 명시) 여부가 quiz_date 를 가른다 — game FK 해석 성공 여부와 무관하다
        // (해석 실패 문항도 시효성은 그대로라 풀에 넣으면 안 된다)
        String naverGameId = resolveNaverGameId(candidate);
        boolean gameBound = naverGameId != null;

        Game game = gameBound ? resolveGame(naverGameId, candidate) : null;
        Team team = null;
        Team opponentTeam = null;
        if (game != null) {
            team = game.getHomeTeam();
            opponentTeam = game.getAwayTeam();
        } else if (candidate.subject() != null && candidate.subject().teamCodes() != null) {
            // 해석 성공분을 앞으로 당겨 채운다 — 첫 코드만 실패했을 때 team=null·opponent≠null 로
            // 적재되면 "opponentTeam 이 있으면 team 도 있다" 불변식(Quiz javadoc)이 깨진다.
            // 맞대결의 팀 순서는 어차피 임의라 당겨도 의미가 훼손되지 않는다.
            List<Team> resolvedTeams = candidate.subject().teamCodes().stream()
                    .limit(2)
                    .map(code -> resolveTeam(code, candidate))
                    .filter(Objects::nonNull)
                    .toList();
            team = resolvedTeams.size() >= 1 ? resolvedTeams.get(0) : null;
            opponentTeam = resolvedTeams.size() >= 2 ? resolvedTeams.get(1) : null;
        }
        Player player = resolvePlayer(candidate);

        Quiz quiz = quizRepository.save(Quiz.builder()
                .quizType(quizType)
                .team(team)
                .opponentTeam(opponentTeam)
                .player(player)
                .game(game)
                .content(candidate.question())
                .answer(answerIndex)
                .point(candidate.pointReward() == null ? null : candidate.pointReward().doubleValue())
                .bq(resolveBq(candidate))
                .externalId(candidate.quizId())
                .quizDate(gameBound ? quizDate : null)
                .difficulty(candidate.difficulty())
                .templateId(candidate.templateId())
                .build());

        List<QuizOption> options = new ArrayList<>();
        for (int i = 0; i < candidate.options().size(); i++) {
            options.add(QuizOption.builder()
                    .quiz(quiz)
                    .option(i)
                    .contents(candidate.options().get(i).text())
                    .build());
        }
        quizOptionRepository.saveAll(options);
        return Result.LOADED;
    }

    /**
     * PREDICTION 후보 중 지표가 {@code BATTER_HIT_IN_INNING} 인 것만 적재한다. 그 외 PREDICTION
     * 지표(또는 settlement 자체가 없는 PREDICTION)는 여전히 미지원 — 기존 스킵 로그·결과를 그대로
     * 쓴다(이번 범위는 이 지표 하나뿐).
     *
     * <p>KNOWLEDGE 와 갈리는 지점은 둘이다 — ① {@code answer}를 비워 둔다(정답은 경기가 그 이닝을
     * 지난 뒤 {@code quiz.settlement} 패키지가 채운다, {@link Quiz#settle(int)}) ② 게임 귀속 여부를
     * {@code resolveNaverGameId}가 아니라 {@code settlement.gameId()}로 판정한다 — PREDICTION 은
     * 정의상 특정 경기·이닝이 전제라 귀속이 선택적일 수 없다.
     *
     * <p><b>보기 순서는 고정 계약이다</b> — {@code options[0]}이 "적중(안타 발생)",
     * {@code options[1]}이 "미적중"이어야 한다. {@code QuizSettlementService}가 정산 시점에 보기
     * 텍스트를 보지 않고 이 인덱스 0/1 관례만으로 정답을 확정하기 때문이다(KNOWLEDGE의
     * {@code answer}가 보기 '텍스트'가 아니라 '번호'인 것과 같은 설계 — 보기 행이 재생성돼도 깨지지
     * 않는다).
     */
    private Result ingestPrediction(QuizCandidate candidate, LocalDate quizDate) {
        QuizCandidate.Settlement settlement = candidate.settlement();
        if (settlement == null || !METRIC_BATTER_HIT_IN_INNING.equals(settlement.metric())) {
            log.info("PREDICTION 후보는 아직 미지원 — 스킵: {}", candidate.quizId());
            return Result.SKIPPED_PREDICTION;
        }
        if (quizRepository.existsByExternalId(candidate.quizId())) {
            return Result.SKIPPED_DUPLICATE;
        }
        if (settlement.gameId() == null || settlement.gameId().isBlank()) {
            log.warn("PREDICTION 후보에 settlement.gameId 가 없음 — 정산 불가라 스킵: {}",
                    candidate.quizId());
            return Result.SKIPPED_PREDICTION;
        }

        QuizType quizType = resolveQuizType(candidate);
        Game game = resolveGame(settlement.gameId(), candidate);
        Team team = game != null ? game.getHomeTeam() : null;
        Team opponentTeam = game != null ? game.getAwayTeam() : null;
        Player player = resolvePlayer(candidate);
        InningHalf half = InningHalf.valueOf(settlement.half());

        Quiz quiz = quizRepository.save(Quiz.builder()
                .quizType(quizType)
                .team(team)
                .opponentTeam(opponentTeam)
                .player(player)
                .game(game)
                .content(candidate.question())
                .answer(null)
                .point(candidate.pointReward() == null ? null : candidate.pointReward().doubleValue())
                .bq(resolveBq(candidate))
                .externalId(candidate.quizId())
                .quizDate(quizDate)
                .difficulty(candidate.difficulty())
                .templateId(candidate.templateId())
                .settlementMetric(settlement.metric())
                .settlementInning(settlement.inning())
                .settlementHalf(half.ordinal())
                .build());

        List<QuizOption> options = new ArrayList<>();
        for (int i = 0; i < candidate.options().size(); i++) {
            options.add(QuizOption.builder()
                    .quiz(quiz)
                    .option(i)
                    .contents(candidate.options().get(i).text())
                    .build());
        }
        quizOptionRepository.saveAll(options);
        return Result.LOADED;
    }

    /**
     * 레이팅 축 배점 결정. 후보가 준 값이 <b>항상 우선</b>이고(난이도와 어긋나도 그대로 저장한다 —
     * 값의 정합은 업로드 직전 게이트가 책임진다. 여기서 한 번 더 판정하면 두 판정이 반드시 갈린다),
     * 값이 없을 때만 난이도로 채운다. 둘 다 실패하면 null 이며 적재는 성공한다.
     */
    private Integer resolveBq(QuizCandidate candidate) {
        return candidate.bqReward() != null
                ? candidate.bqReward()
                : DifficultyBqMapping.bqOf(candidate.difficulty());
    }

    private QuizType resolveQuizType(QuizCandidate candidate) {
        String typeName = switch (candidate.format()) {
            case "OX" -> TYPE_OX;
            case "BINARY", "MULTI4" -> TYPE_MULTIPLE;
            default -> throw new IllegalArgumentException(
                    "알 수 없는 format: " + candidate.format() + " (" + candidate.quizId() + ")");
        };
        // 시드(quiz-type-init.sql)가 선행돼야 한다 — 없으면 적재가 실패하는 것이 맞다(조용히
        // 임의 유형을 만들면 시드와 두 갈래가 된다)
        return quizTypeRepository.findByName(typeName).orElseThrow(() -> new IllegalStateException(
                "quiz_type 시드 없음: " + typeName + " — quiz-type-init.sql 적용 여부를 확인할 것"));
    }

    private int resolveAnswerIndex(QuizCandidate candidate) {
        if (candidate.options() == null || candidate.options().isEmpty()) {
            throw new IllegalArgumentException(
                    "KNOWLEDGE 후보에 options 가 없음 (" + candidate.quizId() + ")");
        }
        String answer = candidate.answer();
        if (answer == null || answer.length() != 1) {
            throw new IllegalArgumentException(
                    "KNOWLEDGE 후보의 answer 형식 위반: " + answer + " (" + candidate.quizId() + ")");
        }
        int index = answer.charAt(0) - 'A';
        if (index < 0 || index >= candidate.options().size()) {
            throw new IllegalArgumentException(
                    "answer 가 보기 범위를 벗어남: " + answer + " (" + candidate.quizId() + ")");
        }
        return index;
    }

    private String resolveNaverGameId(QuizCandidate candidate) {
        String naverGameId = candidate.subject() != null && candidate.subject().gameId() != null
                ? candidate.subject().gameId()
                : candidate.gameId();
        return naverGameId == null || naverGameId.isBlank() ? null : naverGameId;
    }

    private Game resolveGame(String naverGameId, QuizCandidate candidate) {
        return gameRepository.findByNaverGameId(naverGameId).orElseGet(() -> {
            log.warn("후보의 gameId 를 games 에서 못 찾음 — game FK 비우고 적재: {} ({})",
                    naverGameId, candidate.quizId());
            return null;
        });
    }

    private Team resolveTeam(String code, QuizCandidate candidate) {
        return teamRepository.findByCode(code).orElseGet(() -> {
            log.warn("후보의 teamCode 를 teams 에서 못 찾음 — 해당 FK 비우고 적재: {} ({})",
                    code, candidate.quizId());
            return null;
        });
    }

    private Player resolvePlayer(QuizCandidate candidate) {
        if (candidate.subject() == null || candidate.subject().playerIds() == null
                || candidate.subject().playerIds().isEmpty()) {
            return null;
        }
        // 대표 1명만 — 두 선수 문항(RELATION_LINK)의 두 번째 선수는 지금은 버린다(단일 FK 설계
        // 유지 결정). S3 원본이 externalId 로 링크돼 있어 필요해지면 백필 가능하다.
        String kboPlayerId = String.valueOf(candidate.subject().playerIds().get(0));
        return playerRepository.findByKboPlayerId(kboPlayerId).orElseGet(() -> {
            log.warn("후보의 playerId 를 players 에서 못 찾음 — player FK 비우고 적재: {} ({})",
                    kboPlayerId, candidate.quizId());
            return null;
        });
    }
}
