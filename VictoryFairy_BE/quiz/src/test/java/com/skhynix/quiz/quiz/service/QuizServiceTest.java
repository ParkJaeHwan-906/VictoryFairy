package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.skhynix.quiz.quiz.dto.QuizVoteRateResponse;
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
 * <p><b>여러 테스트가 (경기,이닝) 존재 검사({@code existsByUserAccount_IdAndGame_IdAndInning})를 매번
 * false로 정적 스텁한다</b> — 실제 연속 호출이라면 첫 호출이 만든 행 때문에 두 번째 호출이 409가 나야
 * 정확하지만(그 자체는 별도 테스트로 고정한다), 순서·셔플·부분집합 안정성을 다루는 테스트는 그 축과
 * 무관한 관심사를 검증하는 것이라 의도적으로 분리했다.
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
    // 작아 상한이 발동하지 않는다. 상한 자체의 케이스는 별도로 채운다(AC-INN-6·7·8).
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
                .score(score)
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

    /** 넘긴 quizId들에 대해 기존 행이 전혀 없는 상태(빈 결과)를 스텁한다 — 가장 흔한 기본값. */
    private void givenNoExistingRows(List<Long> quizIds) {
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, quizIds)).willReturn(List.of());
    }

    /** 세트 제공 가능 상태(경기·응원 구단·회차)를 한 번에 갖춘다. */
    private Game givenServable(int inning) {
        Game game = defaultServableGame(inning);
        givenGame(game);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(game, inning);
        return game;
    }

    /** 그룹 내 순열이 갈릴 만큼 충분한 개수(10건)의 무선호 문제 목록. */
    private List<Quiz> manyQuizzes() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<Long>> quizIdsCaptor() {
        return (ArgumentCaptor<Collection<Long>>) (ArgumentCaptor<?>) ArgumentCaptor
                .forClass(Collection.class);
    }

    // ---------- 오늘의 퀴즈: 2쿼리 방식 ----------

    @Test
    @DisplayName("오늘 문제가 2건이면 보기를 IN 한 방으로 받아 문제별로 묶어 반환한다(2쿼리 방식)")
    void getTodayQuizzes_twoQuizzes_groupsOptionsPerQuizWithSingleInQuery() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        Quiz multiQuiz = quiz(2L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(oxQuiz, multiQuiz));
        givenNoExistingRows(List.of(1L, 2L));
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(orphan));
        givenNoExistingRows(List.of(7L));
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(7L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).options()).isEmpty();
        assertThat(result.get(0).point()).isNull();
        assertThat(result.get(0).difficulty()).isNull();
    }

    // ---------- 오늘의 퀴즈: 선호 정렬 ----------

    @Test
    @DisplayName("응원팀이 team 또는 opponentTeam과 일치하는 문제가 preferred 그룹으로 전부 앞선다"
            + "(그룹 내부 순서는 사용자별 셔플이라 단언하지 않음)")
    void getTodayQuizzes_supportTeamMatches_sortsPreferredGroupFirst() {
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        Quiz matchupQuiz = quiz(3L, "객관식", "KT vs 한화 문제", "EASY", 10.0, awayTeam, homeTeam, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz, matchupQuiz));
        givenSupportPlayers();
        givenNoExistingRows(List.of(1L, 2L, 3L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(true, true, false);
    }

    @Test
    @DisplayName("응원 선수가 문제의 player와 일치하면 preferred로 앞선다(응원팀 매칭과 별개 축)")
    void getTodayQuizzes_supportPlayerMatches_sortsPreferredFirst() {
        Player moonDongJu = player(200L, homeTeam);
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz playerQuiz = quiz(2L, "객관식", "문동주 문제", "EASY", 10.0, null, null, moonDongJu);
        givenServable(DEFAULT_INNING); // 응원 구단은 gameId 검증의 필수 전제 — 문제 team과는 무관
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, playerQuiz));
        givenSupportPlayers(moonDongJu);
        givenNoExistingRows(List.of(1L, 2L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L, 1L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L, 1L);
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(true, false);
    }

    @Test
    @DisplayName("응원 구단이 있어도(가입 필수 전제) 문제의 team과 일치하지 않으면 preferred=false다"
            + "(응원 구단 부재 자체는 이제 403이라 이 테스트로 대신 검증)")
    void getTodayQuizzes_unmatchedTeamQuiz_notPreferred() {
        Team samsung = team(200L, "SS", "삼성");
        Quiz first = quiz(1L, "객관식", "문제1", "EASY", 10.0, samsung, null, null);
        Quiz second = quiz(2L, "객관식", "문제2", "EASY", 10.0);
        givenServable(DEFAULT_INNING); // 응원 구단 = 한화(홈) — 어느 문제와도 매칭되지 않음
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(first, second));
        givenSupportPlayers();
        givenNoExistingRows(List.of(1L, 2L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(false, false);
    }

    // ---------- 오늘의 퀴즈: preferredOnly ----------

    @Test
    @DisplayName("preferredOnly=true면 선호 문제만 반환하고, 보기 조회도 남은 문제 id로만 나간다")
    void getTodayQuizzes_preferredOnly_returnsOnlyMatchedQuizzes() {
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz));
        givenSupportPlayers();
        givenNoExistingRows(List.of(1L, 2L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L)))
                .willReturn(List.of(option(teamQuiz, 0, "O")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, true);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L);
        assertThat(result.get(0).preferred()).isTrue();
        verify(quizOptionRepository).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L));
    }

    // ---------- 오늘의 퀴즈: 사용자별 고정 랜덤(shuffleKey) ----------

    @Test
    @DisplayName("같은 사용자로 두 번 호출하면 순서가 동일하다(결정성 — (경기,이닝) 존재 검사는 두 호출 "
            + "모두 false로 정적 스텁해 회차 제한 축과 분리한다)")
    void getTodayQuizzes_calledTwiceForSameUser_returnsSameOrder() {
        List<Quiz> quizzes = manyQuizzes();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> firstCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();
        List<Long> secondCall = quizService.getTodayQuizzes(USER_ID, GAME_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(secondCall).containsExactlyElementsOf(firstCall);
    }

    @Test
    @DisplayName("서로 다른 계정은 셔플 순서가 다르다 — 문제 수가 충분(10건)해 순열이 갈릴 만큼 "
            + "우연히 전부 같을 확률은 무시할 만하다")
    void getTodayQuizzes_differentAccounts_produceDifferentOrders() {
        List<Quiz> quizzes = manyQuizzes();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> accountIds = List.of(USER_ID, 20L, 300L);
        List<List<Long>> orders = accountIds.stream()
                .map(accountId -> {
                    given(userSupportTeamRepository
                            .findWithTeamByUserAccount_IdAndOpposeIsNull(accountId))
                            .willReturn(Optional.of(UserSupportTeam.builder().team(homeTeam).build()));
                    given(userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(accountId))
                            .willReturn(List.of());
                    given(quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                            accountId, game.getId(), DEFAULT_INNING)).willReturn(false);
                    given(quizUserSubmitRepository.findServedQuizIds(accountId, ids))
                            .willReturn(List.of());
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());
        givenSupportPlayers();
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, allIds)).willReturn(List.of());

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

    // ---------- 오늘의 퀴즈: 응답 개수 상한(quiz.serve.max-today-count) ----------

    @Test
    @DisplayName("[AC-INN-6-1,6-3] 조건을 만족하는 문제가 25건이면 상한 20에서 잘려 200과 길이 20으로 "
            + "응답한다(에러도 별도 표식도 없다)")
    void getTodayQuizzes_moreThanCap_truncatesToMaxTodayCount() {
        List<Quiz> quizzes = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(20);
    }

    @Test
    @DisplayName("[AC-INN-6-2] 19건이면 상한 20이 발동하지 않아 그대로 19건이다")
    void getTodayQuizzes_underCap_returnsAllWithoutTruncation() {
        List<Quiz> quizzes = IntStream.rangeClosed(1, 19)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(19);
    }

    @Test
    @DisplayName("[AC-INN-7-1,7-2] max-today-count 생성자 인자를 5로 낮추면 같은 세트에서 5건만 "
            + "반환된다(설정값으로 배선돼 있다는 계약 — 하드코딩이면 이 인자가 무시된다)")
    void getTodayQuizzes_customMaxTodayCount_limitsToConfiguredValue() {
        QuizService limitedService = newQuizService(5);
        List<Quiz> quizzes = manyQuizzes();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limitedService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("[AC-INN-8-1,8-2] 상한은 정렬·필터가 끝난 목록의 앞에서부터 자른다 — 상한 적용 결과는 "
            + "상한 없는 전체 순서의 앞부분과 정확히 일치한다(정렬을 흐트러뜨리는 회귀 방지)")
    void getTodayQuizzes_capTruncatesFromSortedOrderPrefix() {
        List<Quiz> quizzes = manyQuizzes();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
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
    @DisplayName("[AC-INN-8-4] preferredOnly=true로 5건만 남으면 상한(20)과 무관하게 5건이다")
    void getTodayQuizzes_preferredOnlyFewerThanCap_returnsAllMatched() {
        List<Quiz> preferredQuizzes = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> quiz((long) i, "객관식", "한화 문제" + i, "EASY", 10.0, homeTeam, null, null))
                .toList();
        List<Long> ids = preferredQuizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(preferredQuizzes);
        givenSupportPlayers();
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, true);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("[AC-INN-61-2] 상한으로 잘려 나간 문제에는 행이 생기지 않는다 — insertUnansweredRows에 "
            + "실리는 대상은 실제로 응답에 실린 문제와 정확히 같다")
    void getTodayQuizzes_issuesRowsOnlyForServedQuizzes() {
        QuizService cappedService = newQuizService(6);
        List<Quiz> quizzes = manyQuizzes(); // 10건
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        Game game = givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
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

    // ---------- T절: 이닝의 원천 (QUIZ-INN-83~94) ----------

    @Test
    @DisplayName("[AC-INN-83-1,83-2,83-3,84-1,84-2,84-3,84-4] 한 요청으로 만들어지는 모든 행은 문제의 "
            + "game 연관과 무관하게 전부 같은 inning(기준 경기의 current_inning)을 갖는다 — 경기 미귀속 "
            + "문제·다른 경기(다른 이닝)에 귀속된 문제도 예외 없이 기준 경기 이닝을 받는다")
    void getTodayQuizzes_allNewRowsShareBaseGameInning_regardlessOfQuizOwnGame() {
        Game baseGame = defaultServableGame(7);
        givenGame(baseGame);
        givenSupportTeam(homeTeam);
        givenNotServedThisInning(baseGame, 7);
        Game otherGame = game(600L, "20260807XXYY02026", TODAY.atTime(14, 0), homeTeam, awayTeam,
                "IN_PROGRESS", 3); // 문제가 귀속된, 기준 경기와는 다른 경기 — 이닝도 다르다(3)
        Quiz noGameQuiz = quiz(1L, "객관식", "경기 미귀속 문제", "EASY", 10.0);
        Quiz otherGameQuiz = quiz(2L, "객관식", "다른 경기 귀속 문제", "EASY", 10.0,
                homeTeam, awayTeam, null, otherGame);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(noGameQuiz, otherGameQuiz));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of());
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty(); // 예외 없이 통과했다는 것 자체가 id 무관 판정의 증거
    }

    @Test
    @DisplayName("[AC-INN-87-1,87-2,87-3,87-4] 응원 중인 구단이 없으면(정상 흐름에 없는 데이터 이상) "
            + "403 QUIZ_NOT_SERVABLE이다 — SUPPORT_TEAM_REQUIRED(400)를 쓰지 않는다")
    void getTodayQuizzes_noSupportTeam_throwsNotServable() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenNoSupportTeam();

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
        verifyNoInteractions(quizRepository, quizUserSubmitRepository);
    }

    @Test
    @DisplayName("[AC-INN-92-1,92-2,92-4] 응답 항목이 20건이어도 경기·응원 구단 조회는 요청당 정확히 1회다")
    void getTodayQuizzes_gameAndTeamLookups_happenOnceFor20Items() {
        List<Quiz> quizzes = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(single));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L))).willReturn(List.of());
        givenSupportPlayers();
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
    @DisplayName("[AC-INN-95-1,95-2,95-3,95-4,112-1,96-2] 같은 (경기,이닝)에 이미 받은 행이 있으면 새 "
            + "미답 행 없이 409 QUIZ_ALREADY_SERVED_IN_INNING이다 — 옛 회귀 AC-INN-74-1(연속 두 번 "
            + "호출하면 같은 목록)은 폐기되고 정반대(두 번째 호출은 실패)로 교체된다")
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

    @Test
    @DisplayName("[AC-INN-96-1,96-3,102-2] 이미 행이 있는 문제는(답 여부·시한 정보 자체를 조회하지 않고) "
            + "전부 제외된다 — 조회 시각에 의존하지 않는 순수 존재 판정(findServedQuizIds가 id만 반환)")
    void getTodayQuizzes_excludesAnyQuizWithExistingRow_regardlessOfAnswerOrTime() {
        Quiz alreadyHasRow1 = quiz(1L, "객관식", "이미 행이 있는 문제1", "EASY", 10.0);
        Quiz alreadyHasRow2 = quiz(2L, "객관식", "이미 행이 있는 문제2", "EASY", 10.0);
        Quiz brandNew = quiz(3L, "객관식", "아직 행이 없는 문제", "EASY", 10.0);
        Game game = givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(alreadyHasRow1, alreadyHasRow2, brandNew));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L, 3L)))
                .willReturn(List.of(1L, 2L));
        givenSupportPlayers();
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
        givenNotServedThisInning(game, 6); // 6회는 아직 안 받음 — 95를 통과
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(servedInInning5, newInInning6));
        // 5회에 받은 문제는 game_id는 같고 inning만 달랐던 과거 행이라도 "행이 있다"는 사실만으로 제외된다
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L));
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(first, second));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L, 2L));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportPlayerRepository);
    }

    // ---------- V절: /today의 경기 지목과 검증 (QUIZ-INN-105~111) ----------

    @Test
    @DisplayName("[AC-INN-106-2] quiz_users_submit에 저장되는 game_id는 naver_game_id 문자열이 아니라 "
            + "해석된 내부 PK다")
    void getTodayQuizzes_insertUsesInternalGamePk_notNaverGameIdString() {
        Quiz single = quiz(1L, "객관식", "문제1", "EASY", 10.0);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(single));
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, List.of(1L))).willReturn(List.of());
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty(); // 예외 없이 통과 = 오늘로 인정됐다는 뜻
    }

    @Test
    @DisplayName("[AC-INN-109-1,109-3] 지목된 경기에 내 응원 구단이 참여하지 않으면 403 "
            + "QUIZ_NOT_SERVABLE이다")
    void getTodayQuizzes_supportTeamNotInGame_throwsNotServable() {
        givenGame(defaultServableGame(DEFAULT_INNING));
        givenSupportTeam(team(200L, "SS", "삼성")); // 이 경기의 홈도 원정도 아님

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.QUIZ_NOT_SERVABLE);
    }

    @Test
    @DisplayName("[AC-INN-109-2] 원정 구단으로 응원해도 통과한다 — 홈만 보면 원정 경기 날 전 사용자가 막힌다")
    void getTodayQuizzes_supportAwayTeam_isServable() {
        Game game = defaultServableGame(DEFAULT_INNING);
        givenGame(game);
        givenSupportTeam(awayTeam);
        givenNotServedThisInning(game, DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, GAME_ID, false);

        assertThat(result).isEmpty();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(oxQuiz, multiQuiz));
        givenNoExistingRows(List.of(1L, 2L));
        givenSupportPlayers();
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
        List<Long> ids = quizzes.stream().map(Quiz::getId).toList();
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizUserSubmitRepository.findServedQuizIds(USER_ID, ids)).willReturn(List.of());
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(multiQuiz));
        givenNoExistingRows(List.of(2L));
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(oxQuiz));
        givenNoExistingRows(List.of(1L));
        givenSupportPlayers();
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
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(multiQuiz));
        givenNoExistingRows(List.of(2L));
        givenSupportPlayers();
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
    @DisplayName("[AC-VOTEVIEW-14-1] preferredOnly로 필터링돼 응답에서 빠진 문제는 "
            + "initializeAndRead() 대상에도 포함되지 않는다 — 분포도 실리지 않은 문제와 함께 빠진다")
    void getTodayQuizzes_preferredOnlyFiltered_excludesUnmatchedQuizFromVoteTallyTarget() {
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, homeTeam, null, null);
        givenServable(DEFAULT_INNING);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz));
        givenSupportPlayers();
        givenNoExistingRows(List.of(1L, 2L));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L)))
                .willReturn(List.of(option(teamQuiz, 0, "O")));

        quizService.getTodayQuizzes(USER_ID, GAME_ID, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<Integer>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(quizVoteTally).initializeAndRead(captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys(2L);
    }

    @Test
    @DisplayName("[AC-VOTEVIEW-4-1,5-1] /today 응답 문제 항목의 필드 집합은 기존 그대로다(id·type·"
            + "question·difficulty·point·preferred·options) — answer·liked·likeCount·totalVotes·"
            + "voteRatio 같은 필드는 record에 아예 존재하지 않는다")
    void quizResponse_recordComponents_matchExactFieldSet() {
        List<String> componentNames = java.util.Arrays.stream(QuizResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(componentNames).containsExactlyInAnyOrder(
                "id", "type", "question", "difficulty", "point", "preferred", "options");
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

    // ---------- getQuizVoteRate ----------

    @Test
    @DisplayName("받았고 아직 답하지 않은 문제는 보기별 투표 수를 돌려준다 — 항목 모양이 /today 와 같다")
    void getQuizVoteRate_unanswered_returnsVoteCountsPerOption() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타"), option(quiz, 1, "삼진")));
        given(quizVoteTally.read(1L)).willReturn(Map.of(0, 37L, 1, 12L));

        QuizVoteRateResponse result = quizService.getQuizVoteRate(USER_ID, 1L);

        assertThat(result.quizId()).isEqualTo(1L);
        assertThat(result.options()).extracting(
                        QuizResponse.TodayOptionResponse::no,
                        QuizResponse.TodayOptionResponse::text,
                        QuizResponse.TodayOptionResponse::voteCount)
                .containsExactly(tuple(0, "안타", 37L), tuple(1, "삼진", 12L));
    }

    @Test
    @DisplayName("Redis 가 일부만 돌려주거나(부분 결손) 통째로 비어도 보기는 전부 실린다 — 없는 자리는 0")
    void getQuizVoteRate_partialTally_fillsMissingOptionsWithZero() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타"), option(quiz, 1, "삼진"),
                        option(quiz, 2, "볼넷")));
        // Redis 장애·TTL 만료면 read 가 빈 맵을 준다(예외가 아니다) — 여기서는 1번 보기만 읽힌 상태
        given(quizVoteTally.read(1L)).willReturn(Map.of(1, 5L));

        QuizVoteRateResponse result = quizService.getQuizVoteRate(USER_ID, 1L);

        assertThat(result.options()).extracting(QuizResponse.TodayOptionResponse::voteCount)
                .containsExactly(0L, 5L, 0L);
    }

    @Test
    @DisplayName("받은 적 없는 문제는 null 이다 — 보기도 Redis 도 조회하지 않는다")
    void getQuizVoteRate_neverServed_returnsNullWithoutTouchingOptionsOrTally() {
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.empty());

        assertThat(quizService.getQuizVoteRate(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizOptionRepository, quizVoteTally);
    }

    @Test
    @DisplayName("이미 제출한 문제는 null 이다 — 낸 사람에게 분포를 감추는 것이 이 API 의 목적이다")
    void getQuizVoteRate_alreadyAnswered_returnsNull() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder()
                        .quiz(quiz)
                        .submitOption(option(quiz, 1, "삼진"))
                        .isAnswer(false)
                        .build()));

        assertThat(quizService.getQuizVoteRate(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizOptionRepository, quizVoteTally);
    }

    @Test
    @DisplayName("보기가 하나도 없는 문제는 null 이다 — 0 으로 채울 근거가 없어 Redis 도 읽지 않는다")
    void getQuizVoteRate_noOptions_returnsNullWithoutReadingTally() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build()));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L)).willReturn(List.of());

        assertThat(quizService.getQuizVoteRate(USER_ID, 1L)).isNull();

        verifyNoInteractions(quizVoteTally);
    }

    @Test
    @DisplayName("시한(8분)을 넘긴 미답 행도 그대로 준다 — 폴링 도중 응답이 조용히 비지 않게 한다")
    void getQuizVoteRate_unansweredPastWindow_stillReturnsCounts() {
        Quiz quiz = quiz(1L, "객관식", "다음 타석 결과는?", "MEDIUM", 30.0);
        QuizUserSubmit expired = QuizUserSubmit.builder().quiz(quiz).isAnswer(false).build();
        ReflectionTestUtils.setField(expired, "createdAt", QuizSubmitWindow.now().minusMinutes(30));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(expired));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "안타")));
        given(quizVoteTally.read(1L)).willReturn(Map.of(0, 3L));

        assertThat(quizService.getQuizVoteRate(USER_ID, 1L)).isNotNull();
    }

    @Test
    @DisplayName("투표율 응답의 필드 집합은 quizId·options 둘뿐이고, 보기 항목은 /today 와 같은 타입이다 "
            + "— 서버가 백분율을 계산해 내보내지 않는다")
    void quizVoteRateResponse_recordComponents_areQuizIdAndOptionsOnly() {
        List<java.lang.reflect.RecordComponent> components = java.util.Arrays
                .stream(QuizVoteRateResponse.class.getRecordComponents()).toList();
        assertThat(components).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("quizId", "options");
        assertThat(QuizVoteRateResponse.class.getRecordComponents()[1].getGenericType().getTypeName())
                .contains(QuizResponse.TodayOptionResponse.class.getName());
    }
}
