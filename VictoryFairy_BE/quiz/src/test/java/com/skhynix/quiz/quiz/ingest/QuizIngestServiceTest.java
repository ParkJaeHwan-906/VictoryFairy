package com.skhynix.quiz.quiz.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link QuizIngestService#ingest}를 협력 리포지토리 전부 Mockito 목으로 대체해 단위 검증한다.
 * DB·S3·Spring 컨텍스트 없음.
 *
 * <p>핵심 검증 포인트는 <b>정답 유출 방지 규칙</b>이다 — subject 에는 문제가 '전제'하는 엔티티만
 * 오고 정답 엔티티는 생산자가 뺀다. 그래서 player 만 있고 team 이 비어 오는 후보(CAREER_PATH)가
 * 정상이며, 로더가 {@code player.getTeam()}으로 team 을 보강하면 안 된다({@code Quiz} javadoc 불변식).
 */
@ExtendWith(MockitoExtension.class)
class QuizIngestServiceTest {

    private static final LocalDate QUIZ_DATE = LocalDate.of(2026, 8, 7);
    private static final String EXTERNAL_ID = "QZ-20260807-001";

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizOptionRepository quizOptionRepository;

    @Mock
    private QuizTypeRepository quizTypeRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private QuizIngestService ingestService;

    @Captor
    private ArgumentCaptor<Quiz> quizCaptor;

    @Captor
    private ArgumentCaptor<List<QuizOption>> optionsCaptor;

    // ---------- 픽스처 ----------

    private static QuizCandidate.Option option(String id, String text) {
        return new QuizCandidate.Option(id, text);
    }

    private static List<QuizCandidate.Option> fourOptions() {
        return List.of(option("A", "보기1"), option("B", "보기2"),
                option("C", "보기3"), option("D", "보기4"));
    }

    /** KNOWLEDGE 후보 한 건. 상단 gameId 는 비우고 상단 teamCodes(귀속 축)는 채워 둔다. */
    private static QuizCandidate candidate(String kind, String format,
            List<QuizCandidate.Option> options, String answer, QuizCandidate.Subject subject) {
        return new QuizCandidate(EXTERNAL_ID, null, kind, "MEME_ORIGIN", format, "문제 지문?",
                options, answer, "MEDIUM", 30, 2, List.of("HH"), subject);
    }

    private static QuizType multipleType() {
        return QuizType.builder().name("객관식").build();
    }

    private static QuizType oxType() {
        return QuizType.builder().name("O/X").build();
    }

    private static Team team(String code, String name) {
        return Team.builder().code(code).name(name).build();
    }

    private static Player player(Team team) {
        return Player.builder().team(team).name("문동주").average(0.0).kboPlayerId("54260").build();
    }

    private void givenNotDuplicated() {
        given(quizRepository.existsByExternalId(EXTERNAL_ID)).willReturn(false);
    }

    private void givenMultipleTypeSeed(QuizType type) {
        given(quizTypeRepository.findByName("객관식")).willReturn(Optional.of(type));
    }

    private void givenSaveReturnsArgument() {
        given(quizRepository.save(any(Quiz.class))).willAnswer(inv -> inv.getArgument(0));
        given(quizOptionRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));
    }

    // ---------- 매핑 전체 ----------

    @Test
    @DisplayName("KNOWLEDGE MULTI4 후보를 적재하면 externalId·score·difficulty·templateId·"
            + "answer(A→0)가 그대로 매핑되고 보기 4행이 번호 0..3으로 저장된다 — 게임 귀속이 "
            + "없는 후보라 quizDate는 null(미편성 풀 대기)이다")
    void ingest_knowledgeMulti4_mapsEveryFieldAndSavesFourOptions() {
        givenNotDuplicated();
        QuizType type = multipleType();
        givenMultipleTypeSeed(type);
        givenSaveReturnsArgument();
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A", null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.LOADED);
        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getExternalId()).isEqualTo(EXTERNAL_ID);
        // 시효성 없는(게임 미귀속) 문제는 파티션 날짜가 아니라 풀로 간다 — 편성 잡이 출제일을 찍는다
        assertThat(saved.getQuizDate()).isNull();
        assertThat(saved.getPoint()).isEqualTo(30.0);
        assertThat(saved.getDifficulty()).isEqualTo("MEDIUM");
        assertThat(saved.getTemplateId()).isEqualTo("MEME_ORIGIN");
        assertThat(saved.getAnswer()).isEqualTo(0);
        assertThat(saved.getContent()).isEqualTo("문제 지문?");
        assertThat(saved.getQuizType()).isSameAs(type);

        verify(quizOptionRepository).saveAll(optionsCaptor.capture());
        List<QuizOption> options = optionsCaptor.getValue();
        assertThat(options).hasSize(4);
        assertThat(options).extracting(QuizOption::getOption).containsExactly(0, 1, 2, 3);
        assertThat(options).extracting(QuizOption::getContents)
                .containsExactly("보기1", "보기2", "보기3", "보기4");
        assertThat(options).allSatisfy(option -> assertThat(option.getQuiz()).isSameAs(saved));
    }

    // ---------- 배점 분리(QUIZ-PBQ-9~13) ----------

    @Test
    @DisplayName("[QUIZ-PBQ-9] 후보 JSON에 bqReward가 있으면 그 값을 그대로 저장한다 — difficulty 매핑값"
            + "(EASY=1)과 달라도 후보 값이 우선이다")
    void ingest_bqRewardPresent_savesAsIsOverridingDifficultyMapping() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        QuizCandidate candidate = new QuizCandidate(EXTERNAL_ID, null, "KNOWLEDGE", "MEME_ORIGIN",
                "MULTI4", "문제 지문?", fourOptions(), "A", "EASY", 30, 3, List.of("HH"), null);

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        // EASY의 매핑값은 1이지만 후보가 3을 보냈으므로 3이 저장된다(정합 판단은 상류 게이트 몫)
        assertThat(quizCaptor.getValue().getBq()).isEqualTo(3);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-10] 후보 JSON에 bqReward가 없으면 difficulty를 매핑표에 적용해 채운다"
            + "(v3 이전 파티션 재처리 경로)")
    void ingest_bqRewardAbsent_fallsBackToDifficultyMapping() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        QuizCandidate candidate = new QuizCandidate(EXTERNAL_ID, null, "KNOWLEDGE", "MEME_ORIGIN",
                "MULTI4", "문제 지문?", fourOptions(), "A", "HARD", 30, null, List.of("HH"), null);

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getBq()).isEqualTo(3);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-11] bqReward도 difficulty 매핑도 없으면 bq를 NULL로 저장하고 적재는 성공한다"
            + "(LOADED, 다른 컬럼은 정상)")
    void ingest_neitherBqRewardNorMappableDifficulty_savesNullBqAndStillLoads() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        QuizCandidate candidate = new QuizCandidate(EXTERNAL_ID, null, "KNOWLEDGE", "MEME_ORIGIN",
                "MULTI4", "문제 지문?", fourOptions(), "A", null, 30, null, List.of("HH"), null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.LOADED);
        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getBq()).isNull();
        assertThat(saved.getPoint()).isEqualTo(30.0);
        assertThat(saved.getExternalId()).isEqualTo(EXTERNAL_ID);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-13] 후보 JSON에 pointReward가 없으면 point를 NULL로 저장한다")
    void ingest_pointRewardAbsent_savesNullPoint() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        QuizCandidate candidate = new QuizCandidate(EXTERNAL_ID, null, "KNOWLEDGE", "MEME_ORIGIN",
                "MULTI4", "문제 지문?", fourOptions(), "A", "MEDIUM", null, 2, List.of("HH"), null);

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getPoint()).isNull();
    }

    // ---------- 유형 해석 ----------

    @Test
    @DisplayName("format OX 후보는 quiz_type 'O/X'로 해석되고 answer B는 보기 번호 1이 된다")
    void ingest_oxFormat_resolvesOxTypeAndAnswerIndex() {
        givenNotDuplicated();
        QuizType type = oxType();
        given(quizTypeRepository.findByName("O/X")).willReturn(Optional.of(type));
        givenSaveReturnsArgument();
        QuizCandidate candidate = candidate("KNOWLEDGE", "OX",
                List.of(option("A", "O"), option("B", "X")), "B", null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.LOADED);
        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getQuizType()).isSameAs(type);
        assertThat(quizCaptor.getValue().getAnswer()).isEqualTo(1);
    }

    @Test
    @DisplayName("format BINARY(2지선다) 후보는 O/X가 아니라 quiz_type '객관식'으로 흡수된다")
    void ingest_binaryFormat_resolvesMultipleChoiceType() {
        givenNotDuplicated();
        QuizType type = multipleType();
        givenMultipleTypeSeed(type);
        givenSaveReturnsArgument();
        QuizCandidate candidate = candidate("KNOWLEDGE", "BINARY",
                List.of(option("A", "류현진"), option("B", "김광현")), "A", null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.LOADED);
        verify(quizTypeRepository).findByName("객관식");
        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getQuizType()).isSameAs(type);
    }

    // ---------- 스킵 경로 ----------

    @Test
    @DisplayName("PREDICTION 후보는 SKIPPED_PREDICTION을 반환하고 어떤 리포지토리도 부르지 않는다")
    void ingest_prediction_skipsWithoutTouchingRepositories() {
        QuizCandidate candidate = candidate("PREDICTION", "OX",
                List.of(option("A", "O"), option("B", "X")), null, null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.SKIPPED_PREDICTION);
        verifyNoInteractions(quizRepository, quizOptionRepository, quizTypeRepository,
                teamRepository, playerRepository, gameRepository);
    }

    @Test
    @DisplayName("externalId가 이미 적재돼 있으면 SKIPPED_DUPLICATE를 반환하고 save를 부르지 않는다")
    void ingest_duplicateExternalId_skipsWithoutSave() {
        given(quizRepository.existsByExternalId(EXTERNAL_ID)).willReturn(true);
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A", null);

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.SKIPPED_DUPLICATE);
        verify(quizRepository, never()).save(any(Quiz.class));
        verifyNoInteractions(quizOptionRepository, quizTypeRepository, teamRepository,
                playerRepository, gameRepository);
    }

    // ---------- subject → 대상 FK ----------

    @Test
    @DisplayName("subject가 없는 구계약(v1) 후보는 상단 teamCodes가 있어도 "
            + "team/opponentTeam/player/game 전부 null로 적재된다")
    void ingest_noSubject_leavesAllTargetFksNull() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        // candidate() 픽스처의 상단 teamCodes = ["HH"] — 귀속 축은 대상 FK 매핑에 쓰이면 안 된다
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A", null);

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getTeam()).isNull();
        assertThat(saved.getOpponentTeam()).isNull();
        assertThat(saved.getPlayer()).isNull();
        assertThat(saved.getGame()).isNull();
        verifyNoInteractions(teamRepository, playerRepository, gameRepository);
    }

    @Test
    @DisplayName("subject PLAYER + teamCodes 1개면 player와 team이 함께 채워진다")
    void ingest_playerSubjectWithOneTeamCode_fillsPlayerAndTeam() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        Team hanwha = team("HH", "한화");
        Player pitcher = player(hanwha);
        given(teamRepository.findByCode("HH")).willReturn(Optional.of(hanwha));
        given(playerRepository.findByKboPlayerId("54260")).willReturn(Optional.of(pitcher));
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("PLAYER", List.of(54260L), List.of("HH"), null));

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getPlayer()).isSameAs(pitcher);
        assertThat(saved.getTeam()).isSameAs(hanwha);
        assertThat(saved.getOpponentTeam()).isNull();
        assertThat(saved.getGame()).isNull();
        assertThat(saved.getQuizDate()).isNull(); // 선수 문항도 게임 귀속이 없으면 풀 대기
    }

    @Test
    @DisplayName("subject PLAYER + teamCodes 빈 배열(CAREER_PATH 정답 유출 방지 케이스)이면 "
            + "player만 채우고 team은 null로 유지한다 — player.getTeam()으로 보강하지 않는다")
    void ingest_playerSubjectWithEmptyTeamCodes_neverEnrichesTeamFromPlayer() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        // 선수 엔티티에는 소속팀이 실재한다 — 그런데도 결과 team 이 null 이어야 보강 금지가 증명된다
        Team ktWiz = team("KT", "KT");
        Player transferred = player(ktWiz);
        given(playerRepository.findByKboPlayerId("54260")).willReturn(Optional.of(transferred));
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("PLAYER", List.of(54260L), List.of(), null));

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getPlayer()).isSameAs(transferred);
        assertThat(saved.getPlayer().getTeam()).isNotNull(); // 선수에겐 팀이 있지만
        assertThat(saved.getTeam()).isNull();                // 문제의 team FK 는 비어 있어야 한다
        assertThat(saved.getOpponentTeam()).isNull();
        verifyNoInteractions(teamRepository);
    }

    @Test
    @DisplayName("subject MATCHUP + teamCodes 2개면 team과 opponentTeam이 순서대로 채워진다")
    void ingest_matchupSubjectWithTwoTeamCodes_fillsTeamAndOpponent() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        Team hanwha = team("HH", "한화");
        Team ktWiz = team("KT", "KT");
        given(teamRepository.findByCode("HH")).willReturn(Optional.of(hanwha));
        given(teamRepository.findByCode("KT")).willReturn(Optional.of(ktWiz));
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("MATCHUP", null, List.of("HH", "KT"), null));

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getTeam()).isSameAs(hanwha);
        assertThat(saved.getOpponentTeam()).isSameAs(ktWiz);
        assertThat(saved.getPlayer()).isNull();
        assertThat(saved.getGame()).isNull();
    }

    @Test
    @DisplayName("MATCHUP의 첫 팀 코드만 해석 실패하면 해석된 팀을 team으로 당겨 채운다 — "
            + "team=null·opponentTeam≠null 불변식 위반 조합을 만들지 않는다")
    void ingest_matchupFirstCodeUnresolved_shiftsResolvedTeamForward() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        Team ktWiz = team("KT", "KT");
        given(teamRepository.findByCode("XX")).willReturn(Optional.empty());
        given(teamRepository.findByCode("KT")).willReturn(Optional.of(ktWiz));
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("MATCHUP", null, List.of("XX", "KT"), null));

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getTeam()).isSameAs(ktWiz);   // 맞대결 팀 순서는 임의라 당겨도 의미 보존
        assertThat(saved.getOpponentTeam()).isNull();
    }

    @Test
    @DisplayName("subject GAME(gameId)이 해석되면 game과 함께 홈=team, 원정=opponentTeam이 채워진다")
    void ingest_gameSubjectResolved_fillsGameAndHomeAwayTeams() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        Team home = team("LG", "LG");
        Team away = team("SK", "SSG");
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 4, 18, 30))
                .homeTeam(home)
                .awayTeam(away)
                .naverGameId("20260804LGSK02026")
                .build();
        given(gameRepository.findByNaverGameId("20260804LGSK02026")).willReturn(Optional.of(game));
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("GAME", null, null, "20260804LGSK02026"));

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getGame()).isSameAs(game);
        assertThat(saved.getTeam()).isSameAs(home);
        assertThat(saved.getOpponentTeam()).isSameAs(away);
        // 경기 문항(시효성)은 풀에 가지 않고 파티션 날짜에 바로 묶인다
        assertThat(saved.getQuizDate()).isEqualTo(QUIZ_DATE);
        // game 이 해석되면 팀 코드 해석 경로는 타지 않는다
        verifyNoInteractions(teamRepository);
    }

    @Test
    @DisplayName("subject의 gameId를 games에서 못 찾으면 예외 없이 game FK만 null로 적재되되, "
            + "quizDate는 그대로 찍힌다 — 해석 실패여도 시효성 문항이 풀에 들어가면 안 된다")
    void ingest_gameIdUnresolved_loadsWithNullGameFkButStampsQuizDate() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        given(gameRepository.findByNaverGameId("20991231XXYY0")).willReturn(Optional.empty());
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A",
                new QuizCandidate.Subject("GAME", null, null, "20991231XXYY0"));

        QuizIngestService.Result result = ingestService.ingest(candidate, QUIZ_DATE);

        assertThat(result).isEqualTo(QuizIngestService.Result.LOADED);
        verify(quizRepository).save(quizCaptor.capture());
        Quiz saved = quizCaptor.getValue();
        assertThat(saved.getGame()).isNull();
        assertThat(saved.getTeam()).isNull();
        assertThat(saved.getOpponentTeam()).isNull();
        assertThat(saved.getQuizDate()).isEqualTo(QUIZ_DATE);
    }

    @Test
    @DisplayName("subject 없이 top-level gameId(귀속 축)만 있어도 게임 귀속 문항으로 보고 "
            + "quizDate를 찍는다")
    void ingest_topLevelGameIdOnly_stampsQuizDate() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        givenSaveReturnsArgument();
        given(gameRepository.findByNaverGameId("20260807HHKT02026")).willReturn(Optional.empty());
        QuizCandidate candidate = new QuizCandidate(EXTERNAL_ID, "20260807HHKT02026", "KNOWLEDGE",
                "MEME_ORIGIN", "MULTI4", "문제 지문?", fourOptions(), "A", "MEDIUM", 30, 2,
                List.of("HH"), null);

        ingestService.ingest(candidate, QUIZ_DATE);

        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getQuizDate()).isEqualTo(QUIZ_DATE);
    }

    // ---------- 계약 위반 → 예외 ----------

    @Test
    @DisplayName("알 수 없는 format이면 IllegalArgumentException을 던지고 save를 부르지 않는다")
    void ingest_unknownFormat_throwsIllegalArgument() {
        givenNotDuplicated();
        QuizCandidate candidate = candidate("KNOWLEDGE", "SHORT_ANSWER", fourOptions(), "A", null);

        assertThatThrownBy(() -> ingestService.ingest(candidate, QUIZ_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 format");
        verify(quizRepository, never()).save(any(Quiz.class));
        verifyNoInteractions(quizOptionRepository);
    }

    @Test
    @DisplayName("answer가 보기 범위를 벗어나면(4지선다에 'E') IllegalArgumentException을 던진다")
    void ingest_answerOutOfOptionRange_throwsIllegalArgument() {
        givenNotDuplicated();
        givenMultipleTypeSeed(multipleType());
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "E", null);

        assertThatThrownBy(() -> ingestService.ingest(candidate, QUIZ_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("보기 범위");
        verify(quizRepository, never()).save(any(Quiz.class));
        verifyNoInteractions(quizOptionRepository);
    }

    @Test
    @DisplayName("quiz_type 시드가 없으면 IllegalStateException을 던진다(임의 유형을 만들지 않는다)")
    void ingest_missingQuizTypeSeed_throwsIllegalState() {
        givenNotDuplicated();
        given(quizTypeRepository.findByName("객관식")).willReturn(Optional.empty());
        QuizCandidate candidate = candidate("KNOWLEDGE", "MULTI4", fourOptions(), "A", null);

        assertThatThrownBy(() -> ingestService.ingest(candidate, QUIZ_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiz_type 시드 없음");
        verify(quizRepository, never()).save(any(Quiz.class));
        verifyNoInteractions(quizOptionRepository);
    }
}
