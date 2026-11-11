package com.skhynix.quiz.quizroom.service;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.account;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.options;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.quiz;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.room;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomCountView;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeekRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeeklyBestRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomRequest;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomRequest.QuizItem;
import com.skhynix.quiz.quizroom.dto.CreateQuizRoomResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomOptionResponse;
import com.skhynix.quiz.quizroom.dto.RoomDetailResponse;
import com.skhynix.quiz.quizroom.dto.RoomQuizResponse;
import com.skhynix.quiz.quizroom.dto.RoomSummaryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class QuizRoomServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), KST);

    @Mock
    private QuizRoomRepository roomRepository;
    @Mock
    private QuizRoomQuizRepository quizRepository;
    @Mock
    private QuizRoomQuizOptionRepository optionRepository;
    @Mock
    private QuizRoomLikeRepository likeRepository;
    @Mock
    private QuizRoomAnswerRepository answerRepository;
    @Mock
    private QuizRoomWeekRepository weekRepository;
    @Mock
    private QuizRoomWeeklyBestRepository bestRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private QuizRoomSolvingCounter solvingCounter;

    private QuizRoomService service;

    private final UserAccount author = account(1L, "작성자", "profile.jpg");

    @BeforeEach
    void setUp() {
        service = new QuizRoomService(roomRepository, quizRepository, optionRepository, likeRepository,
                answerRepository, weekRepository, bestRepository, userAccountRepository, solvingCounter, CLOCK);
    }

    private static QuizItem mcItem(String question, List<String> options, int answer) {
        return new QuizItem("객관식", question, options, answer);
    }

    private static QuizItem oxItem(String question, List<String> options, int answer) {
        return new QuizItem("O/X", question, options, answer);
    }

    private CreateQuizRoomRequest request(String title, String description, List<QuizItem> items) {
        return new CreateQuizRoomRequest(title, description, items);
    }

    private void stubSaves() {
        given(userAccountRepository.getReferenceById(1L)).willReturn(author);
        given(roomRepository.save(any(QuizRoom.class))).willAnswer(invocation -> {
            QuizRoom saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });
        long[] nextQuizId = {10L};
        given(quizRepository.save(any(QuizRoomQuiz.class))).willAnswer(invocation -> {
            QuizRoomQuiz saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", nextQuizId[0]++);
            return saved;
        });
    }

    @SuppressWarnings("unchecked")
    private List<QuizRoomQuizOption> savedOptions() {
        ArgumentCaptor<List<QuizRoomQuizOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ---- 생성 ----

    @Test
    @DisplayName("[QUIZ-CQR-10, QUIZ-CQR-27] 생성하면 방을 저장하고 roomId 를 반환하며 방의 quizCount 는 요청 문항 수, likeCount 는 0 이다")
    void create_savesRoomAndReturnsRoomId() {
        stubSaves();
        CreateQuizRoomRequest request = request("LG 레전드 퀴즈", "90년대 LG", List.of(
                mcItem("1994 우승 감독은?", List.of("이광환", "김성근", "백인천"), 0), oxItem("LG의 연고지는 서울이다", null, 0)));

        CreateQuizRoomResponse response = service.create(1L, request);

        assertThat(response.roomId()).isEqualTo(100L);
        ArgumentCaptor<QuizRoom> captor = ArgumentCaptor.forClass(QuizRoom.class);
        verify(roomRepository).save(captor.capture());
        QuizRoom saved = captor.getValue();
        assertThat(saved.getAuthor()).isSameAs(author);
        assertThat(saved.getTitle()).isEqualTo("LG 레전드 퀴즈");
        assertThat(saved.getDescription()).isEqualTo("90년대 LG");
        assertThat(saved.getQuizCount()).isEqualTo(2);
        assertThat(saved.getLikeCount()).isZero();
    }

    @Test
    @DisplayName("[QUIZ-CQR-11] 문항은 요청 순서대로 저장되고 객관식 보기 번호는 요청 배열의 0부터 시작하는 인덱스다")
    void create_savesQuizzesInRequestOrder_andNumbersOptionsByIndex() {
        stubSaves();
        CreateQuizRoomRequest request = request("t", "d", List.of(
                mcItem("첫째", List.of("이광환", "김성근", "백인천"), 2), oxItem("둘째", null, 1)));

        service.create(1L, request);

        InOrder order = inOrder(quizRepository);
        ArgumentCaptor<QuizRoomQuiz> captor = ArgumentCaptor.forClass(QuizRoomQuiz.class);
        order.verify(quizRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<QuizRoomQuiz> quizzes = captor.getAllValues();
        assertThat(quizzes).extracting(QuizRoomQuiz::getQuestion).containsExactly("첫째", "둘째");
        assertThat(quizzes).extracting(QuizRoomQuiz::getType)
                .containsExactly(QuizRoomQuizType.MULTIPLE_CHOICE, QuizRoomQuizType.OX);
        assertThat(quizzes).extracting(QuizRoomQuiz::getAnswer).containsExactly(2, 1);

        List<QuizRoomQuizOption> options = savedOptions();
        assertThat(options).extracting(o -> o.getQuiz().getQuestion() + ":" + o.getNo() + ":" + o.getText())
                .containsExactly("첫째:0:이광환", "첫째:1:김성근", "첫째:2:백인천", "둘째:0:O", "둘째:1:X");
    }

    @Test
    @DisplayName("[QUIZ-CQR-12] O/X 문항은 요청 보기와 상관없이 보기를 [0=O, 1=X] 로 저장한다")
    void create_oxOptionsAreFixed() {
        stubSaves();
        CreateQuizRoomRequest request = request("t", "d", List.of(oxItem("q", List.of("맞다", "아니다", "몰라"), 0)));

        service.create(1L, request);

        assertThat(savedOptions()).extracting(QuizRoomQuizOption::getNo, QuizRoomQuizOption::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "O"), org.assertj.core.groups.Tuple.tuple(1, "X"));
    }

    @Test
    @DisplayName("[QUIZ-CQR-28] 제목·소개·문제·보기를 다듬거나 마스킹하지 않고 받은 그대로 저장한다")
    void create_storesTextVerbatim() {
        stubSaves();
        CreateQuizRoomRequest request = request("  앞뒤 공백 제목  ", " 씨발 소개 ",
                List.of(mcItem(" 문제 ", List.of(" 보기1 ", "보기2"), 0)));

        service.create(1L, request);

        ArgumentCaptor<QuizRoom> roomCaptor = ArgumentCaptor.forClass(QuizRoom.class);
        verify(roomRepository).save(roomCaptor.capture());
        assertThat(roomCaptor.getValue().getTitle()).isEqualTo("  앞뒤 공백 제목  ");
        assertThat(roomCaptor.getValue().getDescription()).isEqualTo(" 씨발 소개 ");
        ArgumentCaptor<QuizRoomQuiz> quizCaptor = ArgumentCaptor.forClass(QuizRoomQuiz.class);
        verify(quizRepository).save(quizCaptor.capture());
        assertThat(quizCaptor.getValue().getQuestion()).isEqualTo(" 문제 ");
        assertThat(savedOptions()).extracting(QuizRoomQuizOption::getText).containsExactly(" 보기1 ", "보기2");
    }

    @Test
    @DisplayName("[QUIZ-CQR-12] 생성 시각은 주입된 KST Clock 에서 얻는다 (JVM 기본 존이 아니다)")
    void create_usesKstClock() {
        stubSaves();

        service.create(1L, request("t", "d", List.of(oxItem("q", null, 0))));

        ArgumentCaptor<QuizRoom> captor = ArgumentCaptor.forClass(QuizRoom.class);
        verify(roomRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 12, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-26] 문항 저장 도중 예외가 나면 그대로 전파되고 보기 일괄 저장은 일어나지 않는다 (롤백은 @Transactional 이 맡는다)")
    void create_quizSaveFails_propagatesAndNeverSavesOptions() {
        given(userAccountRepository.getReferenceById(1L)).willReturn(author);
        given(roomRepository.save(any(QuizRoom.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(quizRepository.save(any(QuizRoomQuiz.class))).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.create(1L, request("t", "d", List.of(oxItem("q", null, 0)))))
                .isInstanceOf(IllegalStateException.class);

        verify(optionRepository, never()).saveAll(anyCollection());
    }

    @Test
    @DisplayName("[QUIZ-CQR-26] create 는 쓰기 트랜잭션이고, 조회 메서드는 readOnly 트랜잭션이다")
    void transactionBoundaries_areDeclared() throws Exception {
        Transactional create = QuizRoomService.class
                .getMethod("create", Long.class, CreateQuizRoomRequest.class).getAnnotation(Transactional.class);
        assertThat(create).isNotNull();
        assertThat(create.readOnly()).isFalse();
        for (String name : List.of("getRooms", "getPopularRooms", "getQuizzes")) {
            Transactional tx = java.util.Arrays.stream(QuizRoomService.class.getMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow()
                    .getAnnotation(Transactional.class);
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.readOnly()).as(name).isTrue();
        }
    }

    // ---- 목록 ----

    @Test
    @DisplayName("[QUIZ-CQR-30, QUIZ-CQR-31, QUIZ-CQR-37] 목록은 id 내림차순 페이지를 RoomSummary 로 바꾸고 solvingCount 를 인원 집계에서 채운다")
    void getRooms_mapsPageAndFillsSolvingCount() {
        QuizRoom newer = room(25L, author, 3, 4);
        QuizRoom older = room(24L, author, 2, 0);
        Pageable pageable = PageRequest.of(0, 20);
        given(roomRepository.findAllByOrderByIdDesc(pageable))
                .willReturn(new PageImpl<>(List.of(newer, older), pageable, 25));
        given(solvingCounter.counts(List.of(25L, 24L))).willReturn(Map.of(25L, 3L));

        PageResponse<RoomSummaryResponse> response = service.getRooms(0, 20);

        assertThat(response.totalElements()).isEqualTo(25);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.page()).isZero();
        assertThat(response.content()).extracting(RoomSummaryResponse::roomId).containsExactly(25L, 24L);
        assertThat(response.content()).extracting(RoomSummaryResponse::solvingCount).containsExactly(3L, 0L);
        RoomSummaryResponse first = response.content().get(0);
        assertThat(first.likeCount()).isEqualTo(4);
        assertThat(first.quizCount()).isEqualTo(3);
        assertThat(first.author().nickname()).isEqualTo("작성자");
        assertThat(first.author().profileImgUrl()).isEqualTo("profile.jpg");
    }

    @Test
    @DisplayName("[QUIZ-CQR-30] 방이 하나도 없으면 빈 content 를 반환하고 hasNext 는 false 다")
    void getRooms_empty() {
        Pageable pageable = PageRequest.of(0, 20);
        given(roomRepository.findAllByOrderByIdDesc(pageable)).willReturn(new PageImpl<>(List.of(), pageable, 0));
        given(solvingCounter.counts(List.of())).willReturn(Map.of());

        PageResponse<RoomSummaryResponse> response = service.getRooms(0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.hasNext()).isFalse();
    }

    // ---- 인기 (이번 주 실시간 순위 + 확정 주간 베스트로 보충) ----

    private QuizRoomWeek confirmedWeek(long weekId) {
        QuizRoomWeek week = QuizRoomWeek.builder().weekStart(LocalDate.of(2026, 10, 5))
                .confirmedAt(LocalDateTime.of(2026, 10, 12, 0, 1)).build();
        ReflectionTestUtils.setField(week, "id", weekId);
        return week;
    }

    private static QuizRoomWeeklyBest best(QuizRoomWeek week, int rank, QuizRoom room) {
        return QuizRoomWeeklyBest.builder().week(week).rankNo(rank).room(room).score(10L - rank).build();
    }

    private static QuizRoomCountView count(long roomId, long count) {
        return new QuizRoomCountView(roomId, count);
    }

    /** 이번 주 집계를 심고, 순위에 든 방은 요청된 id 중 {@code loadable} 에 있는 것만 (역순으로) 돌려준다 — 정렬을 리포지토리 순서에 기대지 않게. */
    private void stubThisWeek(List<QuizRoomCountView> likes, List<QuizRoomCountView> solvers, QuizRoom... loadable) {
        given(likeRepository.countWeeklyLikesByRoom(any(), any())).willReturn(likes);
        given(answerRepository.countWeeklyNewSolversByRoom(any(), any())).willReturn(solvers);
        if (loadable.length > 0) {
            given(roomRepository.findAllWithAuthorByIdIn(anyCollection())).willAnswer(invocation -> {
                Collection<Long> ids = invocation.getArgument(0);
                List<QuizRoom> found = new ArrayList<>(
                        java.util.Arrays.stream(loadable).filter(r -> ids.contains(r.getId())).toList());
                java.util.Collections.reverse(found);
                return found;
            });
        }
    }

    private void stubLatestConfirmed(QuizRoomWeek week, QuizRoom... ranked) {
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        List<QuizRoomWeeklyBest> bests = new ArrayList<>();
        for (int i = 0; i < ranked.length; i++) {
            bests.add(best(week, i + 1, ranked[i]));
        }
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(week.getId())).willReturn(bests);
    }

    private List<Long> popularIds() {
        return service.getPopularRooms().stream().map(RoomSummaryResponse::roomId).toList();
    }

    @Test
    @DisplayName("[QUIZ-CQR-150] 이번 주 점수 내림차순, 동점은 roomId 내림차순으로 앞에 놓고 0점 방은 뺀다 (방1=4, 방2=7, 방3=4, 방4=0 -> [2,3,1])")
    void getPopularRooms_ranksThisWeekByScoreDescThenRoomIdDesc_excludingZero() {
        stubThisWeek(List.of(count(1, 4), count(2, 7), count(3, 4), count(4, 0)), List.of(),
                room(1L, author, 1, 0), room(2L, author, 1, 0), room(3L, author, 1, 0), room(4L, author, 1, 0));

        assertThat(popularIds()).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-150, QUIZ-CQR-151] 점수는 추천 수와 새 풀이자 수의 합이다 (방1 추천2+풀이3=5 가 방2 추천4 보다 앞)")
    void getPopularRooms_scoreIsLikesPlusNewSolvers() {
        stubThisWeek(List.of(count(1, 2), count(2, 4)), List.of(count(1, 3)),
                room(1L, author, 1, 0), room(2L, author, 1, 0));

        assertThat(popularIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-150] 점수 1 이상인 방이 6개 이상이면 상위 5개뿐이고, 5개가 찼으므로 확정 주간 베스트는 읽지 않는다")
    void getPopularRooms_capsAtFive_andSkipsConfirmedLookupWhenFull() {
        stubThisWeek(List.of(count(1, 9), count(2, 8), count(3, 7), count(4, 6), count(5, 5), count(6, 4)), List.of(),
                room(1L, author, 1, 0), room(2L, author, 1, 0), room(3L, author, 1, 0), room(4L, author, 1, 0),
                room(5L, author, 1, 0), room(6L, author, 1, 0));

        assertThat(popularIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        verifyNoInteractions(weekRepository, bestRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-150] 이번 주 구간은 [이번 주 월요일 00:00, 다음 월요일 00:00) KST 로 두 집계에 똑같이 전달된다 (수요일 -> 10/12 ~ 10/19)")
    void getPopularRooms_queriesCurrentWeekWindow() {
        service.getPopularRooms();

        LocalDateTime start = LocalDateTime.of(2026, 10, 12, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 19, 0, 0);
        verify(likeRepository).countWeeklyLikesByRoom(start, end);
        verify(answerRepository).countWeeklyNewSolversByRoom(start, end);
    }

    @Test
    @DisplayName("[QUIZ-CQR-151] 파드 UTC 일요일 14:59:59 (KST 일요일 23:59:59) 는 아직 지난 주 구간이다")
    void getPopularRooms_sundayJustBeforeKstMidnight_isStillOldWeek() {
        QuizRoomService atSunday = serviceAt(Instant.parse("2026-10-18T14:59:59Z"));

        atSunday.getPopularRooms();

        verify(likeRepository).countWeeklyLikesByRoom(LocalDateTime.of(2026, 10, 12, 0, 0),
                LocalDateTime.of(2026, 10, 19, 0, 0));
        verify(answerRepository).countWeeklyNewSolversByRoom(LocalDateTime.of(2026, 10, 12, 0, 0),
                LocalDateTime.of(2026, 10, 19, 0, 0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-151] 파드 UTC 일요일 15:00:00 (KST 월요일 00:00:00) 부터는 새 주 구간이 된다")
    void getPopularRooms_utcSunday1500_isKstMondayAndStartsNewWeek() {
        QuizRoomService atMonday = serviceAt(Instant.parse("2026-10-18T15:00:00Z"));

        atMonday.getPopularRooms();

        verify(likeRepository).countWeeklyLikesByRoom(LocalDateTime.of(2026, 10, 19, 0, 0),
                LocalDateTime.of(2026, 10, 26, 0, 0));
        verify(answerRepository).countWeeklyNewSolversByRoom(LocalDateTime.of(2026, 10, 19, 0, 0),
                LocalDateTime.of(2026, 10, 26, 0, 0));
    }

    private QuizRoomService serviceAt(Instant instant) {
        return new QuizRoomService(roomRepository, quizRepository, optionRepository, likeRepository,
                answerRepository, weekRepository, bestRepository, userAccountRepository, solvingCounter,
                Clock.fixed(instant, KST));
    }

    @Test
    @DisplayName("[QUIZ-CQR-152] 이번 주 순위가 5개 미만이면 남는 자리를 가장 최근 확정 주간 베스트의 확정 순위 순서로 채운다 ([9] + 확정 [7,3] -> [9,7,3])")
    void getPopularRooms_fillsRemainingSlotsFromLatestConfirmedInRankOrder() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(9, 3)), List.of(), room(9L, author, 1, 0));
        stubLatestConfirmed(week, room(7L, author, 1, 0), room(3L, author, 1, 99));

        assertThat(popularIds()).containsExactly(9L, 7L, 3L);
        verify(bestRepository).findAllByWeek_IdOrderByRankNoAsc(5L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-152] 채우기는 5건까지만이다 (이번 주 3개 + 확정 4개 -> 앞의 2개만 보충해 5건)")
    void getPopularRooms_fillStopsAtFive() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(1, 3), count(2, 2), count(3, 1)), List.of(),
                room(1L, author, 1, 0), room(2L, author, 1, 0), room(3L, author, 1, 0));
        stubLatestConfirmed(week, room(14L, author, 1, 0), room(15L, author, 1, 0), room(16L, author, 1, 0),
                room(17L, author, 1, 0));

        assertThat(popularIds()).containsExactly(1L, 2L, 3L, 14L, 15L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-152] 이번 주 활동이 전혀 없으면 확정 주간 베스트가 확정 순위 그대로 나온다 (QUIZ-CQR-138 의 인기 목록 순서 = 확정 순위)")
    void getPopularRooms_noActivity_showsConfirmedBestAsIs() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubLatestConfirmed(week, room(7L, author, 1, 0), room(3L, author, 1, 99), room(9L, author, 1, 50));

        assertThat(popularIds()).containsExactly(7L, 3L, 9L);
        verify(roomRepository, never()).findAllWithAuthorByIdIn(anyCollection());
    }

    @Test
    @DisplayName("[QUIZ-CQR-153] 이번 주 순위에 이미 든 방은 채우기에서 건너뛴다 (이번 주 [3,9] + 확정 [7,3,5,8] -> [3,9,7,5,8])")
    void getPopularRooms_skipsRoomsAlreadyRankedThisWeek() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(3, 9), count(9, 5)), List.of(), room(3L, author, 1, 0), room(9L, author, 1, 0));
        stubLatestConfirmed(week, room(7L, author, 1, 0), room(3L, author, 1, 0), room(5L, author, 1, 0),
                room(8L, author, 1, 0));

        assertThat(popularIds()).containsExactly(3L, 9L, 7L, 5L, 8L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-153, QUIZ-CQR-34] 확정 결과가 이미 든 방 하나뿐이면 중복 없이 길이 2다 (이번 주 [3,9] + 확정 [3] -> [3,9])")
    void getPopularRooms_confirmedOnlyDuplicate_addsNothing() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(3, 9), count(9, 5)), List.of(), room(3L, author, 1, 0), room(9L, author, 1, 0));
        stubLatestConfirmed(week, room(3L, author, 1, 0));

        assertThat(popularIds()).containsExactly(3L, 9L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-34] 이번 주 점수 1 이상인 방 1개 + 확정 주간 베스트 1개(다른 방) -> 길이 2")
    void getPopularRooms_oneThisWeekPlusOneConfirmed_hasSizeTwo() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(4, 2)), List.of(), room(4L, author, 1, 0));
        stubLatestConfirmed(week, room(8L, author, 1, 0));

        assertThat(popularIds()).containsExactly(4L, 8L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-154, QUIZ-CQR-34] 이번 주 점수 방도 확정된 주도 없으면 null 이 아닌 빈 목록이고 Redis 인원도 방 로딩도 하지 않는다")
    void getPopularRooms_nothingAtAll_isEmptyAndSkipsRedis() {
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.empty());

        assertThat(service.getPopularRooms()).isNotNull().isEmpty();
        verifyNoInteractions(solvingCounter, bestRepository, roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-154, QUIZ-CQR-34] 이번 주 점수가 전부 0이고 가장 최근 확정 결과가 빈 목록이어도 빈 목록이고 Redis 인원을 읽지 않는다")
    void getPopularRooms_zeroScoresAndEmptyConfirmedWeek_isEmpty() {
        stubThisWeek(List.of(count(1, 0)), List.of(count(2, 0)));
        stubLatestConfirmed(confirmedWeek(5L));

        assertThat(service.getPopularRooms()).isNotNull().isEmpty();
        verifyNoInteractions(solvingCounter, roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-132] likeCount 는 방의 현재 값, solvingCount 는 응답 시점 Redis 인원이다 (이번 주 방·채워진 방 모두)")
    void getPopularRooms_fillsCountsAtResponseTime_forRankedAndFilledRooms() {
        QuizRoomWeek week = confirmedWeek(5L);
        QuizRoom ranked = room(9L, author, 1, 4);
        QuizRoom filled = room(7L, author, 1, 11);
        stubThisWeek(List.of(count(9, 1)), List.of(), ranked);
        stubLatestConfirmed(week, filled);
        given(solvingCounter.counts(List.of(9L, 7L))).willReturn(Map.of(7L, 6L));

        List<RoomSummaryResponse> popular = service.getPopularRooms();

        assertThat(popular).extracting(RoomSummaryResponse::roomId).containsExactly(9L, 7L);
        assertThat(popular).extracting(RoomSummaryResponse::likeCount).containsExactly(4L, 11L);
        assertThat(popular).extracting(RoomSummaryResponse::solvingCount).containsExactly(0L, 6L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-155] 캐시가 없다 - 연속 호출이 매번 집계를 다시 조회하고 바뀐 점수가 바로 순서에 반영된다")
    void getPopularRooms_noCache_recomputesOnEveryCall() {
        QuizRoom room1 = room(1L, author, 1, 0);
        QuizRoom room2 = room(2L, author, 1, 0);
        given(roomRepository.findAllWithAuthorByIdIn(anyCollection())).willReturn(List.of(room1, room2));
        given(likeRepository.countWeeklyLikesByRoom(any(), any()))
                .willReturn(List.of(count(1, 3), count(2, 2)))
                .willReturn(List.of(count(1, 3), count(2, 4)))
                .willReturn(List.of(count(1, 3), count(2, 2)));
        given(answerRepository.countWeeklyNewSolversByRoom(any(), any())).willReturn(List.of());
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.empty());

        assertThat(popularIds()).containsExactly(1L, 2L);
        assertThat(popularIds()).containsExactly(2L, 1L);
        assertThat(popularIds()).containsExactly(1L, 2L);

        verify(likeRepository, times(3)).countWeeklyLikesByRoom(any(), any());
        verify(answerRepository, times(3)).countWeeklyNewSolversByRoom(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-156] 인기 목록 조회는 주간 베스트를 확정하지 않고 소유자 point 도 건드리지 않는다 (읽기 호출 외에 아무것도 호출하지 않는다)")
    void getPopularRooms_neverConfirmsOrRewards() {
        QuizRoomWeek week = confirmedWeek(5L);
        stubThisWeek(List.of(count(9, 3)), List.of(count(9, 2)), room(9L, author, 1, 0));
        stubLatestConfirmed(week, room(7L, author, 1, 0));

        for (int i = 0; i < 100; i++) {
            service.getPopularRooms();
        }

        verify(weekRepository, times(100)).findTopByOrderByWeekStartDesc();
        verifyNoMoreInteractions(weekRepository);
        verify(bestRepository, times(100)).findAllByWeek_IdOrderByRankNoAsc(5L);
        verifyNoMoreInteractions(bestRepository);
        verify(roomRepository, times(100)).findAllWithAuthorByIdIn(anyCollection());
        verifyNoMoreInteractions(roomRepository);
        verifyNoInteractions(userAccountRepository);
        assertThat(author.getPoint()).isEqualTo(0L);
    }

    // ---- 상세 ----

    @Test
    @DisplayName("[QUIZ-CQR-27, QUIZ-CQR-40] 작성자가 막 만든 방의 상세는 likeCount 0, liked false, isAuthor true, quizCount 는 문항 수다")
    void getRoom_authorViewsOwnNewRoom() {
        QuizRoom room = room(1L, author, 5, 0);
        given(roomRepository.findWithAuthorById(1L)).willReturn(java.util.Optional.of(room));
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(1L, 1L)).willReturn(false);
        given(solvingCounter.count(1L)).willReturn(2L);

        RoomDetailResponse detail = service.getRoom(1L, 1L);

        assertThat(detail.likeCount()).isZero();
        assertThat(detail.liked()).isFalse();
        assertThat(detail.isAuthor()).isTrue();
        assertThat(detail.quizCount()).isEqualTo(5);
        assertThat(detail.solvingCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-40] 다른 계정이 추천한 방을 보면 liked true, isAuthor false")
    void getRoom_nonAuthorWhoLiked() {
        QuizRoom room = room(1L, author, 3, 1);
        given(roomRepository.findWithAuthorById(1L)).willReturn(java.util.Optional.of(room));
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(2L, 1L)).willReturn(true);
        given(solvingCounter.count(1L)).willReturn(0L);

        RoomDetailResponse detail = service.getRoom(2L, 1L);

        assertThat(detail.liked()).isTrue();
        assertThat(detail.isAuthor()).isFalse();
        assertThat(detail.author().nickname()).isEqualTo("작성자");
    }

    @Test
    @DisplayName("[QUIZ-CQR-41] 없는 방 상세는 QUIZ_ROOM_NOT_FOUND 이고 추천·인원 조회까지 가지 않는다")
    void getRoom_notFound() {
        given(roomRepository.findWithAuthorById(999999L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getRoom(1L, 999999L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        verifyNoInteractions(likeRepository, solvingCounter);
    }

    @Test
    @DisplayName("[QUIZ-CQR-120] 탈퇴했지만 아직 지워지지 않은 작성자의 방도 예전 닉네임 그대로 노출한다 (서비스가 탈퇴 상태로 걸러내지 않는다)")
    void getRoom_withdrawnAuthorStillShownWithOldNickname() {
        UserAccount withdrawn = account(5L, "예전닉네임", null);
        withdrawn.withdraw(LocalDateTime.of(2026, 10, 1, 0, 0));
        QuizRoom room = room(1L, withdrawn, 1, 0);
        given(roomRepository.findWithAuthorById(1L)).willReturn(java.util.Optional.of(room));
        given(solvingCounter.count(1L)).willReturn(0L);

        RoomDetailResponse detail = service.getRoom(2L, 1L);

        assertThat(detail.author().nickname()).isEqualTo("예전닉네임");
        assertThat(detail.author().profileImgUrl()).isNull();
    }

    // ---- 퀴즈 리스트 ----

    @Test
    @DisplayName("[QUIZ-CQR-42, QUIZ-CQR-11] 퀴즈 리스트는 문항을 생성 순서대로, 보기를 번호 순으로 싣는다")
    void getQuizzes_returnsQuizzesInOrderWithOptions() {
        QuizRoom room = room(1L, author, 2, 0);
        QuizRoomQuiz first = quiz(11L, room, QuizRoomQuizType.MULTIPLE_CHOICE, "첫째", 0);
        QuizRoomQuiz second = quiz(12L, room, QuizRoomQuizType.OX, "둘째", 1);
        given(roomRepository.existsById(1L)).willReturn(true);
        List<QuizRoomQuizOption> all = new ArrayList<>(options(first, "이광환", "김성근", "백인천"));
        all.addAll(options(second, "O", "X"));
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(all);
        given(quizRepository.findAllByRoom_IdOrderByIdAsc(1L)).willReturn(List.of(first, second));

        List<RoomQuizResponse> quizzes = service.getQuizzes(1L);

        assertThat(quizzes).extracting(RoomQuizResponse::quizId).containsExactly(11L, 12L);
        assertThat(quizzes).extracting(RoomQuizResponse::type).containsExactly("객관식", "O/X");
        assertThat(quizzes.get(0).options()).containsExactly(new QuizRoomOptionResponse(0, "이광환"),
                new QuizRoomOptionResponse(1, "김성근"), new QuizRoomOptionResponse(2, "백인천"));
        assertThat(quizzes.get(1).options()).containsExactly(new QuizRoomOptionResponse(0, "O"),
                new QuizRoomOptionResponse(1, "X"));
    }

    @Test
    @DisplayName("[QUIZ-CQR-43] 퀴즈 리스트 응답 타입에는 answer·correct·count·voteCount 를 담을 자리가 없다")
    void roomQuizResponse_hasNoAnswerOrVoteComponent() {
        assertThat(RoomQuizResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("quizId", "type", "question", "options");
        assertThat(QuizRoomOptionResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("no", "text");
    }

    @Test
    @DisplayName("[QUIZ-CQR-44] 없는 방의 퀴즈 리스트는 QUIZ_ROOM_NOT_FOUND 이고 문항을 읽지 않는다")
    void getQuizzes_roomNotFound() {
        given(roomRepository.existsById(999999L)).willReturn(false);

        assertThatThrownBy(() -> service.getQuizzes(999999L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        verifyNoInteractions(quizRepository, optionRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-45] 상세·퀴즈 리스트 조회는 어떤 저장·삭제도 호출하지 않는다")
    void reads_neverWrite() {
        QuizRoom room = room(1L, author, 1, 0);
        given(roomRepository.findWithAuthorById(1L)).willReturn(java.util.Optional.of(room));
        given(solvingCounter.count(1L)).willReturn(0L);
        given(roomRepository.existsById(1L)).willReturn(true);
        given(optionRepository.findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(1L)).willReturn(List.of());
        given(quizRepository.findAllByRoom_IdOrderByIdAsc(1L)).willReturn(List.of());

        service.getRoom(1L, 1L);
        for (int i = 0; i < 10; i++) {
            service.getQuizzes(1L);
        }

        verify(roomRepository, never()).save(any());
        verify(quizRepository, never()).save(any());
        verify(optionRepository, never()).saveAll(anyCollection());
        verifyNoInteractions(userAccountRepository);
    }
}
