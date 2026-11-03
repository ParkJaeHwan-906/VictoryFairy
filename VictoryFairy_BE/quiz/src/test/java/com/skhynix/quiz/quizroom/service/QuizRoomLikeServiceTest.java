package com.skhynix.quiz.quizroom.service;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.account;
import static com.skhynix.quiz.quizroom.QuizRoomFixtures.room;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomLike;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quizroom.dto.QuizRoomLikeResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class QuizRoomLikeServiceTest {

    private static final Long USER_ID = 7L;
    private static final long ROOM_ID = 1L;
    // UTC 03:00 = KST 12:00. 추천 시각은 주간 점수의 KST 월요일 경계와 비교되므로 KST 값이어야 한다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private QuizRoomRepository roomRepository;
    @Mock
    private QuizRoomLikeRepository likeRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private QuizRoomLikeService service;

    @BeforeEach
    void setUp() {
        service = new QuizRoomLikeService(roomRepository, likeRepository, userAccountRepository, CLOCK);
    }

    private final UserAccount author = account(1L, "작성자", null);

    private QuizRoom stubRoom(long likeCount) {
        QuizRoom room = room(ROOM_ID, author, 3, likeCount);
        given(roomRepository.findWithLockById(ROOM_ID)).willReturn(Optional.of(room));
        return room;
    }

    @Test
    @DisplayName("[QUIZ-CQR-70] 추천하지 않은 상태에서 liked:true 를 보내면 추천 행을 만들고 likeCount 를 1 올린다")
    void setLiked_true_createsLikeAndIncrements() {
        QuizRoom room = stubRoom(0);
        UserAccount reference = account(USER_ID, "나", null);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(false);
        given(userAccountRepository.getReferenceById(USER_ID)).willReturn(reference);

        QuizRoomLikeResponse response = service.setLiked(USER_ID, ROOM_ID, true);

        assertThat(response).isEqualTo(new QuizRoomLikeResponse(true, 1L));
        ArgumentCaptor<QuizRoomLike> captor = ArgumentCaptor.forClass(QuizRoomLike.class);
        verify(likeRepository).save(captor.capture());
        assertThat(captor.getValue().getUserAccount()).isSameAs(reference);
        assertThat(captor.getValue().getRoom()).isSameAs(room);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 12, 0));
        assertThat(room.getLikeCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-71] 추천한 상태에서 liked:false 를 보내면 추천 행을 지우고 likeCount 를 1 내린다")
    void setLiked_false_deletesLikeAndDecrements() {
        QuizRoom room = stubRoom(1);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(true);

        QuizRoomLikeResponse response = service.setLiked(USER_ID, ROOM_ID, false);

        assertThat(response).isEqualTo(new QuizRoomLikeResponse(false, 0L));
        verify(likeRepository).deleteByUserAccountIdAndRoomId(USER_ID, ROOM_ID);
        assertThat(room.getLikeCount()).isZero();
    }

    @Test
    @DisplayName("[QUIZ-CQR-72] 이미 추천한 상태에서 liked:true 를 다시 보내면 행도 카운터도 바뀌지 않고 같은 값을 돌려준다")
    void setLiked_trueAgain_isIdempotent() {
        QuizRoom room = stubRoom(1);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(true);

        for (int i = 0; i < 10; i++) {
            assertThat(service.setLiked(USER_ID, ROOM_ID, true)).isEqualTo(new QuizRoomLikeResponse(true, 1L));
        }

        verify(likeRepository, never()).save(any());
        verify(likeRepository, never()).deleteByUserAccountIdAndRoomId(any(), any());
        assertThat(room.getLikeCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-72] 추천하지 않은 상태에서 liked:false 를 보내면 200 이고 likeCount 는 그대로다")
    void setLiked_falseWhenNotLiked_isNoOp() {
        QuizRoom room = stubRoom(4);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(false);

        QuizRoomLikeResponse response = service.setLiked(USER_ID, ROOM_ID, false);

        assertThat(response).isEqualTo(new QuizRoomLikeResponse(false, 4L));
        verify(likeRepository, never()).deleteByUserAccountIdAndRoomId(any(), any());
        assertThat(room.getLikeCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-74] 없는 방은 QUIZ_ROOM_NOT_FOUND 이고 추천 행을 조회·저장하지 않는다")
    void setLiked_roomNotFound() {
        given(roomRepository.findWithLockById(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.setLiked(USER_ID, 999999L, true)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        verifyNoInteractions(likeRepository, userAccountRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-75] 방 행 락(findWithLockById)으로 읽는다 - 같은 방의 동시 추천을 직렬화하는 유일한 장치이므로 일반 findById 가 아니다")
    void setLiked_usesRowLockedRead() {
        stubRoom(0);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(USER_ID, ROOM_ID)).willReturn(false);

        service.setLiked(USER_ID, ROOM_ID, true);

        verify(roomRepository).findWithLockById(ROOM_ID);
        verify(roomRepository, never()).findById(any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-75] 락을 잡은 순서대로 서로 다른 계정 50명이 추천하면 likeCount 가 정확히 50 이다")
    void setLiked_sequentialDifferentAccounts_countExactlyN() {
        QuizRoom room = stubRoom(0);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(any(), any())).willReturn(false);
        given(userAccountRepository.getReferenceById(any())).willReturn(account(1L, "x", null));

        for (long user = 100; user < 150; user++) {
            service.setLiked(user, ROOM_ID, true);
        }

        assertThat(room.getLikeCount()).isEqualTo(50L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-76, QUIZ-CQR-77] 작성자 본인의 추천도, 풀이 기록이 없는 계정의 추천도 거절하지 않는다 (서비스가 풀이·작성자 여부를 조회하지 않는다)")
    void setLiked_noAuthorOrSolvedRestriction() {
        stubRoom(0);
        given(likeRepository.existsByUserAccount_IdAndRoom_Id(1L, ROOM_ID)).willReturn(false);
        given(userAccountRepository.getReferenceById(1L)).willReturn(author);

        QuizRoomLikeResponse response = service.setLiked(1L, ROOM_ID, true);

        assertThat(response).isEqualTo(new QuizRoomLikeResponse(true, 1L));
    }

    @Test
    @DisplayName("[QUIZ-CQR-75] setLiked 는 쓰기 트랜잭션이다 (방 락과 카운터 갱신이 한 경계)")
    void setLiked_isWriteTransaction() throws Exception {
        Transactional tx = QuizRoomLikeService.class
                .getMethod("setLiked", Long.class, long.class, boolean.class).getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }
}
