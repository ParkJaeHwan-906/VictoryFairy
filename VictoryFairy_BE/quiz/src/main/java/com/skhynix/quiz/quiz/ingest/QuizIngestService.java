package com.skhynix.quiz.quiz.ingest;

import com.skhynix.domain.game.entity.Game;
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

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizTypeRepository quizTypeRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final GameRepository gameRepository;

    public enum Result { LOADED, SKIPPED_DUPLICATE, SKIPPED_PREDICTION }

    @Transactional
    public Result ingest(QuizCandidate candidate, LocalDate quizDate) {
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
                .score(candidate.pointReward() == null ? null : candidate.pointReward().doubleValue())
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
