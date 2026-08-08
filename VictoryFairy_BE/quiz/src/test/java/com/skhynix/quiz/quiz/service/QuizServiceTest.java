package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
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
import com.skhynix.quiz.quiz.dto.QuizResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private QuizService quizService;

    @BeforeEach
    void setUp() {
        Clock fixedKst = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(12, 0), KST).toInstant(), KST);
        quizService = new QuizService(quizRepository, quizOptionRepository,
                userSupportTeamRepository, userSupportPlayerRepository, quizUserSubmitRepository,
                fixedKst);
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
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(USER_ID))
                .willReturn(Optional.empty());
        given(userSupportPlayerRepository.findAllActiveWithPlayerAndTeam(USER_ID))
                .willReturn(List.of());
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
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L)))
                .willReturn(List.of(
                        option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X"),
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).hasSize(2);
        QuizResponse first = result.get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.type()).isEqualTo("O/X");
        assertThat(first.question()).isEqualTo("문동주는 한화 소속이다?");
        assertThat(first.difficulty()).isEqualTo("EASY");
        assertThat(first.point()).isEqualTo(10.0);
        assertThat(first.options())
                .extracting(QuizResponse.OptionResponse::no, QuizResponse.OptionResponse::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "O"),
                        org.assertj.core.groups.Tuple.tuple(1, "X"));
        QuizResponse second = result.get(1);
        assertThat(second.id()).isEqualTo(2L);
        assertThat(second.type()).isEqualTo("객관식");
        assertThat(second.options())
                .extracting(QuizResponse.OptionResponse::no)
                .containsExactly(0, 1, 2, 3);
        // 문제 수와 무관하게 보기 조회는 단 1회 — 문제마다 단건 조회하면 N+1 회귀다
        verify(quizOptionRepository).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L));
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
    @DisplayName("응원팀이 team 또는 opponentTeam과 일치하는 문제가 preferred로 앞서고, "
            + "그룹 안에서는 id ASC가 유지된다")
    void getTodayQuizzes_supportTeamMatches_sortsPreferredFirstKeepingIdAscWithinGroups() {
        Team hanwha = team(100L, "HH", "한화");
        Quiz general = quiz(1L, "객관식", "일반 문제", "EASY", 10.0);
        Quiz teamQuiz = quiz(2L, "객관식", "한화 문제", "EASY", 10.0, hanwha, null, null);
        Quiz matchupQuiz = quiz(3L, "객관식", "KT vs 한화 문제", "EASY", 10.0,
                team(101L, "KT", "KT"), hanwha, null);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(general, teamQuiz, matchupQuiz));
        givenSupportTeam(hanwha);
        givenSupportPlayers();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(2L, 3L, 1L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(2L, 3L, 1L);
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
    @DisplayName("응원팀·응원 선수가 없으면 전부 preferred=false로 id ASC 순서가 그대로다")
    void getTodayQuizzes_noPreference_keepsIdAscWithAllNotPreferred() {
        Quiz first = quiz(1L, "객관식", "문제1", "EASY", 10.0, team(100L, "HH", "한화"), null, null);
        Quiz second = quiz(2L, "객관식", "문제2", "EASY", 10.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(first, second));
        givenNoPreference();
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, false);

        assertThat(result).extracting(QuizResponse::id).containsExactly(1L, 2L);
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
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes(USER_ID, true);

        assertThat(result).extracting(QuizResponse::id).containsExactly(1L, 2L);
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

        QuizDetailResponse result = quizService.getQuiz(USER_ID, 1L);

        assertThat(result.submitted()).isTrue();
        assertThat(result.myOption()).isEqualTo(1);
        assertThat(result.correct()).isFalse();
        assertThat(result.answer()).isEqualTo(0); // quiz() 픽스처의 정답 보기 번호
    }
}
