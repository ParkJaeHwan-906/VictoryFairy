package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.support.entity.UserSupportPlayer;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.dto.QuizVoteCountResponse;
import com.skhynix.quiz.quiz.vote.QuizVoteTally;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizService}를 리포지토리 목으로 단위 검증한다. DB·Spring 컨텍스트 없음.
 *
 * <p>'오늘'({@code quiz_date} 판정 · '오늘 경기' 판정)은 KST 고정 클록으로 결정되므로 {@code Clock.fixed}
 * 를 직접 주입한다. 제출 시한 계산({@link QuizSubmitWindow})은 이 파일(서빙 경로)의 관심사가 아니다 —
 * 그 회귀는 {@code QuizSubmitServiceTest}가 그대로 유지하며 이번 작업으로 바뀌지 않았다.
 *
 * <p><b>경기 기반 선택(계약 {@code docs/requirements/quiz/game-scoped-selection.md}, QUIZ-GSS-1~20)
 * 으로 전면 재작성됨(2026-10-04).</b> 선택 로직은 이제 "오늘 세트 전체를 한 번에 조회"가 아니라
 * {@code QuizRepository}의 classifier 기반 조회 5종({@code findByQuizDateAndClassifierTeam} 등)을
 * 단계적으로 호출하는 캐스케이드다 — 이 리포지토리 메서드들은 전부 목이므로, <b>각 단계가 어떤 메서드를
 * 어떤 파라미터로 부르는지, 그리고 단계 간 "부족분만 채움" 산술이 맞는지가 이 파일의 검증 대상</b>이고,
 * classifier 자체의 SQL 정확성(어떤 퀴즈가 실제로 어느 팀에 속하는지, QUIZ-GSS-14)은 DB 가 필요한
 * 리포지토리 테스트의 몫이라 이 파일 범위 밖이다(하단 보고 참고).
 *
 * <p>일반 퀴즈(team·player·game 전부 null)로 만든 픽스처({@link #quiz(Long, String, String, String,
 * Double)} 5-인자 버전, {@link #manyQuizzes()}, {@link #quizzesWithIds(List)})는 <b>바닥 단계
 * 조회({@code findGeneralQuizzesWithQuizDate}/{@code findGeneralQuizzesByQuizDate})에 붙인다</b> —
 * 필드 모양과 붙이는 조회가 실제 classifier 규칙과 들어맞아야 이 테스트들이 "말이 되는" 상태를
 * 유지한다. 반대로 캐스케이드 자체의 메서드 호출·순서·단락(short-circuit)만 검증하는 테스트는 어떤
 * 조회에 어떤 퀴즈를 붙이든 상관없다(목 리포지토리가 계약의 경계이기 때문) — 그런 테스트는 의도를
 * 분명히 하기 위해 그래도 필드 모양에 맞는 조회를 고른다.
 *
 * <p><b>여러 테스트가 (경기,이닝) 존재 검사({@code existsByUserAccount_IdAndGame_IdAndInning})를 매번
 * false로 정적 스텁한다</b> — 실제 연속 호출이라면 첫 호출이 만든 행 때문에 두 번째 호출이 409가 나야
 * 정확하지만(그 자체는 별도 테스트로 고정한다), 순서·셔플·부분집합 안정성을 다루는 테스트는 그 축과
 * 무관한 관심사를 검증하는 것이라 의도적으로 분리했다.
 *
 * <p><b>Mockito 기본 동작에 의존한다</b>: 명시적으로 스텁하지 않은 {@code List}/{@code Map} 반환
 * 메서드는 빈 컬렉션을, {@code Optional} 반환 메서드는 {@code Optional.empty()}를 돌려준다
 * (Mockito {@code ReturnsEmptyValues}) — 그래서 "이 단계는 후보가 없다"를 표현하는 데 별도 스텁이
 * 필요 없고, {@code MockitoExtension}의 strict stub 검사(미사용 스텁 시 실패)에도 걸리지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final Long USER_ID = 10L;
    // games.naver_game_id — /today 필수 파라미터 값. 내부 PK(GAME_PK)와 다른 값임을 테스트로 드러낸다.
    private static final String GAME_ID = "20260807HHKT02026";
    private static final Long GAME_PK = 500L;
    private static final int DEFAULT_INNING = 5;
    // 프로덕션 기본값(quiz.serve.max-today-count)과 같은 값 — 기존 케이스는 세트가 이보다 훨씬
    // 작아 상한이 발동하지 않는다. 상한 자체의 케이스는 별도로 채운다.
    private static final int MAX_TODAY_COUNT = 20;

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizOptionRepository quizOptionRepository;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private UserSupportPlayerRepository userSupportPlayerRepository;

    @Mock
    private QuizUserSubmitRepository quizUserSubmitRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private QuizLikeService quizLikeService;

    @Mock
    private QuizVoteTally quizVoteTally;

    private QuizService quizService;
    private Clock clock;
    private Team homeTeam;
    private Team awayTeam;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(ZonedDateTime.of(TODAY.atTime(12, 0), KST).toInstant(), KST);
        quizService = newQuizService(MAX_TODAY_COUNT);
        homeTeam = team(100L, "HH", "한화");
        awayTeam = team(101L, "KT", "KT");
    }

    private QuizService newQuizService(int maxTodayCount) {
        return new QuizService(quizRepository, quizOptionRepository,
                userSupportTeamRepository, userSupportPlayerRepository, quizUserSubmitRepository,
                gameRepository, quizLikeService, quizVoteTally, clock, maxTodayCount);
    }

    // ---------- 픽스처 ----------

    private static Team team(Long id, String code, String name) {
        Team team = Team.builder().code(code).name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static Player player(Long id, Team team) {
        Player player = Player.builder().team(team).name("문동주").average(0.0)
                .kboPlayerId("54260").build();
        ReflectionTestUtils.setField(player, "id", id);
        return player;
    }

    private Quiz quiz(Long id, String typeName, String content, String difficulty, Double score) {
        return quiz(id, typeName, content, difficulty, score, null, null, null, null);
    }

    private Quiz quiz(Long id, String typeName, String content, String difficulty, Double score,
            Team team, Team opponentTeam, Player player) {
        return quiz(id, typeName, content, difficulty, score, team, opponentTeam, player, null);
    }

    private Quiz quiz(Long id, String typeName, String content, String difficulty, Double score,
            Team team, Team opponentTeam, Player player, Game game) {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name(typeName).build())
                .team(team)
                .opponentTeam(opponentTeam)
                .player(player)
                .game(game)
                .content(content)
                .answer(0)
                .quizDate(TODAY)
                .difficulty(difficulty)
                .point(score)
                .build();
        ReflectionTestUtils.setField(quiz, "id", id);
        return quiz;
    }

    /** bq(레이팅 축)까지 지정하는 버전 — QUIZ-PBQ-26·27(/today의 bq 노출) 전용. */
    private Quiz quizWithBq(Long id, String typeName, String content, String difficulty,
            Double score, Integer bq) {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name(typeName).build())
                .content(content)
                .answer(0)
                .quizDate(TODAY)
                .difficulty(difficulty)
                .point(score)
                .bq(bq)
                .build();
        ReflectionTestUtils.setField(quiz, "id", id);
        return quiz;
    }

    private QuizOption option(Quiz quiz, int no, String text) {
        return QuizOption.builder().quiz(quiz).option(no).contents(text).build();
    }

    private Game game(Long id, String naverGameId, LocalDateTime gameDate, Team home, Team away,
            String statusName, Integer currentInning) {
        Game game = Game.builder()
                .naverGameId(naverGameId)
                .gameDate(gameDate)
                .homeTeam(home)
                .awayTeam(away)
                .gameStatus(GameStatus.builder().name(statusName).build())
                .currentInning(currentInning)
                .build();
        ReflectionTestUtils.setField(game, "id", id);
        return game;
    }

    /** 오늘·홈=한화·원정=KT·IN_PROGRESS인 기본 경기(팀 검증만 남겨 둔 상태). */
    private Game defaultServableGame(int inning) {
        return game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam,
                "IN_PROGRESS", inning);
    }

    private void givenGame(Game game) {
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.of(game));
    }

    private void givenSupportTeam(Team team) {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID))
                .willReturn(Optional.of(UserSupportTeam.builder().team(team).build()));
    }

    private void givenNoSupportTeam() {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID))
                .willReturn(Optional.empty());
    }

    private void givenSupportPlayers(Player... players) {
        given(userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(USER_ID))
                .willReturn(List.of(players).stream()
                        .map(player -> UserSupportPlayer.builder().player(player).build())
                        .toList());
    }

    private void givenNotServedThisInning(Game game, int inning) {
        given(quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                USER_ID, game.getId(), inning)).willReturn(false);
    }

    private void givenAlreadyServedThisInning(Game game, int inning) {
        given(quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                USER_ID, game.getId(), inning)).willReturn(true);
    }

    /** 세트 제공 가능 상태(경기·응원 구단=homeTeam·회차)를 한 번에 갖춘다 — "응원 구단 경기" 경로(C절). */
    private Game givenServable(int inning) {
        Game game = defaultServableGame(inning);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(game, inning);
        return game;
    }

    /** 일반 퀴즈(team·player·game 전부 null) id 목록 — 바닥 단계(findGeneralQuizzes*) 전용 픽스처. */
    private List<Quiz> quizzesWithIds(List<Long> ids) {
        return ids.stream()
                .map(id -> quiz(id, "객관식", "문제" + id, "EASY", 10.0))
                .toList();
    }

    /** 그룹 내 순열이 갈릴 만큼 충분한 개수(10건)의 일반 퀴즈 — 전부 바닥 단계 전용 픽스처다. */
    private List<Quiz> manyQuizzes() {
        return quizzesWithIds(IntStream.rangeClosed(1, 10).mapToObj(i -> (long) i).toList());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<Long>> quizIdsCaptor() {
        return (ArgumentCaptor<Collection<Long>>) (ArgumentCaptor<?>) ArgumentCaptor
                .forClass(Collection.class);
    }

    // ---------- 오늘의 퀴즈: 2쿼리 방식 (일반 퀴즈 바닥 단계를 통해 흘러들어온 경우) ----------

    @Test
    @DisplayName("오늘 문제가 2건이면 보기를 IN 한 방으로 받아 문제별로 묶어 반환한다(2쿼리 방식)")
    void getTodayQuizzes_twoQuizzes_groupsOptionsPerQuizWithSingleInQuery() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        Quiz multiQuiz = quiz(2L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(List.of(oxQuiz, multiQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(
                        option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X"),
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(2);
        Map<Long, QuizResponse> byId = result.stream()
                .collect(Collectors.toMap(QuizResponse::id, r -> r));
        assertThat(byId.get(1L).type()).isEqualTo("O/X");
        assertThat(byId.get(1L).question()).isEqualTo("문동주는 한화 소속이다?");
        assertThat(byId.get(1L).difficulty()).isEqualTo("EASY");
        assertThat(byId.get(1L).point()).isEqualTo(10.0);
        assertThat(byId.get(1L).options())
                .extracting(QuizResponse.TodayOptionResponse::no, QuizResponse.TodayOptionResponse::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "O"),
                        org.assertj.core.groups.Tuple.tuple(1, "X"));
        assertThat(byId.get(2L).type()).isEqualTo("객관식");
        assertThat(byId.get(2L).options())
                .extracting(QuizResponse.TodayOptionResponse::no)
                .containsExactly(0, 1, 2, 3);

        ArgumentCaptor<List<Long>> quizIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(quizOptionRepository)
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIdsCaptor.capture());
        assertThat(quizIdsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("보기 행이 없는 문제는 빈 options로 응답한다(NPE 없이 getOrDefault 흡수 — 요구사항 미기재 경계)")
    void getTodayQuizzes_quizWithoutOptionRows_returnsEmptyOptions() {
        Quiz orphan = quiz(7L, "객관식", "보기가 아직 없는 문제", null, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(orphan));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(7L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).options()).isEmpty();
        assertThat(result.get(0).point()).isNull();
        assertThat(result.get(0).difficulty()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-PBQ-26] /today 응답 항목에 bq가 JSON 정수로 실린다")
    void getTodayQuizzes_quizWithBq_exposesBqAsInteger() {
        Quiz quizWithBq = quizWithBq(1L, "객관식", "bq가 있는 문제", "HARD", 80.0, 3);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(quizWithBq));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result.get(0).bq()).isEqualTo(3);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-27] /today 응답 항목의 bq가 NULL이면 키는 남고 값만 null이다 — point가 NULL일 "
            + "때와 같은 규칙(이 응답에는 @JsonInclude가 없다)")
    void getTodayQuizzes_quizWithNullBq_keepsKeyWithNullValue() {
        Quiz quizWithoutBq = quizWithBq(1L, "객관식", "bq가 없는 문제", "HARD", 80.0, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(quizWithoutBq));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result.get(0).bq()).isNull();
        // record 자체는 값을 null로 담을 뿐, "키가 실제로 응답에 남는다"는 직렬화 단언은
        // QuizControllerTest(@JsonInclude 부재 확인)가 맡는다 — 여기는 서비스 계층 값 조립만 검증
    }

    // ---------- 오늘의 퀴즈: preferred 필드 계산(isPreferred) ----------
    // GSS 문서는 classifier(선택 캐스케이드가 어느 단계에서 퍼오는가)와 isPreferred(응답에 실리는
    // 표시용 플래그)를 재정의하지 않는다 — isPreferred 계산 자체는 QuizService에 그대로 남아 있고
    // (quiz.team 또는 quiz.opponentTeam이 응원 구단과 같거나, quiz.player가 응원 선수면 true),
    // 이 절은 그 계산이 새 캐스케이드 아래서도 그대로 유지됨을 회귀 고정한다.

    @Test
    @DisplayName("응원 구단이 quiz.team과 같은 문제(1단계에서 선택)는 preferred=true, 일반 퀴즈"
            + "(바닥 단계에서 선택)는 preferred=false다")
    void getTodayQuizzes_teamMatchesPreferredStage_isPreferredTrue() {
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of(teamQuiz));
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(general));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        Map<Long, Boolean> preferredById = result.stream()
                .collect(Collectors.toMap(QuizResponse::id, QuizResponse::preferred));
        assertThat(preferredById.get(2L)).isTrue();
        assertThat(preferredById.get(1L)).isFalse();
    }

    @Test
    @DisplayName("응원 선수가 quiz.player와 같은 문제(classifier=player.team=응원 구단이라 1단계에서 "
            + "선택됨)는 preferred=true다")
    void getTodayQuizzes_supportPlayerMatches_isPreferredTrue() {
        Player moonDongJu = player(200L, homeTeam);
        Quiz playerQuiz = quiz(2L, "객관식", "문동주 문제", "EASY", 10.0, null, null, moonDongJu);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of(playerQuiz));
        givenSupportPlayers(moonDongJu);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result.get(0).preferred()).isTrue();
    }

    @Test
    @DisplayName("상대팀 자체 문제(opponentTeam 필드 없음, 3단계에서 선택됨)는 preferred=false다")
    void getTodayQuizzes_opponentTeamOnlyQuiz_isPreferredFalse() {
        Quiz opponentOnly = quiz(3L, "객관식", "KT 단독 문제", "EASY", 10.0, awayTeam, null, null);
        givenServable(DEFAULT_INNING); // 응원=한화(홈) → 상대=KT(원정)
        // 1단계(오늘·선호팀)도 같은 메서드를 다른 teamId로 호출하므로, strict stub 불일치 경고를 피하려면
        // 명시적으로 빈 결과를 스텁해 둬야 한다(미스텁 시 Mockito가 "인자 불일치 의심"으로 거절한다).
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of());
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId()))
                .willReturn(List.of(opponentOnly)); // 3단계(오늘·상대팀)
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result.get(0).preferred()).isFalse();
    }

    @Test
    @DisplayName("[요구사항 미기재 경계] 맞대결 문제(quiz.team=상대팀이라 classifier상 3단계에서 선택되지만 "
            + "opponentTeam=응원 구단)는 isPreferred가 opponentTeam 필드도 보므로 여전히 preferred=true다 "
            + "— 선택 계층(classifier)과 표시 플래그(isPreferred) 판정 기준이 다르다는 비대칭을 고정한다")
    void getTodayQuizzes_matchupQuizSelectedViaOpponentStage_stillPreferredTrue() {
        // classifier(quiz.team=awayTeam=상대) 기준으로는 3단계 후보지만, opponentTeam=homeTeam(응원
        // 구단)이라 isPreferred 판정에서는 true가 된다 — QUIZ-GSS-14 AC-GSS-14-2(맞대결 문제는 선택
        // 단계에서 opponentTeam을 안 본다)와 기존 isPreferred(두 필드 다 본다) 규칙이 공존하는 지점.
        Quiz matchupQuiz = quiz(3L, "객관식", "KT vs 한화 문제", "EASY", 10.0, awayTeam, homeTeam, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of()); // 1단계 — strict stub 불일치 경고 방지(위 테스트와 같은 이유)
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId()))
                .willReturn(List.of(matchupQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result.get(0).preferred()).isTrue();
    }

    // ---------- 오늘의 퀴즈: preferredOnly 무력화(QUIZ-GSS-13) ----------

    @Test
    @DisplayName("[AC-GSS-13-1,13-2] preferredOnly=true/false 결과가 완전히 같다 — 새 캐스케이드가 "
            + "필터 역할을 대체해 파라미터는 더 이상 결과에 영향을 주지 않는다(하위 호환으로만 존재)")
    void getTodayQuizzes_preferredOnlyTrueOrFalse_returnsIdenticalResult() {
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of(teamQuiz));
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(general));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> withTrue = quizService.getTodayQuizzes(USER_ID, GAME_ID, true).stream()
                .map(QuizResponse::id).toList();
        // (경기,이닝) 존재 검사를 정적으로 false 스텁했으므로 두 번째 호출도 409 없이 통과한다
        // (클래스 상단 javadoc 참고) — preferredOnly 축과 회차 제한 축을 분리하려는 의도적 설계.
        List<Long> withFalse = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(withTrue).containsExactlyInAnyOrder(1L, 2L);
        assertThat(withTrue).containsExactlyInAnyOrderElementsOf(withFalse);
    }

    // ---------- A절: 경기 분류(QUIZ-GSS-1) ----------

    @Test
    @DisplayName("[AC-GSS-1-1] 응원 구단이 기준 경기의 home_team과 같으면 응원 구단 경기로 분류돼 "
            + "1단계(선호팀=home)·3단계(상대팀=away) 조회가 나간다 — 무관한 경기 조회는 나가지 않는다")
    void getTodayQuizzes_supportHomeTeam_classifiedAsSupportGame() {
        givenServable(DEFAULT_INNING);

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        verify(quizRepository).findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId());
        verify(quizRepository).findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId());
        verify(quizRepository, never())
                .findByQuizDateAndClassifierTeamIn(any(), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("[AC-GSS-1-1] 응원 구단이 away_team과 같아도 응원 구단 경기다 — 선호팀=away·상대팀=home으로 "
            + "역할이 뒤집힐 뿐 분류 자체는 동일하다")
    void getTodayQuizzes_supportAwayTeam_classifiedAsSupportGame() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(awayTeam);
        givenNotServedThisInning(game, DEFAULT_INNING);

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        verify(quizRepository).findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId()); // 1단계(선호=away)
        verify(quizRepository).findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()); // 3단계(상대=home)
        verify(quizRepository, never())
                .findByQuizDateAndClassifierTeamIn(any(), org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("[AC-GSS-1-2] 응원 구단이 기준 경기의 홈·어웨이 어느 쪽과도 다르면 무관한 경기로 분류돼 "
            + "D절 조회(양팀 IN)만 나간다 — C절 1~4단계 조회는 전혀 나가지 않는다")
    void getTodayQuizzes_supportThirdTeam_classifiedAsUnrelatedGame() {
        Team samsung = team(300L, "SS", "삼성");
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(samsung);
        givenNotServedThisInning(game, DEFAULT_INNING);

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        verify(quizRepository).findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId()));
        verify(quizRepository, never()).findByQuizDateAndClassifierTeam(any(), any());
        verify(quizRepository, never()).findByOtherQuizDateAndClassifierTeam(any(), any());
    }

    @Test
    @DisplayName("[AC-GSS-1-3] 응원 구단이 없으면 무관한 경기로 분류된다(QUIZ-INN-87 폐기 — 더 이상 "
            + "403이 아니다) — 예외 없이 D절 경로로 처리된다")
    void getTodayQuizzes_noSupportTeam_classifiedAsUnrelatedGameWithoutException() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);

        assertThatCode(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .doesNotThrowAnyException();
        verify(quizRepository).findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId()));
    }

    // ---------- QUIZ-GSS-2: 정책 전환(QUIZ-INN-109 폐기) ----------

    @Test
    @DisplayName("[AC-GSS-2-1] 응원 구단과 무관한 오늘 IN_PROGRESS 경기를 지목해도 더 이상 403이 아니다 "
            + "— AC-INN-109-3(남의 팀 경기는 403)의 정반대")
    void getTodayQuizzes_unrelatedInProgressGame_doesNotThrowNotServable() {
        Team samsung = team(300L, "SS", "삼성");
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(samsung);
        givenNotServedThisInning(game, DEFAULT_INNING);

        assertThatCode(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[AC-GSS-2-3] QUIZ_NOT_SERVABLE 자체는 남아 있다 — 응원 구단과 무관해도 경기가 "
            + "IN_PROGRESS가 아니면 여전히 403이다(다른 사유로는 계속 거절)")
    void getTodayQuizzes_unrelatedTeamButGameNotInProgress_stillThrowsNotServable() {
        Team samsung = team(300L, "SS", "삼성");
        Game game = game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam,
                "SCHEDULED", null);
        givenGame(game);
        givenSupportTeam(samsung);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
    }

    // ---------- QUIZ-GSS-3: 기존 제외 규칙·회차 제한 선행 ----------

    @Test
    @DisplayName("[AC-GSS-3-1] 이미 받은 문제는 \"다른 날짜\"(2단계) 후보에서도 동일하게 제외된다 — "
            + "1단계가 전부 제외되고 2단계도 일부만 남으면 캐스케이드가 계속 이어져 부족분을 채운다")
    void getTodayQuizzes_servedExclusion_appliesToOtherDateStageToo() {
        QuizService limited = newQuizService(3);
        givenServable(DEFAULT_INNING);
        List<Quiz> stage1 = quizzesWithIds(List.of(1L, 2L)); // 둘 다 이미 받은 상태로 스텁
        List<Quiz> stage2 = quizzesWithIds(List.of(101L, 102L, 103L)); // 2건 이미 받음, 1건만 신규
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(stage1);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L, 2L));
        given(quizRepository.findByOtherQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(stage2);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(101L, 102L, 103L)))
                .willReturn(List.of(101L, 102L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(103L);
    }

    @Test
    @DisplayName("[AC-GSS-3-2] 그 (경기,이닝)에 이미 세트를 받았으면 선택 로직 자체가 실행되지 않고 409 "
            + "QUIZ_ALREADY_SERVED_IN_INNING이다(QUIZ-INN-95 불변)")
    void getTodayQuizzes_alreadyServedThisInning_throws409AndCreatesNoRows() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenAlreadyServedThisInning(game, DEFAULT_INNING);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_ALREADY_SERVED_IN_INNING);
        verifyNoInteractions(quizRepository, quizOptionRepository, userSupportPlayerRepository);
        verify(quizUserSubmitRepository, never()).insertUnansweredRows(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // ---------- C절: 응원 구단 경기 — 4단계 날짜×팀 캐스케이드(QUIZ-GSS-15~18·20·19) ----------

    @Test
    @DisplayName("[QUIZ-GSS-15, AC-GSS-15-1,15-2] 1단계(오늘·선호팀) 후보만으로 목표 수를 채우면 "
            + "2~5단계는 후보 조회조차 하지 않는다")
    void getTodayQuizzes_stage1AloneFillsTarget_skipsLaterStages() {
        QuizService limited = newQuizService(3);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(quizzesWithIds(List.of(1L, 2L, 3L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        verify(quizRepository, never()).findByOtherQuizDateAndClassifierTeam(any(), any());
        verify(quizRepository, never()).findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId());
        verify(quizRepository, never()).findGeneralQuizzesWithQuizDate();
    }

    @Test
    @DisplayName("[QUIZ-GSS-16, AC-GSS-16-1,16-2] 1단계가 부족하면 2단계(다른 날짜·선호팀)가 부족분만 "
            + "채운다 — 1단계 결과 + 2단계 추가분이 목표 수를 넘지 않는다")
    void getTodayQuizzes_stage1Insufficient_stage2FillsRemainderOnly() {
        QuizService limited = newQuizService(5);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(quizzesWithIds(List.of(1L, 2L))); // 2건뿐
        given(quizRepository.findByOtherQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(quizzesWithIds(List.of(101L, 102L, 103L, 104L, 105L))); // 5건 중 3건만 필요
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5);
        List<Long> ids = result.stream().map(QuizResponse::id).toList();
        assertThat(ids).contains(1L, 2L); // 1단계 결과는 전부 포함
        assertThat(ids.stream().filter(id -> id >= 101L).count()).isEqualTo(3); // 2단계는 3건만
        verify(quizRepository, never()).findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId());
        verify(quizRepository, never()).findGeneralQuizzesWithQuizDate();
    }

    @Test
    @DisplayName("[QUIZ-GSS-17, AC-GSS-17-1,17-2] 1·2단계를 합쳐도 부족하면 3단계(오늘·상대팀)가 "
            + "부족분을 채운다")
    void getTodayQuizzes_stage1And2Insufficient_stage3Fills() {
        QuizService limited = newQuizService(4);
        givenServable(DEFAULT_INNING);
        // 1단계(오늘·선호팀)가 3단계와 같은 메서드를 다른 teamId로 먼저 호출한다 — strict stub 불일치
        // 경고를 피하려면 명시적으로 빈 결과를 스텁한다.
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of());
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, awayTeam.getId()))
                .willReturn(quizzesWithIds(List.of(201L, 202L, 203L, 204L, 205L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(QuizResponse::id)
                .allMatch(id -> id >= 201L && id <= 205L);
        verify(quizRepository, never()).findByOtherQuizDateAndClassifierTeam(TODAY, awayTeam.getId());
        verify(quizRepository, never()).findGeneralQuizzesWithQuizDate();
    }

    @Test
    @DisplayName("[QUIZ-GSS-18, AC-GSS-18-1,18-2] 1~3단계를 합쳐도 부족하면 4단계(다른 날짜·상대팀)가 "
            + "부족분을 채운다")
    void getTodayQuizzes_stage1To3Insufficient_stage4Fills() {
        QuizService limited = newQuizService(3);
        givenServable(DEFAULT_INNING);
        // 2단계(다른 날짜·선호팀)가 4단계와 같은 메서드를 다른 teamId로 먼저 호출한다 — strict stub
        // 불일치 경고를 피하려면 명시적으로 빈 결과를 스텁한다.
        given(quizRepository.findByOtherQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of());
        given(quizRepository.findByOtherQuizDateAndClassifierTeam(TODAY, awayTeam.getId()))
                .willReturn(quizzesWithIds(List.of(301L, 302L, 303L, 304L, 305L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(QuizResponse::id)
                .allMatch(id -> id >= 301L && id <= 305L);
        verify(quizRepository, never()).findGeneralQuizzesWithQuizDate();
    }

    @Test
    @DisplayName("[QUIZ-GSS-20(C절), AC-GSS-20-1,20-2] 1~4단계를 합쳐도 부족하면 5단계(바닥, 일반 퀴즈)가 "
            + "부족분만 채운다")
    void getTodayQuizzes_stage1To4Insufficient_floorStageFills() {
        QuizService limited = newQuizService(2);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(quizzesWithIds(List.of(401L, 402L, 403L, 404L, 405L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(QuizResponse::id)
                .allMatch(id -> id >= 401L && id <= 405L);
    }

    @Test
    @DisplayName("[QUIZ-GSS-19, AC-GSS-19-1] 1~5단계를 전부 채워도 목표 수에 못 미치면 에러 없이 모은 "
            + "만큼(목표 수 미만)을 200으로 반환한다")
    void getTodayQuizzes_allStagesInsufficient_returnsPartialResultWithoutError() {
        QuizService limited = newQuizService(10);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(quizzesWithIds(List.of(1L, 2L)));
        given(quizRepository.findByOtherQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(quizzesWithIds(List.of(101L, 102L)));
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(quizzesWithIds(List.of(401L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5); // 2+2+0+0+1, 목표 10에 못 미쳐도 에러 없음
    }

    @Test
    @DisplayName("[QUIZ-GSS-19, AC-GSS-19-2] 5단계까지 합쳐 0건이면 빈 배열이 응답이다(기존 "
            + "QUIZ-INN-113과 같은 결)")
    void getTodayQuizzes_allStagesEmpty_returnsEmptyArray() {
        givenServable(DEFAULT_INNING);

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportPlayerRepository, quizVoteTally);
    }

    // ---------- D절: 무관한 경기 — 랜덤 채움(QUIZ-GSS-8) ----------

    @Test
    @DisplayName("[QUIZ-GSS-8, AC-GSS-8-1] 무관한 경기는 양팀(홈·어웨이) classifier IN 조회 하나로 "
            + "목표 수를 채운다 — C절의 날짜 캐스케이드(1~4단계) 조회는 전혀 나가지 않는다")
    void getTodayQuizzes_unrelatedGame_fillsFromTeamInQuery() {
        QuizService limited = newQuizService(5);
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId())))
                .willReturn(quizzesWithIds(List.of(501L, 502L, 503L, 504L, 505L, 506L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5);
        assertThat(result).extracting(QuizResponse::id).allMatch(id -> id >= 501L && id <= 506L);
        verify(quizRepository, never()).findByQuizDateAndClassifierTeam(any(), any());
        verify(quizRepository, never()).findByOtherQuizDateAndClassifierTeam(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-GSS-8, AC-GSS-8-3] 양팀 조회만으로 부족하면 바닥 단계(오늘 세트 한정 일반 퀴즈)가 "
            + "부족분을 채운다")
    void getTodayQuizzes_unrelatedGame_insufficientFillsFromTodayFloor() {
        QuizService limited = newQuizService(5);
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId())))
                .willReturn(quizzesWithIds(List.of(501L, 502L)));
        given(quizRepository.findGeneralQuizzesByQuizDate(TODAY))
                .willReturn(quizzesWithIds(List.of(601L, 602L, 603L, 604L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5);
        List<Long> ids = result.stream().map(QuizResponse::id).toList();
        assertThat(ids).contains(501L, 502L);
        assertThat(ids.stream().filter(id -> id >= 601L).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("[QUIZ-GSS-20(D절), AC-GSS-20-4] 양팀 조회만으로 목표 수를 채우면 바닥 단계는 "
            + "후보 조회조차 하지 않는다")
    void getTodayQuizzes_unrelatedGame_teamInAloneFillsTarget_skipsFloor() {
        QuizService limited = newQuizService(3);
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId())))
                .willReturn(quizzesWithIds(List.of(501L, 502L, 503L)));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(3);
        verify(quizRepository, never()).findGeneralQuizzesByQuizDate(any());
    }

    // ---------- QUIZ-GSS-9: 사용자별 고정 시드(결정성) ----------

    @Test
    @DisplayName("[QUIZ-GSS-9, AC-GSS-9-1] 응원 구단 경기 경로 — 같은 사용자로 두 번 호출하면 순서가 "
            + "동일하다((경기,이닝) 존재 검사는 두 호출 모두 false로 정적 스텁해 회차 제한 축과 분리한다)")
    void getTodayQuizzes_supportGame_calledTwiceForSameUser_returnsSameOrder() {
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(manyQuizzes());
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> firstCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();
        List<Long> secondCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(secondCall).containsExactlyElementsOf(firstCall);
    }

    @Test
    @DisplayName("[QUIZ-GSS-9, AC-GSS-9-1] 무관한 경기 경로 — 같은 사용자로 두 번 호출하면 순서가 "
            + "동일하다")
    void getTodayQuizzes_unrelatedGame_calledTwiceForSameUser_returnsSameOrder() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId()))).willReturn(manyQuizzes());
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> firstCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();
        List<Long> secondCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(secondCall).containsExactlyElementsOf(firstCall);
    }

    @Test
    @DisplayName("[QUIZ-GSS-9, AC-GSS-9-2] 서로 다른 계정은 셔플 순서가 다르다 — 문제 수가 충분(10건)해 "
            + "순열이 갈릴 만큼 우연히 전부 같을 확률은 무시할 만하다")
    void getTodayQuizzes_differentAccounts_produceDifferentOrders() {
        List<Quiz> quizzes = manyQuizzes();
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of());
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> accountIds = List.of(USER_ID, 20L, 300L);
        List<List<Long>> orders = accountIds.stream()
                .map(accountId -> {
                    given(userSupportTeamRepository
                            .findWithTeamByUserAccount_IdAndOpposeIsNull(accountId))
                            .willReturn(Optional.of(UserSupportTeam.builder().team(homeTeam).build()));
                    given(quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                            accountId, game.getId(), DEFAULT_INNING)).willReturn(false);
                    return quizService.getTodayQuizzes(accountId, GAME_ID, false).stream()
                            .map(QuizResponse::id).toList();
                })
                .toList();

        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("문제 하나를 제외해도 남은 문제들의 상대 순서는 그대로 보존된다(부분집합 안정성 — "
            + "Collections.shuffle이었다면 깨지는 성질)")
    void getTodayQuizzes_afterExcludingOneQuiz_preservesRelativeOrderOfRemaining() {
        List<Quiz> quizzes = manyQuizzes();
        List<Long> allIds = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> fullOrder = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        Long excludedId = fullOrder.get(3);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, allIds))
                .willReturn(List.of(excludedId));
        List<Long> orderAfterExclusion = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        List<Long> expectedRemaining = fullOrder.stream()
                .filter(id -> !id.equals(excludedId))
                .toList();
        assertThat(orderAfterExclusion).containsExactlyElementsOf(expectedRemaining);
    }

    // ---------- E절: 목표 수 상한 공유(QUIZ-GSS-12) ----------

    @Test
    @DisplayName("[QUIZ-GSS-12, AC-GSS-12-1,12-2] 응원 구단 경기 경로 — 조건을 만족하는 문제가 25건이면 "
            + "상한 20에서 잘려 길이 20으로 응답한다")
    void getTodayQuizzes_supportGame_moreThanCap_truncatesToMaxTodayCount() {
        List<Quiz> quizzes = quizzesWithIds(IntStream.rangeClosed(1, 25).mapToObj(i -> (long) i).toList());
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(20);
    }

    @Test
    @DisplayName("[QUIZ-GSS-12, AC-GSS-12-2] 무관한 경기 경로도 같은 상한(quiz.serve.max-today-count)을 "
            + "공유한다 — 생성자 인자를 5로 낮추면 D절에서도 5건만 반환된다")
    void getTodayQuizzes_unrelatedGame_sharesConfiguredMaxTodayCount() {
        QuizService limited = newQuizService(5);
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeamIn(TODAY,
                List.of(homeTeam.getId(), awayTeam.getId()))).willReturn(manyQuizzes());
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limited.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("[AC-INN-6-2] 19건이면 상한 20이 발동하지 않아 그대로 19건이다")
    void getTodayQuizzes_underCap_returnsAllWithoutTruncation() {
        List<Quiz> quizzes = quizzesWithIds(IntStream.rangeClosed(1, 19).mapToObj(i -> (long) i).toList());
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(19);
    }

    @Test
    @DisplayName("[AC-INN-8-1,8-2] 상한은 정렬·필터가 끝난 목록의 앞에서부터 자른다 — 상한 적용 결과는 "
            + "상한 없는 전체 순서의 앞부분과 정확히 일치한다(정렬을 흐트러뜨리는 회귀 방지)")
    void getTodayQuizzes_capTruncatesFromSortedOrderPrefix() {
        List<Quiz> quizzes = manyQuizzes();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> fullOrder = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        QuizService cappedService = newQuizService(6);
        List<Long> cappedOrder = cappedService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(cappedOrder).containsExactlyElementsOf(fullOrder.subList(0, 6));
    }

    @Test
    @DisplayName("[AC-INN-61-2] 상한으로 잘려 나간 문제에는 행이 생기지 않는다 — insertUnansweredRows에 "
            + "실리는 대상은 실제로 응답에 실린 문제와 정확히 같다")
    void getTodayQuizzes_issuesRowsOnlyForServedQuizzes() {
        QuizService cappedService = newQuizService(6);
        List<Quiz> quizzes = manyQuizzes(); // 10건
        Game game = givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = cappedService.getTodayQuizzes(USER_ID, GAME_ID, false);

        ArgumentCaptor<Collection<Long>> insertedCaptor = quizIdsCaptor();
        verify(quizUserSubmitRepository).insertUnansweredRows(
                org.mockito.ArgumentMatchers.eq(USER_ID), insertedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(game.getId()),
                org.mockito.ArgumentMatchers.eq(DEFAULT_INNING), any());
        assertThat(insertedCaptor.getValue())
                .containsExactlyInAnyOrderElementsOf(result.stream().map(QuizResponse::id).toList());
        assertThat(insertedCaptor.getValue()).hasSize(6);
    }

    // ---------- T절: 이닝의 원천 (QUIZ-INN-83~94, 캐스케이드 도입 이후에도 불변) ----------

    @Test
    @DisplayName("[AC-INN-83-1,83-2,83-3,84-1,84-2,84-3,84-4] 한 요청으로 만들어지는 모든 행은 문제의 "
            + "game 연관·선택 단계와 무관하게 전부 같은 inning(기준 경기의 current_inning)을 갖는다 — "
            + "1단계(선호팀)에서 온 문제와 바닥 단계(일반 퀴즈)에서 온 문제도 예외 없이 같은 이닝을 받는다")
    void getTodayQuizzes_allNewRowsShareBaseGameInning_regardlessOfSelectionStage() {
        Game baseGame = defaultServableGame(7);
        givenGame(baseGame);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(baseGame, 7);
        Game otherGame = game(600L, "20260807XXYY02026", TODAY.atTime(14, 0), homeTeam, awayTeam,
                "IN_PROGRESS", 3); // 문제가 귀속된, 기준 경기와는 다른 경기 — 이닝도 다르다(3)
        Quiz noGameQuiz = quiz(1L, "객관식", "경기 미귀속 문제", "EASY", 10.0); // 일반 퀴즈 → 바닥 단계
        Quiz otherGameQuiz = quiz(2L, "객관식", "다른 경기 귀속 문제", "EASY", 10.0,
                homeTeam, awayTeam, null, otherGame); // classifier=home → 1단계
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of(otherGameQuiz));
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(noGameQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        ArgumentCaptor<Collection<Long>> insertedCaptor = quizIdsCaptor();
        verify(quizUserSubmitRepository).insertUnansweredRows(
                org.mockito.ArgumentMatchers.eq(USER_ID), insertedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(baseGame.getId()),
                org.mockito.ArgumentMatchers.eq(7), // 기준 경기 이닝 — otherGame의 이닝(3)이 아니다
                any());
        assertThat(insertedCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @ParameterizedTest(name = "상태가 {0}이면 세트를 제공하지 않는다")
    @ValueSource(strings = {"SCHEDULED", "FINISHED", "DRAW", "CANCELED"})
    @DisplayName("[AC-INN-86-1,86-2,86-4,90-1,90-3] IN_PROGRESS가 아닌 경기(시작 전·종료·무승부·취소)는 "
            + "전부 403 QUIZ_NOT_SERVABLE이고 미답 행이 생기지 않는다 — 취소도 cancel_reason을 별도로 "
            + "읽지 않고 상태 문자열 하나로 판정된다(86에 포섭)")
    void getTodayQuizzes_gameNotInProgress_throwsNotServableAndCreatesNoRows(String statusName) {
        Game game = game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam, statusName, null);
        givenGame(game);
        givenSupportTeam(homeTeam);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
        verifyNoInteractions(quizRepository, quizUserSubmitRepository, quizOptionRepository);
    }

    @Test
    @DisplayName("[AC-INN-86-3] game_statuses.id가 다른 상태의 리터럴(실측 prod 1=FINISHED)과 같아도 "
            + "name이 IN_PROGRESS면 서빙된다 — id가 아니라 name으로 판정한다는 계약의 직접 증거")
    void getTodayQuizzes_statusIdMismatchesConventionalLiteral_stillServesByName() {
        Game game = defaultServableGame(DEFAULT_INNING);
        ReflectionTestUtils.setField(game.getGameStatus(), "id", 1L);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(game, DEFAULT_INNING);

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty(); // 예외 없이 통과했다는 것 자체가 id 무관 판정의 증거
    }

    @Test
    @DisplayName("[AC-INN-92-1,92-2,92-4] 응답 항목이 20건이어도 경기·응원 구단 조회는 요청당 정확히 1회다")
    void getTodayQuizzes_gameAndTeamLookups_happenOnceFor20Items() {
        List<Quiz> quizzes = quizzesWithIds(IntStream.rangeClosed(1, 20).mapToObj(i -> (long) i).toList());
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(20);
        verify(gameRepository, times(1)).findWithStatusByNaverGameId(GAME_ID);
        verify(userSupportTeamRepository, times(1))
                .findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID);
    }

    @Test
    @DisplayName("[AC-INN-92-1] 응답 항목이 1건일 때도 경기·응원 구단 조회는 1회다(20건과 같은 상수 — "
            + "항목 수와 무관함을 대비 확인)")
    void getTodayQuizzes_gameAndTeamLookups_happenOnceFor1Item() {
        Quiz single = quiz(1L, "객관식", "문제1", "EASY", 10.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(single));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(1);
        verify(gameRepository, times(1)).findWithStatusByNaverGameId(GAME_ID);
        verify(userSupportTeamRepository, times(1))
                .findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID);
    }

    // ---------- U절: 한 이닝에 한 세트 · 재조회 없음 (QUIZ-INN-95~102·112·113) ----------

    @Test
    @DisplayName("[AC-INN-96-1,96-3,102-2] 이미 행이 있는 문제는(답 여부·시한 정보 자체를 조회하지 않고) "
            + "전부 제외된다 — 조회 시각에 의존하지 않는 순수 존재 판정(findServedQuizIds가 id만 반환)")
    void getTodayQuizzes_excludesAnyQuizWithExistingRow_regardlessOfAnswerOrTime() {
        Quiz alreadyHasRow1 = quiz(1L, "객관식", "이미 행이 있는 문제1", "EASY", 10.0);
        Quiz alreadyHasRow2 = quiz(2L, "객관식", "이미 행이 있는 문제2", "EASY", 10.0);
        Quiz brandNew = quiz(3L, "객관식", "아직 행이 없는 문제", "EASY", 10.0);
        Game game = givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(List.of(alreadyHasRow1, alreadyHasRow2, brandNew));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L, 3L)))
                .willReturn(List.of(1L, 2L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(3L);
        ArgumentCaptor<Collection<Long>> insertedCaptor = quizIdsCaptor();
        verify(quizUserSubmitRepository).insertUnansweredRows(
                org.mockito.ArgumentMatchers.eq(USER_ID), insertedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(game.getId()),
                org.mockito.ArgumentMatchers.eq(DEFAULT_INNING), any());
        assertThat(insertedCaptor.getValue()).containsExactly(3L);
    }

    @Test
    @DisplayName("[AC-INN-100-1,100-2,100-3,100-4] 이닝이 다음 회로 넘어가면 새 세트를 받되, 직전 이닝에 "
            + "받은(그러나 안 푼) 문제는 그대로 제외된 채 남는다")
    void getTodayQuizzes_inningAdvances_servesNewSetExcludingPreviouslyServed() {
        Quiz servedInInning5 = quiz(1L, "객관식", "5회에 받은 문제", "EASY", 10.0);
        Quiz newInInning6 = quiz(2L, "객관식", "6회에 새로 나온 문제", "EASY", 10.0);
        Game game = game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam,
                "IN_PROGRESS", 6); // 지금은 6회
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(game, 6); // 6회는 아직 안 받음
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(List.of(servedInInning5, newInInning6));
        // 5회에 받은 문제는 game_id는 같고 inning만 달랐던 과거 행이라도 "행이 있다"는 사실만으로 제외된다
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L);
        ArgumentCaptor<Collection<Long>> insertedCaptor = quizIdsCaptor();
        verify(quizUserSubmitRepository).insertUnansweredRows(
                org.mockito.ArgumentMatchers.eq(USER_ID), insertedCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(game.getId()),
                org.mockito.ArgumentMatchers.eq(6), any());
        assertThat(insertedCaptor.getValue()).containsExactly(2L);
    }

    @Test
    @DisplayName("[AC-INN-98-1,98-2,98-3,98-4] IN_PROGRESS인데 current_inning이 NULL이면(원천 이상 방어) "
            + "403 QUIZ_NOT_SERVABLE이고 이후 조회를 진행하지 않는다")
    void getTodayQuizzes_inProgressButInningNull_throwsNotServable() {
        Game game = game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam,
                "IN_PROGRESS", null);
        givenGame(game);
        givenSupportTeam(homeTeam);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
        verifyNoInteractions(quizRepository, quizUserSubmitRepository);
    }

    @Test
    @DisplayName("[AC-INN-113-1,113-3] 제공 가능한 상태인데 오늘 세트가 없으면 200과 빈 배열이다(에러 "
            + "아님) — 미답 행도 생기지 않는다")
    void getTodayQuizzes_servableButNoTodaySet_returns200EmptyArray() {
        givenServable(DEFAULT_INNING);

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportPlayerRepository);
        verify(quizUserSubmitRepository, never()).findServedQuizIds(any(), any());
    }

    @Test
    @DisplayName("[AC-INN-113-1,113-3] 오늘 세트가 있어도 전부 이미 받은 상태(행 존재)면 200과 빈 "
            + "배열이다")
    void getTodayQuizzes_servableButAllAlreadyServed_returns200EmptyArray() {
        Quiz first = quiz(1L, "O/X", "문제1", "EASY", 10.0);
        Quiz second = quiz(2L, "객관식", "문제2", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(first, second));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L, 2L));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportPlayerRepository);
    }

    // ---------- V절: /today의 경기 지목과 검증 (QUIZ-INN-105~111, 109는 QUIZ-GSS-2로 폐기) ----------

    @Test
    @DisplayName("[AC-INN-106-2] quiz_users_submit에 저장되는 game_id는 naver_game_id 문자열이 아니라 "
            + "해석된 내부 PK다")
    void getTodayQuizzes_insertUsesInternalGamePk_notNaverGameIdString() {
        Quiz single = quiz(1L, "객관식", "문제1", "EASY", 10.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(single));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        ArgumentCaptor<Long> gameIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(quizUserSubmitRepository).insertUnansweredRows(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(List.of(1L)),
                gameIdCaptor.capture(), org.mockito.ArgumentMatchers.eq(DEFAULT_INNING), any());
        assertThat(gameIdCaptor.getValue()).isEqualTo(GAME_PK); // 내부 PK — GAME_ID 문자열이 아니다
    }

    @Test
    @DisplayName("[AC-INN-107-1,107-2] gameId에 해당하는 경기가 없으면(빈 문자열 포함 흡수) 403 "
            + "QUIZ_NOT_SERVABLE이다 — GAME_NOT_FOUND(404)를 쓰지 않는다")
    void getTodayQuizzes_gameIdNotFound_throwsNotServable() {
        given(gameRepository.findWithStatusByNaverGameId(GAME_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
        verifyNoInteractions(quizRepository, quizUserSubmitRepository);
    }

    @Test
    @DisplayName("[AC-INN-108-1,108-3] 어제 경기를 지목하면 403 QUIZ_NOT_SERVABLE이다 — 없으면 지난 "
            + "경기 id를 돌려 가며 세트를 무제한 받을 수 있다")
    void getTodayQuizzes_gameFromYesterday_throwsNotServable() {
        Game game = game(GAME_PK, GAME_ID, TODAY.minusDays(1).atTime(18, 30), homeTeam, awayTeam,
                "IN_PROGRESS", DEFAULT_INNING);
        givenGame(game);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
    }

    @Test
    @DisplayName("[AC-INN-108-2] 경기 시각이 정확히 내일 00:00이면(반개구간 상한 제외) 오늘 경기가 "
            + "아니라고 판정해 403이다")
    void getTodayQuizzes_gameDateExactlyAtTomorrowStart_throwsNotServable() {
        Game game = game(GAME_PK, GAME_ID, TODAY.plusDays(1).atStartOfDay(), homeTeam, awayTeam,
                "IN_PROGRESS", DEFAULT_INNING);
        givenGame(game);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
    }

    @Test
    @DisplayName("[AC-INN-108-2] 경기 시각이 정확히 오늘 00:00이면 반개구간 하한 포함으로 통과한다"
            + "(등치 비교·Between이 아니라 [00:00,+1일 00:00) 규약)")
    void getTodayQuizzes_gameDateExactlyAtTodayStart_isTreatedAsToday() {
        Game game = game(GAME_PK, GAME_ID, TODAY.atStartOfDay(), homeTeam, awayTeam,
                "IN_PROGRESS", DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(game, DEFAULT_INNING);

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty(); // 예외 없이 통과 = 오늘로 인정됐다는 뜻
    }

    // ---------- 단건 상세 ----------

    @Test
    @DisplayName("존재하지 않는 문제 조회는 QUIZ_NOT_FOUND를 던진다")
    void getQuiz_missingQuiz_throwsQuizNotFound() {
        given(quizRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> quizService.getQuiz(USER_ID, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_FOUND);
        verifyNoInteractions(quizOptionRepository, quizUserSubmitRepository);
    }

    @Test
    @DisplayName("미편성 풀(quizDate=null) 문제는 존재해도 QUIZ_NOT_FOUND다 — 편성 전 문제의 "
            + "존재를 숨긴다")
    void getQuiz_unpublishedPoolQuiz_throwsQuizNotFound() {
        Quiz pooled = Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .content("아직 편성되지 않은 문제")
                .answer(0)
                .quizDate(null)
                .build();
        ReflectionTestUtils.setField(pooled, "id", 5L);
        given(quizRepository.findById(5L)).willReturn(Optional.of(pooled));

        assertThatThrownBy(() -> quizService.getQuiz(USER_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_FOUND);
        verifyNoInteractions(quizOptionRepository, quizUserSubmitRepository);
    }

    @Test
    @DisplayName("[AC-INN-79-4] 받은 적 없는(행 자체가 없는) 문제 상세는 submitted=false·expired=false다"
            + "(진행 중과 같은 모양 — 구분은 /today 목록의 몫)")
    void getQuiz_noRowAtAll_submittedFalseExpiredFalse() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "LG"), option(quiz, 1, "한화")));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.empty());

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.type()).isEqualTo("객관식");
        assertThat(result.question()).isEqualTo("2025 정규시즌 우승 구단은?");
        assertThat(result.quizDate()).isEqualTo(TODAY);
        assertThat(result.options()).hasSize(2);
        assertThat(result.submitted()).isFalse();
        assertThat(result.expired()).isFalse();
        assertThat(result.myOption()).isNull();
        assertThat(result.correct()).isNull();
        assertThat(result.answer()).isNull();
    }

    @Test
    @DisplayName("[AC-INN-79-2,79-3] 행은 있지만 아직 미답이고 시한이 남은 문제는 submitted=false·"
            + "expired=false다(진행 중 — NPE 없이 unsubmitted로 처리된다, 종전에는 여기서 500이었다)")
    void getQuiz_unansweredWithinWindow_submittedFalseExpiredFalse() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        QuizUserSubmit unanswered = QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build();
        LocalDateTime now = QuizSubmitWindow.now();
        ReflectionTestUtils.setField(unanswered, "createdAt", now.minusMinutes(1));
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "LG"), option(quiz, 1, "한화")));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(unanswered));

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.submitted()).isFalse();
        assertThat(result.expired()).isFalse();
        assertThat(result.myOption()).isNull();
        assertThat(result.correct()).isNull();
        assertThat(result.answer()).isNull();
        verifyNoInteractions(quizLikeService);
    }

    @Test
    @DisplayName("[AC-INN-79-2,79-3] 행은 있지만 미답이고 시한(8분)을 넘긴 문제는 submitted=false·"
            + "expired=true다(제출하면 403이 되는 상태)")
    void getQuiz_unansweredPastWindow_submittedFalseExpiredTrue() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        QuizUserSubmit unanswered = QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build();
        LocalDateTime now = QuizSubmitWindow.now();
        ReflectionTestUtils.setField(unanswered, "createdAt", now.minusMinutes(9));
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "LG"), option(quiz, 1, "한화")));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(unanswered));

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.submitted()).isFalse();
        assertThat(result.expired()).isTrue();
    }

    @Test
    @DisplayName("답한 문제 상세는 내 선택·정오·정답·좋아요를 함께 싣고 expired는 항상 false다(복기 화면)")
    void getQuiz_answered_includesMyOptionCorrectAndAnswerWithExpiredAlwaysFalse() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        QuizOption wrongPick = option(quiz, 1, "한화");
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .quiz(quiz)
                .submitOption(wrongPick)
                .isAnswer(false)
                .build();
        LocalDateTime now = QuizSubmitWindow.now();
        ReflectionTestUtils.setField(submit, "createdAt", now.minusDays(1));
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "LG"), wrongPick));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(submit));
        given(quizLikeService.likeOf(USER_ID, 1L))
                .willReturn(new QuizLikeResponse(true, 3L));

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.submitted()).isTrue();
        assertThat(result.expired()).isFalse();
        assertThat(result.myOption()).isEqualTo(1);
        assertThat(result.correct()).isFalse();
        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(3L);
        assertThat(result.answer()).isEqualTo(0); // quiz() 픽스처의 정답 보기 번호
    }

    // ---------- 투표 집계 초기화(docs/requirements/quiz/quiz-vote-tally.md) ----------

    @Test
    @DisplayName("[AC-VOTE-11-1,2-1] /today가 문제를 실으면 그 문제의 보기 번호를 0-based 그대로 "
            + "initializeAndRead()에 넘긴다 — {1,2,3,4}가 아니라 {0,1,2,3}")
    void getTodayQuizzes_servesQuizzes_initializesVoteTallyWithZeroBasedOptionNumbers() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        Quiz multiQuiz = quiz(2L, "객관식", "우승 구단은?", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate())
                .willReturn(List.of(oxQuiz, multiQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(
                        option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X"),
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));

        quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<Integer>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(quizVoteTally).initializeAndRead(captor.capture());
        Map<Long, List<Integer>> initialized = captor.getValue();
        assertThat(initialized.get(1L)).containsExactlyInAnyOrder(0, 1);
        assertThat(initialized.get(2L)).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    @DisplayName("[AC-VOTE-11-2] 상한(20)에 잘려 응답에 실리지 않은 문제는 initializeAndRead()에도 실리지 "
            + "않는다 — initializeAndRead() 대상은 실제 응답 목록과 정확히 같다")
    void getTodayQuizzes_truncatedByCap_excludesUnservedQuizzesFromInitialize() {
        QuizService cappedService = newQuizService(6);
        List<Quiz> quizzes = manyQuizzes(); // 10건
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(quizzes.stream().map(q -> option(q, 0, "보기")).toList());

        List<QuizResponse> result = cappedService.getTodayQuizzes(USER_ID, GAME_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<Integer>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(quizVoteTally).initializeAndRead(captor.capture());
        assertThat(captor.getValue().keySet())
                .containsExactlyInAnyOrderElementsOf(result.stream().map(QuizResponse::id).toList());
        assertThat(captor.getValue()).hasSize(6);
    }

    @Test
    @DisplayName("[AC-VOTE-13-1] 403 QUIZ_NOT_SERVABLE로 거절되면 집계 초기화 자체가 호출되지 않는다")
    void getTodayQuizzes_notServable_neverInitializesVoteTally() {
        Game game = game(GAME_PK, GAME_ID, TODAY.atTime(18, 30), homeTeam, awayTeam, "SCHEDULED", null);
        givenGame(game);
        givenSupportTeam(homeTeam);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(quizVoteTally);
    }

    @Test
    @DisplayName("[AC-VOTE-13-1] 409 QUIZ_ALREADY_SERVED_IN_INNING으로 거절되면 집계 초기화가 호출되지 "
            + "않는다")
    void getTodayQuizzes_alreadyServedInInning_neverInitializesVoteTally() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenAlreadyServedThisInning(game, DEFAULT_INNING);

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(quizVoteTally);
    }

    @Test
    @DisplayName("[AC-VOTE-13-1] 제공 가능하지만 오늘 세트가 비어 빈 배열을 반환하면 집계 초기화가 "
            + "호출되지 않는다")
    void getTodayQuizzes_emptyResult_neverInitializesVoteTally() {
        givenServable(DEFAULT_INNING);

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizVoteTally);
    }

    @Test
    @DisplayName("[AC-VOTE-31-1] QuizService는 StringRedisTemplate을 직접 필드로 갖지 않는다 — 집계는 "
            + "포트(QuizVoteTally) 하나로만 접근한다")
    void quizService_hasNoDirectStringRedisTemplateField() {
        boolean hasDirectRedisField = java.util.Arrays.stream(QuizService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getSimpleName().equals("StringRedisTemplate"));
        assertThat(hasDirectRedisField).isFalse();
    }

    @Test
    @DisplayName("[AC-VOTE-5-1] 단건 상세 조회(getQuiz)는 집계 포트를 전혀 호출하지 않는다 — 조회 응답은 "
            + "Redis 상태와 무관하다")
    void getQuiz_neverInteractsWithVoteTally() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L)).willReturn(List.of());
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.empty());

        quizService.getQuiz(USER_ID, 1L);

        verifyNoInteractions(quizVoteTally);
    }

    // ---------- 보기별 투표 수 노출(docs/requirements/quiz/quiz-vote-exposure.md) ----------

    @Test
    @DisplayName("[AC-VOTEVIEW-1-1,1-2,9-1,9-2] initializeAndRead()가 돌려준 맵의 값이 응답 options의 "
            + "voteCount로 0-based 그대로(밀리지 않고) 실린다 — 보기마다 서로 다른 값을 심어 한 칸이라도 "
            + "밀리면 잡히게 한다")
    void getTodayQuizzes_populatesVoteCountFromTallyWithoutShiftingOptionAxis() {
        Quiz multiQuiz = quiz(2L, "객관식", "우승 구단은?", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(multiQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));
        given(quizVoteTally.initializeAndRead(any())).willReturn(
                Map.of(2L, Map.of(0, 10L, 1, 20L, 2, 30L, 3, 40L)));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        Map<Integer, Long> voteCountByNo = result.get(0).options().stream()
                .collect(Collectors.toMap(QuizResponse.TodayOptionResponse::no,
                        QuizResponse.TodayOptionResponse::voteCount));
        assertThat(voteCountByNo).containsExactlyInAnyOrderEntriesOf(
                Map.of(0, 10L, 1, 20L, 2, 30L, 3, 40L));
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-10-1,20-1,21-1] 집계 포트가 빈 맵을 돌려주면(첫 서빙·Redis 장애 둘 다 "
            + "이 모양) 모든 보기의 voteCount가 0이고 응답은 여전히 200 목록이다")
    void getTodayQuizzes_emptyVoteTallyResult_fillsAllOptionsWithZeroVoteCount() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(oxQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X")));
        given(quizVoteTally.initializeAndRead(any())).willReturn(Map.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).options())
                .extracting(QuizResponse.TodayOptionResponse::voteCount)
                .containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-23-1] 집계 포트가 일부 보기 필드만 돌려주면 없는 필드에 해당하는 보기만 "
            + "voteCount 0이고 있는 필드는 그 값 그대로다")
    void getTodayQuizzes_partialVoteTallyResult_fillsOnlyMissingFieldsWithZero() {
        Quiz multiQuiz = quiz(2L, "객관식", "우승 구단은?", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(multiQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));
        // no=1 필드만 존재하는 상태(TTL 만료 후 재생성·부분 결손 등을 흉내)
        given(quizVoteTally.initializeAndRead(any())).willReturn(Map.of(2L, Map.of(1, 7L)));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        Map<Integer, Long> voteCountByNo = result.get(0).options().stream()
                .collect(Collectors.toMap(QuizResponse.TodayOptionResponse::no,
                        QuizResponse.TodayOptionResponse::voteCount));
        assertThat(voteCountByNo).containsExactlyInAnyOrderEntriesOf(
                Map.of(0, 0L, 1, 7L, 2, 0L, 3, 0L));
    }

    @Test
    @DisplayName("[AC-GSS-13, 구 AC-VOTEVIEW-14-1 대체] preferredOnly=true여도 더 이상 결과를 좁히지 "
            + "않으므로 initializeAndRead() 대상도 preferredOnly와 무관하게 실제로 선택된 문제 전부다")
    void getTodayQuizzes_preferredOnlyTrue_doesNotNarrowVoteTallyTarget() {
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findByQuizDateAndClassifierTeam(TODAY, homeTeam.getId()))
                .willReturn(List.of(teamQuiz));
        given(quizRepository.findGeneralQuizzesWithQuizDate()).willReturn(List.of(general));
        // initializeAndRead() 대상은 "보기가 있는 문제만"으로 필터링되므로(QuizService 구현), 두 문제
        // 모두 집계 대상에 포함되는지 확인하려면 보기를 실제로 돌려줘야 한다(빈 보기면 필터에서 빠진다).
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(option(general, 0, "O"), option(teamQuiz, 0, "O")));

        quizService.getTodayQuizzes(USER_ID, GAME_ID, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<Integer>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(quizVoteTally).initializeAndRead(captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys(1L, 2L);
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-4-1,5-1] /today 응답 문제 항목의 필드 집합은 기존 그대로다(id·type·"
            + "question·difficulty·point·bq·preferred·options) — answer·liked·likeCount·totalVotes·"
            + "voteRatio 같은 필드는 record에 아예 존재하지 않는다")
    void quizResponse_recordComponents_matchExactFieldSet() {
        List<String> componentNames = java.util.Arrays.stream(QuizResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(componentNames).containsExactlyInAnyOrder(
                "id", "type", "question", "difficulty", "point", "bq", "preferred", "options");
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-1-1] 보기 항목(TodayOptionResponse)의 필드 집합은 no·text·voteCount "
            + "셋뿐이다")
    void todayOptionResponse_recordComponents_areNoTextVoteCountOnly() {
        List<String> componentNames = java.util.Arrays
                .stream(QuizResponse.TodayOptionResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(componentNames).containsExactlyInAnyOrder("no", "text", "voteCount");
    }

    // ---------- getQuizVoteCount ----------

    @Test
    @DisplayName("받았고 아직 답하지 않은 문제는 보기별 투표 수를 돌려준다 — 항목 모양이 /today 와 같다")
    void getQuizVoteCount_unanswered_returnsVoteCountsPerOption() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타"), option(quiz, 1, "삼진")));
        given(quizVoteTally.read(1L)).willReturn(Map.of(0, 37L, 1, 12L));

        QuizVoteCountResponse result = quizService.getQuizVoteCount(USER_ID, 1L);

        assertThat(result.quizId()).isEqualTo(1L);
        assertThat(result.options()).extracting(
                        QuizResponse.TodayOptionResponse::no,
                        QuizResponse.TodayOptionResponse::text,
                        QuizResponse.TodayOptionResponse::voteCount)
                .containsExactly(tuple(0, "안타", 37L), tuple(1, "삼진", 12L));
    }

    @Test
    @DisplayName("Redis 가 일부만 돌려주거나(부분 결손) 통째로 비어도 보기는 전부 실린다 — 없는 자리는 0")
    void getQuizVoteCount_partialTally_fillsMissingOptionsWithZero() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타"), option(quiz, 1, "삼진"),
                        option(quiz, 2, "볼넷")));
        // Redis 장애·TTL 만료면 read 가 빈 맵을 준다(예외가 아니다) — 여기서는 1번 보기만 읽힌 상태
        given(quizVoteTally.read(1L)).willReturn(Map.of(1, 5L));

        QuizVoteCountResponse result = quizService.getQuizVoteCount(USER_ID, 1L);

        assertThat(result.options()).extracting(QuizResponse.TodayOptionResponse::voteCount)
                .containsExactly(0L, 5L, 0L);
    }

    @Test
    @DisplayName("받은 적 없는 문제는 null 이다 — 보기도 Redis 도 조회하지 않는다")
    void getQuizVoteCount_neverServed_returnsNullWithoutTouchingOptionsOrTally() {
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.empty());

        assertThat(quizService.getQuizVoteCount(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizOptionRepository, quizVoteTally);
    }

    @Test
    @DisplayName("이미 제출한 문제는 null 이다 — 낸 사람에게 분포를 감추는 것이 이 API 의 목적이다")
    void getQuizVoteCount_alreadyAnswered_returnsNull() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder()
                        .quiz(quiz)
                        .submitOption(option(quiz, 1, "삼진"))
                        .isAnswer(false)
                        .build()));

        assertThat(quizService.getQuizVoteCount(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizOptionRepository, quizVoteTally);
    }

    @Test
    @DisplayName("보기가 하나도 없는 문제는 null 이다 — 0 으로 채울 근거가 없어 Redis 도 읽지 않는다")
    void getQuizVoteCount_noOptions_returnsNullWithoutReadingTally() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L)).willReturn(List.of());

        assertThat(quizService.getQuizVoteCount(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizVoteTally);
    }

    @Test
    @DisplayName("시한(8분)을 넘긴 미답 행도 그대로 준다 — 폴링 도중 응답이 조용히 비지 않게 한다")
    void getQuizVoteCount_unansweredPastWindow_stillReturnsCounts() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        QuizUserSubmit expired = QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build();
        ReflectionTestUtils.setField(expired, "createdAt", QuizSubmitWindow.now().minusMinutes(30));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(expired));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타")));
        given(quizVoteTally.read(1L)).willReturn(Map.of(0, 3L));

        assertThat(quizService.getQuizVoteCount(USER_ID, 1L)).isNotNull();
    }

    @Test
    @DisplayName("투표 수 응답의 필드 집합은 quizId·options 둘뿐이고, 보기 항목은 /today 와 같은 타입이다 "
            + "— 서버가 백분율을 계산해 내보내지 않는다")
    void quizVoteCountResponse_recordComponents_areQuizIdAndOptionsOnly() {
        List<java.lang.reflect.RecordComponent> components = java.util.Arrays
                .stream(QuizVoteCountResponse.class.getRecordComponents()).toList();
        assertThat(components).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("quizId", "options");
        assertThat(QuizVoteCountResponse.class.getRecordComponents()[1].getGenericType().getTypeName())
                .contains(QuizResponse.TodayOptionResponse.class.getName());
    }
}
