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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import com.skhynix.domain.quizroom.entity.QuizRoomQuizType;
import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
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
                weekRepository, bestRepository, userAccountRepository, solvingCounter, CLOCK);
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

    // ---- 인기 (주간 확정 결과 조회) ----

    private QuizRoomWeek confirmedWeek(long weekId) {
        QuizRoomWeek week = QuizRoomWeek.builder().weekStart(LocalDate.of(2026, 10, 5))
                .confirmedAt(LocalDateTime.of(2026, 10, 12, 0, 1)).build();
        ReflectionTestUtils.setField(week, "id", weekId);
        return week;
    }

    private static QuizRoomWeeklyBest best(QuizRoomWeek week, int rank, QuizRoom room) {
        return QuizRoomWeeklyBest.builder().week(week).rankNo(rank).room(room).score(10L - rank).build();
    }

    @Test
    @DisplayName("[QUIZ-CQR-130] 가장 최근 확정 주의 방을 확정 순위 순서 그대로 반환한다 (likeCount 가 더 큰 방이 뒤에 있어도 재정렬하지 않는다)")
    void getPopularRooms_keepsConfirmedRankOrder() {
        QuizRoomWeek week = confirmedWeek(5L);
        QuizRoom first = room(7L, author, 1, 0);
        QuizRoom second = room(3L, author, 1, 99);
        QuizRoom third = room(9L, author, 1, 50);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L))
                .willReturn(List.of(best(week, 1, first), best(week, 2, second), best(week, 3, third)));
        given(solvingCounter.counts(List.of(7L, 3L, 9L))).willReturn(Map.of());

        List<RoomSummaryResponse> popular = service.getPopularRooms();

        assertThat(popular).extracting(RoomSummaryResponse::roomId).containsExactly(7L, 3L, 9L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-130] 순위는 최신 확정 주의 id 로만 읽는다 (다른 주를 훑지 않는다)")
    void getPopularRooms_readsOnlyLatestConfirmedWeek() {
        QuizRoomWeek week = confirmedWeek(5L);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L)).willReturn(List.of());

        service.getPopularRooms();

        verify(bestRepository).findAllByWeek_IdOrderByRankNoAsc(5L);
        verify(weekRepository, never()).findAll();
    }

    @Test
    @DisplayName("[QUIZ-CQR-132] likeCount 는 방의 현재 값, solvingCount 는 응답 시점의 Redis 인원으로 채우고 순서는 바뀌지 않는다 (QUIZ-CQR-131)")
    void getPopularRooms_fillsCountsAtResponseTime() {
        QuizRoomWeek week = confirmedWeek(5L);
        QuizRoom first = room(7L, author, 1, 4);
        QuizRoom second = room(3L, author, 1, 0);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L))
                .willReturn(List.of(best(week, 1, first), best(week, 2, second)));
        given(solvingCounter.counts(List.of(7L, 3L))).willReturn(Map.of(3L, 6L));

        List<RoomSummaryResponse> popular = service.getPopularRooms();

        assertThat(popular).extracting(RoomSummaryResponse::likeCount).containsExactly(4L, 0L);
        assertThat(popular).extracting(RoomSummaryResponse::solvingCount).containsExactly(0L, 6L);

        first.increaseLikeCount();
        List<RoomSummaryResponse> later = service.getPopularRooms();
        assertThat(later).extracting(RoomSummaryResponse::roomId).containsExactly(7L, 3L);
        assertThat(later.get(0).likeCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-131] 인원이 아무리 많아도 확정 결과만 읽을 뿐 방 후보 쿼리를 쓰지 않는다")
    void getPopularRooms_neverQueriesRoomCandidates() {
        QuizRoomWeek week = confirmedWeek(5L);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L))
                .willReturn(List.of(best(week, 1, room(7L, author, 1, 0)), best(week, 2, room(3L, author, 1, 0))));
        given(solvingCounter.counts(List.of(7L, 3L))).willReturn(Map.of(3L, 100L));

        List<Long> before = service.getPopularRooms().stream().map(RoomSummaryResponse::roomId).toList();
        List<Long> after = service.getPopularRooms().stream().map(RoomSummaryResponse::roomId).toList();

        assertThat(after).isEqualTo(before).containsExactly(7L, 3L);
        verifyNoInteractions(roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-147] 확정된 주가 한 번도 없으면 null 이 아닌 빈 목록이고 Redis 인원도 읽지 않는다")
    void getPopularRooms_noConfirmedWeek_isEmpty() {
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.empty());

        assertThat(service.getPopularRooms()).isNotNull().isEmpty();
        verifyNoInteractions(bestRepository, solvingCounter);
    }

    @Test
    @DisplayName("[QUIZ-CQR-34, QUIZ-CQR-136] 최신 확정 주가 비어 있으면(0건 확정) 빈 목록이고 Redis 인원도 읽지 않는다")
    void getPopularRooms_confirmedButEmptyWeek_isEmpty() {
        QuizRoomWeek week = confirmedWeek(5L);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L)).willReturn(List.of());

        assertThat(service.getPopularRooms()).isNotNull().isEmpty();
        verifyNoInteractions(solvingCounter);
    }

    @Test
    @DisplayName("[QUIZ-CQR-34] 확정된 방이 2건이면 2건을 반환한다")
    void getPopularRooms_fewerThanFive() {
        QuizRoomWeek week = confirmedWeek(5L);
        given(weekRepository.findTopByOrderByWeekStartDesc()).willReturn(Optional.of(week));
        given(bestRepository.findAllByWeek_IdOrderByRankNoAsc(5L))
                .willReturn(List.of(best(week, 1, room(2L, author, 1, 0)), best(week, 2, room(1L, author, 1, 0))));
        given(solvingCounter.counts(List.of(2L, 1L))).willReturn(Map.of());

        assertThat(service.getPopularRooms()).hasSize(2);
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
