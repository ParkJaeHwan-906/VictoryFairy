package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
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
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizService}를 리포지토리 목으로 단위 검증한다. DB·Spring 컨텍스트 없음.
 *
 * <p>'오늘'은 KST 고정 클록으로 결정되므로 {@code Clock.fixed}를 직접 주입한다(프로덕션은
 * {@code QuizIngestConfig.kstClock}). 핵심 검증은 세 가지다 — 보기 조회가 문제 수와 무관하게
 * <b>IN 한 방(2쿼리 방식)</b>인지, 선호(응원팀·응원 선수) 정렬·필터가 명세대로인지, 그리고 단건
 * 상세가 미제출 응답에서 정답 관련 필드를 정말 비우는지.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final Long USER_ID = 10L;
    // 프로덕션 기본값(quiz.serve.max-today-count)과 같은 값 — 기존 케이스는 세트가 이보다 훨씬
    // 작아 상한이 발동하지 않는다. 상한 자체의 케이스는 별도로 채워야 한다(AC-INN-6·7·8).
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
    private QuizLikeService quizLikeService;

    @Mock
    private QuizSubmissionTicketStore ticketStore;

    private QuizService quizService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(ZonedDateTime.of(TODAY.atTime(12, 0), KST).toInstant(), KST);
        quizService = newQuizService(MAX_TODAY_COUNT);
    }

    private QuizService newQuizService(int maxTodayCount) {
        return new QuizService(quizRepository, quizOptionRepository,
                userSupportTeamRepository, userSupportPlayerRepository, quizUserSubmitRepository,
                quizLikeService, ticketStore, clock, maxTodayCount);
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
        return quiz(id, typeName, content, difficulty, score, null, null, null);
    }

    private Quiz quiz(Long id, String typeName, String content, String difficulty, Double score,
            Team team, Team opponentTeam, Player player) {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name(typeName).build())
                .team(team)
                .opponentTeam(opponentTeam)
                .player(player)
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

    private void givenNoPreference() {
        givenNoPreference(USER_ID);
    }

    private void givenNoPreference(Long userAccountId) {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId))
                .willReturn(Optional.empty());
        given(userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(userAccountId))
                .willReturn(List.of());
    }

    /** 그룹 내 순열이 갈릴 만큼 충분한 개수(10건)의 무선호 문제 목록. */
    private List<Quiz> manyQuizzes() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
    }

    private void givenSupportTeam(Team team) {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID))
                .willReturn(Optional.of(UserSupportTeam.builder().team(team).build()));
    }

    private void givenSupportPlayers(Player... players) {
        given(userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(USER_ID))
                .willReturn(List.of(players).stream()
                        .map(player -> UserSupportPlayer.builder().player(player).build())
                        .toList());
    }

    // ---------- 오늘의 퀴즈: 2쿼리 방식 ----------

    @Test
    @DisplayName("오늘 문제가 2건이면 보기를 IN 한 방으로 받아 문제별로 묶어 반환한다(2쿼리 방식)")
    void getTodayQuizzes_twoQuizzes_groupsOptionsPerQuizWithSingleInQuery() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        Quiz multiQuiz = quiz(2L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(oxQuiz, multiQuiz));
        givenNoPreference();
        // 두 문제 모두 비선호라 사용자별 셔플로 순서가 바뀔 수 있으므로 어떤 순서로 IN 조회가
        // 나가도 매칭되게 anyList()로 스텁한다 — 순서는 아래 캡처로 별도 검증한다.
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of(
                        option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X"),
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(2);
        Map<Long, QuizResponse> byId = result.stream()
                .collect(Collectors.toMap(QuizResponse::id, r -> r));
        assertThat(byId.get(1L).type()).isEqualTo("O/X");
        assertThat(byId.get(1L).question()).isEqualTo("문동주는 한화 소속이다?");
        assertThat(byId.get(1L).difficulty()).isEqualTo("EASY");
        assertThat(byId.get(1L).point()).isEqualTo(10.0);
        assertThat(byId.get(1L).options())
                .extracting(QuizResponse.OptionResponse::no, QuizResponse.OptionResponse::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "O"),
                        org.assertj.core.groups.Tuple.tuple(1, "X"));
        assertThat(byId.get(2L).type()).isEqualTo("객관식");
        assertThat(byId.get(2L).options())
                .extracting(QuizResponse.OptionResponse::no)
                .containsExactly(0, 1, 2, 3);

        // 문제 수와 무관하게 보기 조회는 단 1회 — 문제마다 단건 조회하면 N+1 회귀다. IN 절
        // 대상은 순서 무관하게 두 문제 id를 정확히 담아야 한다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> quizIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(quizOptionRepository)
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIdsCaptor.capture());
        assertThat(quizIdsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("오늘 출제분이 없으면 빈 리스트를 반환하고 보기·응원 조회 쿼리를 아예 부르지 않는다")
    void getTodayQuizzes_emptyDay_returnsEmptyListWithoutFurtherQueries() {
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportTeamRepository,
                userSupportPlayerRepository);
    }

    @Test
    @DisplayName("보기 행이 없는 문제는 빈 options로 응답한다(NPE 없이 getOrDefault 흡수 — 요구사항 미기재 경계)")
    void getTodayQuizzes_quizWithoutOptionRows_returnsEmptyOptions() {
        Quiz orphan = quiz(7L, "객관식", "보기가 아직 없는 문제", null, null);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(orphan));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(7L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).options()).isEmpty();
        assertThat(result.get(0).point()).isNull();
        assertThat(result.get(0).difficulty()).isNull();
    }

    // ---------- 오늘의 퀴즈: 푼 문제 제외 ----------

    @Test
    @DisplayName("이미 제출한 문제는 오늘 목록에서 빠진다 — 보기 조회도 남은 문제로만 나간다(정책: 푼 문제 비노출)")
    void getTodayQuizzes_submittedQuiz_excludedFromList() {
        Quiz solved = quiz(1L, "O/X", "이미 푼 문제", "EASY", 10.0);
        Quiz unsolved = quiz(2L, "객관식", "아직 안 푼 문제", "MEDIUM", 30.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(solved, unsolved));
        given(quizUserSubmitRepository.findSubmittedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L)))
                .willReturn(List.of(option(unsolved, 0, "LG")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L);
        verify(quizOptionRepository).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L));
    }

    @Test
    @DisplayName("오늘 세트를 전부 풀었으면 빈 리스트 — 응원·보기 조회는 아예 부르지 않는다")
    void getTodayQuizzes_allSubmitted_returnsEmptyWithoutPreferenceQueries() {
        Quiz first = quiz(1L, "O/X", "문제1", "EASY", 10.0);
        Quiz second = quiz(2L, "객관식", "문제2", "MEDIUM", 30.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(first, second));
        given(quizUserSubmitRepository.findSubmittedQuizIds(USER_ID, List.of(1L, 2L)))
                .willReturn(List.of(1L, 2L));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository, userSupportTeamRepository,
                userSupportPlayerRepository);
    }

    // ---------- 오늘의 퀴즈: 선호 정렬 ----------

    @Test
    @DisplayName("응원팀이 team 또는 opponentTeam과 일치하는 문제가 preferred 그룹으로 전부 앞선다"
            + "(그룹 내부 순서는 사용자별 셔플이라 단언하지 않음)")
    void getTodayQuizzes_supportTeamMatches_sortsPreferredGroupFirst() {
        Team hanwha = team(100L, "HH", "한화");
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, hanwha, null, null);
        Quiz matchupQuiz = quiz(3L, "객관식", "KT vs 한화 문제", "EASY", 10.0,
                team(101L, "KT", "KT"), hanwha, null);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz, matchupQuiz));
        givenSupportTeam(hanwha);
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        // 선호 그룹 경계: preferred 문제(id 2,3)가 전부 비선호(id 1)보다 앞선다
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(true, true, false);
    }

    @Test
    @DisplayName("응원 선수가 문제의 player와 일치하면 preferred로 앞선다(응원팀 없이도)")
    void getTodayQuizzes_supportPlayerMatches_sortsPreferredFirst() {
        Player moonDongJu = player(200L, team(100L, "HH", "한화"));
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz playerQuiz = quiz(2L, "객관식", "문동주 문제", "EASY", 10.0, null, null, moonDongJu);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, playerQuiz));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID))
                .willReturn(Optional.empty());
        givenSupportPlayers(moonDongJu);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L, 1L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L, 1L);
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(true, false);
    }

    @Test
    @DisplayName("응원팀·응원 선수가 없으면 대상 team이 있어도 전부 preferred=false다"
            + "(응원 정보가 없으면 매칭 대상이 없음)")
    void getTodayQuizzes_noPreference_allMarkedNotPreferred() {
        Quiz first = quiz(1L, "객관식", "문제1", "EASY", 10.0, team(100L, "HH", "한화"), null, null);
        Quiz second = quiz(2L, "객관식", "문제2", "EASY", 10.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(first, second));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        // 반환 집합 자체는 순서 무관하게 기존과 동일
        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(result).extracting(QuizResponse::preferred).containsExactly(false, false);
    }

    // ---------- 오늘의 퀴즈: preferredOnly ----------

    @Test
    @DisplayName("preferredOnly=true면 선호 문제만 반환하고, 보기 조회도 남은 문제 id로만 나간다")
    void getTodayQuizzes_preferredOnly_returnsOnlyMatchedQuizzes() {
        Team hanwha = team(100L, "HH", "한화");
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, hanwha, null, null);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz));
        givenSupportTeam(hanwha);
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L)))
                .willReturn(List.of(option(teamQuiz, 0, "O")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, true);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L);
        assertThat(result.get(0).preferred()).isTrue();
        verify(quizOptionRepository).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L));
    }

    @Test
    @DisplayName("preferredOnly=true라도 응원팀·응원 선수가 둘 다 없으면 필터 기준이 없으므로 "
            + "전체를 반환한다(no-op)")
    void getTodayQuizzes_preferredOnlyWithoutAnySupport_returnsAllQuizzes() {
        Quiz first = quiz(1L, "객관식", "문제1", "EASY", 10.0);
        Quiz second = quiz(2L, "객관식", "문제2", "EASY", 10.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(first, second));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, true);

        assertThat(result).extracting(QuizResponse::id).containsExactlyInAnyOrder(1L, 2L);
    }

    // ---------- 오늘의 퀴즈: 사용자별 고정 랜덤(shuffleKey) ----------

    @Test
    @DisplayName("같은 사용자로 두 번 호출하면 순서가 동일하다(결정성)")
    void getTodayQuizzes_calledTwiceForSameUser_returnsSameOrder() {
        List<Quiz> quizzes = manyQuizzes();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> firstCall = quizService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();
        List<Long> secondCall = quizService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(secondCall).containsExactlyElementsOf(firstCall);
    }

    @Test
    @DisplayName("서로 다른 계정은 셔플 순서가 다르다 — 문제 수가 충분(10건)해 순열이 갈릴 만큼 "
            + "우연히 전부 같을 확률은 무시할 만하다")
    void getTodayQuizzes_differentAccounts_produceDifferentOrders() {
        List<Quiz> quizzes = manyQuizzes();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> accountIds = List.of(USER_ID, 20L, 300L);
        List<List<Long>> orders = accountIds.stream()
                .map(accountId -> {
                    givenNoPreference(accountId);
                    return quizService.getTodayQuizzes(accountId, false).stream()
                            .map(QuizResponse::id).toList();
                })
                .toList();

        // 세 계정의 순서가 서로 전부 다르다(하나만 달라도 사용자 구분 성질은 성립하지만,
        // 10건 순열 공간에서는 이 강한 형태도 flaky 하지 않다)
        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("문제 하나를 푼 것으로 제외해도 남은 문제들의 상대 순서는 그대로 보존된다"
            + "(부분집합 안정성 — Collections.shuffle이었다면 깨지는 성질)")
    void getTodayQuizzes_afterExcludingSolvedQuiz_preservesRelativeOrderOfRemaining() {
        List<Quiz> quizzes = manyQuizzes();
        List<Long> allIds = quizzes.stream().map(Quiz::getId).toList();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());
        given(quizUserSubmitRepository.findSubmittedQuizIds(USER_ID, allIds)).willReturn(List.of());

        List<Long> fullOrder = quizService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();

        Long solvedId = fullOrder.get(3);
        given(quizUserSubmitRepository.findSubmittedQuizIds(USER_ID, allIds))
                .willReturn(List.of(solvedId));
        List<Long> orderAfterSolving = quizService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();

        List<Long> expectedRemaining = fullOrder.stream()
                .filter(id -> !id.equals(solvedId))
                .toList();
        assertThat(orderAfterSolving).containsExactlyElementsOf(expectedRemaining);
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
    @DisplayName("미제출 문제 상세는 submitted=false이고 myOption·correct·answer가 전부 null이다")
    void getQuiz_notSubmitted_omitsAnswerRelatedFields() {
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
        assertThat(result.myOption()).isNull();
        assertThat(result.correct()).isNull();
        assertThat(result.answer()).isNull();
    }

    @Test
    @DisplayName("제출한 문제 상세는 내 선택·정오·정답을 함께 싣는다(복기 화면)")
    void getQuiz_submitted_includesMyOptionCorrectAndAnswer() {
        Quiz quiz = quiz(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        QuizOption wrongPick = option(quiz, 1, "한화");
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .quiz(quiz)
                .submitOption(wrongPick)
                .isAnswer(false)
                .build();
        given(quizRepository.findById(1L)).willReturn(Optional.of(quiz));
        given(quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(1L))
                .willReturn(List.of(option(quiz, 0, "LG"), wrongPick));
        given(quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(USER_ID, 1L))
                .willReturn(Optional.of(submit));
        given(quizLikeService.likeOf(USER_ID, 1L))
                .willReturn(new QuizLikeResponse(true, 3L));

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.submitted()).isTrue();
        assertThat(result.myOption()).isEqualTo(1);
        assertThat(result.correct()).isFalse();
        assertThat(result.liked()).isTrue();
        assertThat(result.likeCount()).isEqualTo(3L);
        assertThat(result.answer()).isEqualTo(0); // quiz() 픽스처의 정답 보기 번호
    }

    // ---------- 오늘의 퀴즈: 응답 개수 상한(quiz.serve.max-today-count) ----------

    @Test
    @DisplayName("[AC-INN-6-1,6-3] 조건을 만족하는 문제가 25건이면 상한 20에서 잘려 200과 길이 20으로 "
            + "응답한다(에러도 별도 표식도 없다)")
    void getTodayQuizzes_moreThanCap_truncatesToMaxTodayCount() {
        List<Quiz> quizzes = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(20);
    }

    @Test
    @DisplayName("[AC-INN-6-2] 19건이면 상한 20이 발동하지 않아 그대로 19건이다")
    void getTodayQuizzes_underCap_returnsAllWithoutTruncation() {
        List<Quiz> quizzes = IntStream.rangeClosed(1, 19)
                .mapToObj(i -> quiz((long) i, "객관식", "문제" + i, "EASY", 10.0))
                .toList();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(19);
    }

    @Test
    @DisplayName("[AC-INN-7-1,7-2] max-today-count 생성자 인자를 5로 낮추면 같은 세트에서 5건만 "
            + "반환된다(설정값으로 배선돼 있다는 계약 — 하드코딩이면 이 인자가 무시된다)")
    void getTodayQuizzes_customMaxTodayCount_limitsToConfiguredValue() {
        QuizService limitedService = newQuizService(5);
        List<Quiz> quizzes = manyQuizzes(); // 10건
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = limitedService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("[AC-INN-8-1,8-2] 상한은 정렬·필터가 끝난 목록의 앞에서부터 자른다 — 상한 적용 결과는 "
            + "상한 없는 전체 순서의 앞부분과 정확히 일치한다(정렬을 흐트러뜨리는 회귀 방지)")
    void getTodayQuizzes_capTruncatesFromSortedOrderPrefix() {
        List<Quiz> quizzes = manyQuizzes(); // 10건, 무선호
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<Long> fullOrder = quizService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();

        QuizService cappedService = newQuizService(6);
        List<Long> cappedOrder = cappedService.getTodayQuizzes(USER_ID, false).stream()
                .map(QuizResponse::id).toList();

        assertThat(cappedOrder).containsExactlyElementsOf(fullOrder.subList(0, 6));
    }

    @Test
    @DisplayName("[AC-INN-8-4] preferredOnly=true로 5건만 남으면 상한(20)과 무관하게 5건이다")
    void getTodayQuizzes_preferredOnlyFewerThanCap_returnsAllMatched() {
        Team hanwha = team(100L, "HH", "한화");
        List<Quiz> preferredQuizzes = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> quiz((long) i, "객관식", "한화 문제" + i, "EASY", 10.0, hanwha, null, null))
                .toList();
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(preferredQuizzes);
        givenSupportTeam(hanwha);
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, true);

        assertThat(result).hasSize(5);
    }

    // ---------- 오늘의 퀴즈: 제출 티켓 발급(QUIZ-INN-10~16, 34~40) ----------

    @Test
    @DisplayName("[AC-INN-11-1,11-4] 응답에 실린 문제에만 티켓이 발급된다 — 상한으로 잘려 나간 문제는 "
            + "issue()에 실리지 않는다")
    void getTodayQuizzes_issuesTicketsOnlyForServedQuizzes() {
        QuizService cappedService = newQuizService(6);
        List<Quiz> quizzes = manyQuizzes(); // 10건
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(quizzes);
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        List<QuizResponse> result = cappedService.getTodayQuizzes(USER_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Integer>> issuedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ticketStore).issue(eq(USER_ID), issuedCaptor.capture());
        Set<Long> issuedQuizIds = issuedCaptor.getValue().keySet();
        Set<Long> servedIds = result.stream().map(QuizResponse::id).collect(Collectors.toSet());
        assertThat(issuedQuizIds).isEqualTo(servedIds);
        assertThat(issuedQuizIds).hasSize(6); // 상한으로 잘린 4건은 발급 대상에서 빠진다
    }

    @Test
    @DisplayName("[AC-INN-12-1,12-2,40-1] 이닝을 확보한 문제는 games.current_inning 값 그대로, 경기 "
            + "미귀속·이닝 미상 문제는 null로 issue()에 전달된다(둘 다 티켓 자체는 발급된다)")
    void getTodayQuizzes_passesInningsExactlyAsGameCurrentInning() {
        Team home = team(100L, "HH", "한화");
        Team away = team(101L, "KT", "KT");
        GameStatus gameStatus = GameStatus.builder().name("IN_PROGRESS").build();
        Game gameWithInning = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 7, 18, 30))
                .homeTeam(home).awayTeam(away).gameStatus(gameStatus)
                .naverGameId("20260807HHKT02026")
                .currentInning(6)
                .build();
        Quiz gameQuizWithInning = Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .team(home).opponentTeam(away).game(gameWithInning)
                .content("경기 문제").answer(0).quizDate(TODAY).difficulty("EASY").score(10.0)
                .build();
        ReflectionTestUtils.setField(gameQuizWithInning, "id", 1L);

        Game gameWithoutInning = Game.builder() // currentInning 미지정 → null(이닝 미상)
                .gameDate(LocalDateTime.of(2026, 8, 7, 18, 30))
                .homeTeam(home).awayTeam(away).gameStatus(gameStatus)
                .naverGameId("20260807HHKT12026")
                .build();
        Quiz gameQuizWithoutInning = Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .team(home).opponentTeam(away).game(gameWithoutInning)
                .content("이닝 미상 경기 문제").answer(0).quizDate(TODAY).difficulty("EASY").score(10.0)
                .build();
        ReflectionTestUtils.setField(gameQuizWithoutInning, "id", 2L);

        Quiz noGameQuiz = quiz(3L, "객관식", "경기 미귀속 문제", "EASY", 10.0);

        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(gameQuizWithInning, gameQuizWithoutInning, noGameQuiz));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(anyList()))
                .willReturn(List.of());

        quizService.getTodayQuizzes(USER_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Integer>> issuedCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ticketStore).issue(eq(USER_ID), issuedCaptor.capture());
        Map<Long, Integer> issued = issuedCaptor.getValue();
        assertThat(issued).containsKeys(1L, 2L, 3L); // 셋 다 티켓은 발급된다(자격 축과 이닝 값 축은 별개)
        assertThat(issued.get(1L)).isEqualTo(6);
        assertThat(issued.get(2L)).isNull();
        assertThat(issued.get(3L)).isNull();
    }

    @Test
    @DisplayName("[AC-INN-34-1,34-2,35-2] 티켓 발급이 실패하면 예외가 그대로 전파되고(500), 보기 조회는 "
            + "일어나지 않는다 — 목록만 내려주는 부분 성공을 만들지 않는다")
    void getTodayQuizzes_ticketIssueFails_propagatesAndSkipsOptionLookup() {
        Quiz single = quiz(1L, "객관식", "문제1", "EASY", 10.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(single));
        givenNoPreference();
        willThrow(new RedisConnectionFailureException("redis down"))
                .given(ticketStore).issue(eq(USER_ID), anyMap());

        assertThatThrownBy(() -> quizService.getTodayQuizzes(USER_ID, false))
                .isInstanceOf(RedisConnectionFailureException.class);

        verifyNoInteractions(quizOptionRepository);
    }
}
