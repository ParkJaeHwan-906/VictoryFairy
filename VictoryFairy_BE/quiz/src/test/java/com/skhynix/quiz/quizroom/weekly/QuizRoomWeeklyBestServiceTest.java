package com.skhynix.quiz.quizroom.weekly;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.account;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.room;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import com.skhynix.domain.quizroom.entity.QuizRoomWeeklyBest;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomCountView;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeekRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomWeeklyBestRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quizroom.weekly.QuizRoomWeeklyBestService.ConfirmResult;
import com.skhynix.quiz.quizroom.weekly.QuizRoomWeeklyBestService.RankedRoom;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간 베스트 확정·보상 단위. "작성자 본인 제외"와 "그 주에 처음 답한 고유 계정" 정의는 리포지토리 쿼리(JPQL)가 만들어 이 서비스는
 * 집계 결과만 받는다 - 그 두 가지는 실제 DB 가 있어야 값으로 검증할 수 있어 여기서 다루지 않는다(보고 참고).
 */
@ExtendWith(MockitoExtension.class)
class QuizRoomWeeklyBestServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-10-19(월) 00:10 KST = 2026-10-18T15:10Z
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T15:10:00Z"), KST);
    private static final LocalDate WEEK = LocalDate.of(2026, 10, 12);
    private static final String UNKNOWN_UID = "unknown-uid";

    @Mock
    private QuizRoomWeekRepository weekRepository;
    @Mock
    private QuizRoomWeeklyBestRepository bestRepository;
    @Mock
    private QuizRoomLikeRepository likeRepository;
    @Mock
    private QuizRoomAnswerRepository answerRepository;
    @Mock
    private QuizRoomRepository roomRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private QuizRoomWeeklyBestService service;

    @BeforeEach
    void setUp() {
        service = new QuizRoomWeeklyBestService(weekRepository, bestRepository, likeRepository, answerRepository,
                roomRepository, userAccountRepository, CLOCK, UNKNOWN_UID);
    }

    // ---- 헬퍼 ----

    private static QuizRoomCountView count(long roomId, long count) {
        return new QuizRoomCountView(roomId, count);
    }

    /** 방 id -> 소유자 계정으로 방을 만들어 findAllById 가 돌려주게 한다. */
    private Map<Long, QuizRoom> stubRooms(Map<Long, UserAccount> ownerByRoom) {
        Map<Long, QuizRoom> rooms = new java.util.LinkedHashMap<>();
        ownerByRoom.forEach((roomId, owner) -> rooms.put(roomId, room(roomId, owner, 1, 0)));
        given(roomRepository.findAllById(any())).willAnswer(invocation -> {
            List<Long> ids = new ArrayList<>();
            ((Iterable<Long>) invocation.getArgument(0)).forEach(ids::add);
            return ids.stream().map(rooms::get).toList();
        });
        return rooms;
    }

    private void stubLock(UserAccount owner) {
        given(userAccountRepository.findWithLockById(owner.getId())).willReturn(Optional.of(owner));
    }

    private void stubWeekSaved() {
        given(weekRepository.saveAndFlush(any(QuizRoomWeek.class))).willAnswer(invocation -> {
            QuizRoomWeek week = invocation.getArgument(0);
            ReflectionTestUtils.setField(week, "id", 99L);
            return week;
        });
    }

    private void stubScores(List<QuizRoomCountView> likes, List<QuizRoomCountView> solvers) {
        given(likeRepository.countWeeklyLikesByRoom(any(), any())).willReturn(likes);
        given(answerRepository.countWeeklyNewSolversByRoom(any(), any())).willReturn(solvers);
    }

    private static UserAccount owner(long id, long point) {
        UserAccount owner = account(id, "소유자" + id, null);
        ReflectionTestUtils.setField(owner, "uid", "uid-" + id);
        owner.addPoint(point);
        return owner;
    }

    @SuppressWarnings("unchecked")
    private List<QuizRoomWeeklyBest> savedBests() {
        ArgumentCaptor<Iterable<QuizRoomWeeklyBest>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(bestRepository).saveAll(captor.capture());
        List<QuizRoomWeeklyBest> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        return saved;
    }

    // ---- 점수·순위 (정적 규칙) ----

    @Test
    @DisplayName("[QUIZ-CQR-134] 점수는 추천 수 + 새 풀이자 수이고 한쪽에만 있는 방도 센다 (추천 2 + 풀이 4 = 6)")
    void rank_scoreIsLikesPlusNewSolvers() {
        List<RankedRoom> ranked = QuizRoomWeeklyBestService.rank(Map.of(1L, 2L, 2L, 5L), Map.of(1L, 4L, 3L, 1L));

        assertThat(ranked).containsExactly(new RankedRoom(1L, 6L), new RankedRoom(2L, 5L), new RankedRoom(3L, 1L));
    }

    @Test
    @DisplayName("[QUIZ-CQR-136] 점수가 0인 방은 순위에 넣지 않는다")
    void rank_excludesZeroScoreRooms() {
        List<RankedRoom> ranked = QuizRoomWeeklyBestService.rank(Map.of(1L, 0L, 2L, 3L), Map.of(1L, 0L, 4L, 0L));

        assertThat(ranked).containsExactly(new RankedRoom(2L, 3L));
    }

    @Test
    @DisplayName("[QUIZ-CQR-136] 집계가 전부 비어 있으면 빈 순위다")
    void rank_noActivity_isEmpty() {
        assertThat(QuizRoomWeeklyBestService.rank(Map.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("[QUIZ-CQR-137] 점수 [9,7,5,5,5,5] (방 1~6) -> 확정 [1,2,6,5,4]: 동점은 roomId 내림차순이고 방 3은 5위 밖이라 빠진다")
    void rank_tieBreaksByRoomIdDesc_andLimitsToFive() {
        Map<Long, Long> likes = Map.of(1L, 9L, 2L, 7L, 3L, 5L, 4L, 5L, 5L, 5L, 6L, 5L);

        List<RankedRoom> ranked = QuizRoomWeeklyBestService.rank(likes, Map.of());

        assertThat(ranked).extracting(RankedRoom::roomId).containsExactly(1L, 2L, 6L, 5L, 4L);
        assertThat(ranked).extracting(RankedRoom::score).containsExactly(9L, 7L, 5L, 5L, 5L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-137] 후보가 5개 미만이면 있는 만큼만 확정한다 (2개 -> 2개)")
    void rank_fewerThanFive_keepsAll() {
        assertThat(QuizRoomWeeklyBestService.rank(Map.of(10L, 1L, 11L, 1L), Map.of())).hasSize(2);
    }

    // ---- 확정 흐름 ----

    @Test
    @DisplayName("[QUIZ-CQR-133, QUIZ-CQR-134] 집계 구간은 [해당 주 월요일 00:00, 다음 월요일 00:00) 이다 (KST 벽시계 값 그대로)")
    void confirm_queriesHalfOpenWeekRange() {
        stubScores(List.of(), List.of());
        stubWeekSaved();

        service.confirm(WEEK);

        LocalDateTime start = LocalDateTime.of(2026, 10, 12, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 19, 0, 0);
        verify(likeRepository).countWeeklyLikesByRoom(start, end);
        verify(answerRepository).countWeeklyNewSolversByRoom(start, end);
    }

    @Test
    @DisplayName("[QUIZ-CQR-143] 이미 확정된 주면 집계·지급·저장 어느 것도 하지 않고 confirmed=false 를 반환한다")
    void confirm_alreadyConfirmed_doesNothing() {
        given(weekRepository.existsByWeekStart(WEEK)).willReturn(true);

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(false, 0, 0));
        verify(weekRepository, never()).saveAndFlush(any());
        verifyNoInteractions(likeRepository, answerRepository, roomRepository, userAccountRepository, bestRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-136] 활동이 전혀 없는 주도 주 행은 남기고(확정했는데 비었다) 순위·지급은 0건이며 계정을 잠그지 않는다")
    void confirm_noActivity_savesWeekRowOnly() {
        stubScores(List.of(), List.of());
        stubWeekSaved();

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 0, 0));
        ArgumentCaptor<QuizRoomWeek> week = ArgumentCaptor.forClass(QuizRoomWeek.class);
        verify(weekRepository).saveAndFlush(week.capture());
        assertThat(week.getValue().getWeekStart()).isEqualTo(WEEK);
        verify(bestRepository, never()).saveAll(any());
        verifyNoInteractions(userAccountRepository, roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-138] 확정 시각은 주입된 KST Clock 에서 얻는다 (UTC 15:10 -> 2026-10-19 00:10)")
    void confirm_confirmedAtUsesKstClock() {
        stubScores(List.of(), List.of());
        stubWeekSaved();

        service.confirm(WEEK);

        ArgumentCaptor<QuizRoomWeek> week = ArgumentCaptor.forClass(QuizRoomWeek.class);
        verify(weekRepository).saveAndFlush(week.capture());
        assertThat(week.getValue().getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 10));
    }

    @Test
    @DisplayName("[QUIZ-CQR-137, QUIZ-CQR-139] 점수 [9,7,5,5,5,5] 이면 순위 [1,2,6,5,4] 로 저장하고 소유자 5명 모두 +500 이며 방 3의 소유자는 지급이 없다")
    void confirm_ranksTopFive_andRewardsEachOwner() {
        stubScores(List.of(count(1, 9), count(2, 7), count(3, 5), count(4, 5), count(5, 5), count(6, 5)), List.of());
        stubWeekSaved();
        Map<Long, UserAccount> owners = new java.util.LinkedHashMap<>();
        for (long id = 1; id <= 6; id++) {
            owners.put(id, owner(100 + id, 1200));
        }
        stubRooms(owners);
        owners.forEach((roomId, owner) -> {
            if (roomId != 3L) {
                stubLock(owner);
            }
        });

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 5, 5));
        List<QuizRoomWeeklyBest> bests = savedBests();
        assertThat(bests).extracting(best -> best.getRoom().getId()).containsExactly(1L, 2L, 6L, 5L, 4L);
        assertThat(bests).extracting(QuizRoomWeeklyBest::getRankNo).containsExactly(1, 2, 3, 4, 5);
        assertThat(bests).extracting(QuizRoomWeeklyBest::getScore).containsExactly(9L, 7L, 5L, 5L, 5L);
        assertThat(bests).extracting(QuizRoomWeeklyBest::getRewardPoint).containsOnly(500);
        owners.forEach((roomId, owner) ->
                assertThat(owner.getPoint()).as("room %d owner", roomId).isEqualTo(roomId == 3L ? 1200 : 1700));
        verify(userAccountRepository, never()).findWithLockById(owners.get(3L).getId());
    }

    @Test
    @DisplayName("[QUIZ-CQR-139] 지급은 소유자 point 에 정확히 500을 더한다 (1200 -> 1700) 이고 순위 행에 지급 계정과 500이 기록된다")
    void confirm_addsFiveHundredToOwnerPoint() {
        stubScores(List.of(count(7, 3)), List.of());
        stubWeekSaved();
        UserAccount a = owner(11, 1200);
        stubRooms(Map.of(7L, a));
        stubLock(a);

        service.confirm(WEEK);

        assertThat(a.getPoint()).isEqualTo(1700L);
        QuizRoomWeeklyBest best = savedBests().get(0);
        assertThat(best.getRewardedAccount()).isSameAs(a);
        assertThat(best.getRewardPoint()).isEqualTo(500);
    }

    @Test
    @DisplayName("[QUIZ-CQR-140] 보상 경로는 users_bq 를 다루는 협력자를 갖지 않는다 - bq_score 를 바꾸거나 행을 만들 수단이 없다")
    void serviceHasNoBqCollaborator() {
        Constructor<?> constructor = QuizRoomWeeklyBestService.class.getDeclaredConstructors()[0];

        assertThat(Arrays.stream(constructor.getParameterTypes()).map(Class::getSimpleName))
                .noneMatch(name -> name.toLowerCase().contains("bq"));
    }

    @Test
    @DisplayName("[QUIZ-CQR-141] 한 계정의 방 2개가 1위와 4위에 들면 방마다 500씩 +1000 이다 (계정은 한 번만 잠근다)")
    void confirm_sameOwnerOfTwoRooms_isPaidPerRoom() {
        stubScores(List.of(count(1, 10), count(2, 9), count(3, 8), count(4, 7)), List.of());
        stubWeekSaved();
        UserAccount a = owner(11, 0);
        UserAccount b = owner(12, 0);
        UserAccount c = owner(13, 0);
        stubRooms(Map.of(1L, a, 2L, b, 3L, c, 4L, a));
        stubLock(a);
        stubLock(b);
        stubLock(c);

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 4, 4));
        assertThat(a.getPoint()).isEqualTo(1000L);
        assertThat(b.getPoint()).isEqualTo(500L);
        verify(userAccountRepository).findWithLockById(11L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-142] 탈퇴 계정 소유의 방은 순위에 남기되 지급은 없다 (계정 NULL, rewardPoint 0, point 불변)")
    void confirm_withdrawnOwner_keepsRankButNoReward() {
        stubScores(List.of(count(1, 5), count(2, 4), count(3, 3)), List.of());
        stubWeekSaved();
        UserAccount active1 = owner(11, 100);
        UserAccount withdrawn = owner(12, 100);
        withdrawn.withdraw(LocalDateTime.of(2026, 10, 1, 0, 0));
        UserAccount active3 = owner(13, 100);
        stubRooms(Map.of(1L, active1, 2L, withdrawn, 3L, active3));
        stubLock(active1);
        stubLock(withdrawn);
        stubLock(active3);

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 3, 2));
        List<QuizRoomWeeklyBest> bests = savedBests();
        assertThat(bests).extracting(best -> best.getRoom().getId()).containsExactly(1L, 2L, 3L);
        QuizRoomWeeklyBest second = bests.get(1);
        assertThat(second.getRankNo()).isEqualTo(2);
        assertThat(second.getRewardedAccount()).isNull();
        assertThat(second.getRewardPoint()).isZero();
        assertThat(withdrawn.getPoint()).isEqualTo(100L);
        assertThat(active1.getPoint()).isEqualTo(600L);
        assertThat(active3.getPoint()).isEqualTo(600L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-142] (알수없음) 더미 계정 소유의 방도 순위에는 남고 지급은 없다")
    void confirm_unknownDummyOwner_keepsRankButNoReward() {
        stubScores(List.of(count(1, 5)), List.of());
        stubWeekSaved();
        UserAccount dummy = owner(2, 0);
        ReflectionTestUtils.setField(dummy, "uid", UNKNOWN_UID);
        stubRooms(Map.of(1L, dummy));
        stubLock(dummy);

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 1, 0));
        QuizRoomWeeklyBest best = savedBests().get(0);
        assertThat(best.getRankNo()).isEqualTo(1);
        assertThat(best.getRewardedAccount()).isNull();
        assertThat(best.getRewardPoint()).isZero();
        assertThat(dummy.getPoint()).isZero();
    }

    @Test
    @DisplayName("[QUIZ-CQR-142] 소유자 계정이 이미 하드 삭제돼 잠금 조회가 비어도 순위는 남고 지급만 없다")
    void confirm_ownerRowMissing_keepsRankButNoReward() {
        stubScores(List.of(count(1, 5)), List.of());
        stubWeekSaved();
        UserAccount gone = owner(2, 0);
        stubRooms(Map.of(1L, gone));
        given(userAccountRepository.findWithLockById(2L)).willReturn(Optional.empty());

        ConfirmResult result = service.confirm(WEEK);

        assertThat(result).isEqualTo(new ConfirmResult(true, 1, 0));
        assertThat(savedBests().get(0).getRewardedAccount()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-CQR-145] 소유자 계정은 id 오름차순으로 비관적 락을 잡고, 그 락들 뒤에 point 를 더한다 (소유자 30,10,20 -> 락 10,20,30)")
    void confirm_locksOwnersInAscendingIdOrder_beforeAddingPoint() {
        stubScores(List.of(count(1, 9), count(2, 8), count(3, 7)), List.of());
        stubWeekSaved();
        UserAccount o30 = owner(30, 0);
        UserAccount o10 = owner(10, 0);
        UserAccount o20 = owner(20, 0);
        stubRooms(Map.of(1L, o30, 2L, o10, 3L, o20));
        stubLock(o30);
        stubLock(o10);
        stubLock(o20);

        service.confirm(WEEK);

        InOrder order = inOrder(weekRepository, userAccountRepository, bestRepository);
        order.verify(weekRepository).saveAndFlush(any(QuizRoomWeek.class));
        order.verify(userAccountRepository).findWithLockById(10L);
        order.verify(userAccountRepository).findWithLockById(20L);
        order.verify(userAccountRepository).findWithLockById(30L);
        order.verify(bestRepository).saveAll(any());
        assertThat(o10.getPoint()).isEqualTo(500L);
        assertThat(o20.getPoint()).isEqualTo(500L);
        assertThat(o30.getPoint()).isEqualTo(500L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-145] 소유자를 author 그래프로 싣지 않고 방 id 로만 읽는다 - 락 전에 영속성 컨텍스트에 올라간 계정의 오래된 point 로 덮어쓰지 않으려는 구조")
    void confirm_readsRoomsByIdsOnly() {
        stubScores(List.of(count(1, 9), count(2, 8)), List.of());
        stubWeekSaved();
        UserAccount a = owner(11, 0);
        stubRooms(Map.of(1L, a, 2L, a));
        stubLock(a);

        service.confirm(WEEK);

        verify(roomRepository).findAllById(List.of(1L, 2L));
        verify(roomRepository, never()).findWithAuthorById(anyLong());
    }

    // ---- 원자성 ----

    @Test
    @DisplayName("[QUIZ-CQR-143, QUIZ-CQR-144] 주 행 UNIQUE 위반(다른 인스턴스가 먼저 확정)이면 예외를 그대로 던지고 어떤 계정도 잠그거나 지급·순위 저장을 하지 않는다")
    void confirm_weekRowUniqueViolation_propagatesBeforeAnyPayment() {
        stubScores(List.of(count(1, 5)), List.of());
        given(weekRepository.saveAndFlush(any(QuizRoomWeek.class)))
                .willThrow(new DataIntegrityViolationException("uk_quiz_room_weeks_week_start"));

        assertThatThrownBy(() -> service.confirm(WEEK)).isInstanceOf(DataIntegrityViolationException.class);

        verifyNoInteractions(userAccountRepository, bestRepository, roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-144] 순위 저장이 실패하면 예외가 삼켜지지 않고 전파된다 (트랜잭션 롤백으로 확정·지급이 함께 사라진다)")
    void confirm_failureWhileSavingBests_propagates() {
        stubScores(List.of(count(1, 5)), List.of());
        stubWeekSaved();
        UserAccount a = owner(11, 0);
        stubRooms(Map.of(1L, a));
        stubLock(a);
        given(bestRepository.saveAll(any())).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.confirm(WEEK)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[QUIZ-CQR-144] 확정과 지급은 한 @Transactional 이고 읽기 전용이 아니다 (지급 도중 실패하면 확정 행도 롤백된다)")
    void confirm_isSingleWritableTransaction() throws NoSuchMethodException {
        Transactional tx = QuizRoomWeeklyBestService.class.getMethod("confirm", LocalDate.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.rollbackFor()).isEmpty(); // 기본 규칙 - RuntimeException(DataIntegrityViolation 포함)은 롤백
        assertThat(tx.noRollbackFor()).isEmpty();
    }

    @Test
    @DisplayName("[QUIZ-CQR-142] 더미 계정 uid 설정은 user 모듈 UnknownAccountPolicy.UID 와 같은 값이 기본이다 (모듈 간 참조 불가라 옮겨 적은 값의 드리프트 방지)")
    void unknownAccountUidDefault_matchesUserModulePolicy() throws Exception {
        String yaml = new String(getClass().getClassLoader().getResourceAsStream("application.yaml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(yaml).contains("unknown-account-uid: ${QUIZ_ROOM_UNKNOWN_ACCOUNT_UID:568ee3c3-029f-4514-b87f-9d90e729f755}");
    }
}
