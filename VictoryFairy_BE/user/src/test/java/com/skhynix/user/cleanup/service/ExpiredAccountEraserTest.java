package com.skhynix.user.cleanup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ExpiredAccountView;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ExpiredAccountEraser} 단위 테스트 — 트랜잭션 경계 자체(H2/Testcontainers 부재로 실제 원자성은
 * 검증 못 함)가 아니라 <b>"어떤 순서로 어떤 리포지토리를 부르는가"</b>라는 계약을 목 상호작용으로 고정한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 *
 * <p>클래스 javadoc이 명시한 순서 계약(취소 추천 삭제 → chatrooms 이관 → chats 이관 → users 삭제)이
 * 뒤집히면 취소 이력이 영구히 사라지는(USER-EDC-47) 실제 사고 지점이라, {@link InOrder}로 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class ExpiredAccountEraserTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final Long USER_ID = 142L;
    private static final String UID = "uid-42";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 18, 3, 0, 0);

    @Mock
    private QuizLikeRepository quizLikeRepository;

    @Mock
    private ChatroomRepository chatroomRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private CommunityPostRepository communityPostRepository;

    @Mock
    private CommunityCommentRepository communityCommentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRefreshTokenRepository userRefreshTokenRepository;

    private ExpiredAccountEraser eraser;

    @BeforeEach
    void setUp() {
        eraser = new ExpiredAccountEraser(quizLikeRepository, chatroomRepository, chatRepository,
                communityPostRepository, communityCommentRepository, userRepository,
                userRefreshTokenRepository);
    }

    private ExpiredAccountView target() {
        return new ExpiredAccountView(ACCOUNT_ID, UID, USER_ID);
    }

    private UserAccount unknownAccount() {
        User user = User.builder().name("UNKNOWN").tel("00000000001")
                .email(UnknownAccountPolicy.EMAIL).gender(Gender.MALE).build();
        return UserAccount.reserved(UnknownAccountPolicy.UID, user, UnknownAccountPolicy.NICKNAME,
                UnknownAccountPolicy.LOCKED_PASSWORD);
    }

    @Test
    @DisplayName("[USER-EDC-47] 취소한 좋아요(liked=false) 삭제가 계정(users) 삭제보다 먼저 실행된다"
            + " — 순서가 뒤집히면 소유자가 이미 NULL이라 취소 이력을 가릴 수 없게 되는 지점")
    void erase_deletesCancelledLikesBeforeDeletingUserAccount() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeRepository.deleteCancelledByUserAccountId(ACCOUNT_ID)).willReturn(3);
        given(chatroomRepository.reassignOwner(eq(ACCOUNT_ID), any())).willReturn(0);
        given(chatRepository.reassignSender(eq(ACCOUNT_ID), any())).willReturn(0);
        given(userRepository.deleteUserById(USER_ID)).willReturn(1);

        // when
        eraser.erase(target(), unknown);

        // then
        InOrder inOrder = inOrder(quizLikeRepository, userRepository);
        inOrder.verify(quizLikeRepository).deleteCancelledByUserAccountId(ACCOUNT_ID);
        inOrder.verify(userRepository).deleteUserById(USER_ID);
    }

    @Test
    @DisplayName("[USER-EDC-36, USER-EDC-50] 전체 순서는 취소 추천 삭제 → chatrooms 이관 → chats 이관 → "
            + "users 삭제이며, 이관 호출 둘 다 대상 계정 id와 더미 계정 인스턴스를 그대로 넘긴다")
    void erase_followsFullContractOrder_andPassesCorrectArgumentsToTransferCalls() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeRepository.deleteCancelledByUserAccountId(ACCOUNT_ID)).willReturn(0);
        given(chatroomRepository.reassignOwner(ACCOUNT_ID, unknown)).willReturn(2);
        given(chatRepository.reassignSender(ACCOUNT_ID, unknown)).willReturn(10);
        given(userRepository.deleteUserById(USER_ID)).willReturn(1);

        // when
        eraser.erase(target(), unknown);

        // then
        InOrder inOrder = inOrder(quizLikeRepository, chatroomRepository, chatRepository, userRepository);
        inOrder.verify(quizLikeRepository).deleteCancelledByUserAccountId(ACCOUNT_ID);
        inOrder.verify(chatroomRepository).reassignOwner(ACCOUNT_ID, unknown);
        inOrder.verify(chatRepository).reassignSender(ACCOUNT_ID, unknown);
        inOrder.verify(userRepository).deleteUserById(USER_ID);
    }

    @Test
    @DisplayName("각 단계의 반환 건수가 AccountEraseResult 필드에 그대로 실린다")
    void erase_assemblesResultFromRepositoryReturnCounts() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeRepository.deleteCancelledByUserAccountId(ACCOUNT_ID)).willReturn(3);
        given(chatroomRepository.reassignOwner(ACCOUNT_ID, unknown)).willReturn(2);
        given(chatRepository.reassignSender(ACCOUNT_ID, unknown)).willReturn(10);
        given(userRepository.deleteUserById(USER_ID)).willReturn(1);

        // when
        AccountEraseResult result = eraser.erase(target(), unknown);

        // then
        assertThat(result.cancelledLikesDeleted()).isEqualTo(3);
        assertThat(result.chatroomsTransferred()).isEqualTo(2);
        assertThat(result.chatsTransferred()).isEqualTo(10);
        assertThat(result.accountRemoved()).isTrue();
    }

    @Test
    @DisplayName("[USER-EDC-25] users 삭제 건수가 0이면(다른 파드가 먼저 지움) accountRemoved가 false다")
    void erase_deleteReturnsZero_accountRemovedIsFalse() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeRepository.deleteCancelledByUserAccountId(ACCOUNT_ID)).willReturn(0);
        given(chatroomRepository.reassignOwner(ACCOUNT_ID, unknown)).willReturn(0);
        given(chatRepository.reassignSender(ACCOUNT_ID, unknown)).willReturn(0);
        given(userRepository.deleteUserById(USER_ID)).willReturn(0);

        // when
        AccountEraseResult result = eraser.erase(target(), unknown);

        // then
        assertThat(result.accountRemoved()).isFalse();
    }

    @Test
    @DisplayName("[USER-EDC-41] 이관(chatrooms) 단계에서 예외가 나면 그 뒤의 users 삭제 호출이 아예 "
            + "일어나지 않는다 — fail-closed(이관 실패 계정은 삭제되지 않는다)의 코드 수준 증거")
    void erase_transferFails_neverReachesUserDeletion() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeRepository.deleteCancelledByUserAccountId(ACCOUNT_ID)).willReturn(0);
        willThrow(new RuntimeException("FK violation")).given(chatroomRepository)
                .reassignOwner(ACCOUNT_ID, unknown);

        // when / then
        assertThatThrownBy(() -> eraser.erase(target(), unknown)).isInstanceOf(RuntimeException.class);
        verify(chatRepository, never()).reassignSender(any(), any());
        verify(userRepository, never()).deleteUserById(any());
    }

    @Test
    @DisplayName("[USER-EDC-51, USER-EDC-52, USER-EDC-54] 게시글 이관 -> 댓글(답글 포함) 이관이 chats 이관 뒤,"
            + " users 삭제 앞에서 대상 계정 id와 더미 계정을 그대로 넘겨 실행된다")
    void erase_reassignsCommunityPostsAndComments_afterChats_beforeUserDeletion() {
        // given
        UserAccount unknown = unknownAccount();
        given(userRepository.deleteUserById(USER_ID)).willReturn(1);

        // when
        eraser.erase(target(), unknown);

        // then
        InOrder inOrder = inOrder(chatRepository, communityPostRepository, communityCommentRepository,
                userRepository);
        inOrder.verify(chatRepository).reassignSender(ACCOUNT_ID, unknown);
        inOrder.verify(communityPostRepository).reassignAuthor(ACCOUNT_ID, unknown);
        inOrder.verify(communityCommentRepository).reassignAuthor(ACCOUNT_ID, unknown);
        inOrder.verify(userRepository).deleteUserById(USER_ID);
    }

    @Test
    @DisplayName("[USER-EDC-55] 게시글·댓글 이관 건수가 결과의 postsTransferred·commentsTransferred에 각각 실린다")
    void erase_carriesCommunityTransferCountsInResult() {
        // given
        UserAccount unknown = unknownAccount();
        given(communityPostRepository.reassignAuthor(ACCOUNT_ID, unknown)).willReturn(3);
        given(communityCommentRepository.reassignAuthor(ACCOUNT_ID, unknown)).willReturn(5);
        given(userRepository.deleteUserById(USER_ID)).willReturn(1);

        // when
        AccountEraseResult result = eraser.erase(target(), unknown);

        // then
        assertThat(result.postsTransferred()).isEqualTo(3);
        assertThat(result.commentsTransferred()).isEqualTo(5);
        assertThat(result.accountRemoved()).isTrue();
    }

    @Test
    @DisplayName("[USER-EDC-54] 게시글 이관이 실패하면 댓글 이관과 users 삭제는 호출되지 않는다(fail-closed)")
    void erase_postReassignFails_neverReachesCommentsOrUserDeletion() {
        // given
        UserAccount unknown = unknownAccount();
        willThrow(new RuntimeException("boom")).given(communityPostRepository)
                .reassignAuthor(ACCOUNT_ID, unknown);

        // when / then
        assertThatThrownBy(() -> eraser.erase(target(), unknown)).isInstanceOf(RuntimeException.class);
        verify(communityCommentRepository, never()).reassignAuthor(any(), any());
        verify(userRepository, never()).deleteUserById(any());
    }

    @Test
    @DisplayName("[USER-EDC-54] 댓글 이관이 실패하면 users 삭제는 호출되지 않는다(fail-closed)")
    void erase_commentReassignFails_neverReachesUserDeletion() {
        // given
        UserAccount unknown = unknownAccount();
        willThrow(new RuntimeException("boom")).given(communityCommentRepository)
                .reassignAuthor(ACCOUNT_ID, unknown);

        // when / then
        assertThatThrownBy(() -> eraser.erase(target(), unknown)).isInstanceOf(RuntimeException.class);
        verify(userRepository, never()).deleteUserById(any());
    }

    @Test
    @DisplayName("[USER-EDC-56, USER-EDC-57, AC-CM-195-1] 이레이저는 순수 DB 작업이다 — 반응·신고 리포지토리, 이미지 행, S3 저장소, Redis 협력자가 없다")
    void eraser_hasNoReactionOrReportRepositoryDependency() {
        // when
        var parameterTypes = java.util.Arrays.stream(ExpiredAccountEraser.class.getDeclaredConstructors()[0]
                .getParameterTypes()).map(Class::getSimpleName).toList();

        // then
        assertThat(parameterTypes).noneMatch(n -> n.contains("Reaction") || n.contains("Report")
                || n.contains("Image") || n.contains("Storage") || n.contains("Redis")
                || n.contains("Template"));
    }

    @Test
    @DisplayName("[USER-EDC-59] 이관 대상 더미 계정의 profileImgUrl은 null이다 — 이관된 글의 author.profileImgUrl이 null이 되는 전제")
    void unknownAccount_hasNullProfileImage() {
        assertThat(unknownAccount().getProfileImgUrl()).isNull();
        assertThat(unknownAccount().getNickname()).isEqualTo(UnknownAccountPolicy.NICKNAME);
    }

    @Test
    @DisplayName("[USER-EDC-19, USER-EDC-20] purgeExpiredTokens는 기준 시각을 그대로 넘겨 만료 토큰 삭제를 "
            + "위임하고 삭제된 행 수를 반환한다")
    void purgeExpiredTokens_delegatesBaseTime_andReturnsDeletedCount() {
        // given
        given(userRefreshTokenRepository.deleteExpiredTokens(BASE_TIME)).willReturn(108);

        // when
        int deleted = eraser.purgeExpiredTokens(BASE_TIME);

        // then
        assertThat(deleted).isEqualTo(108);
        verify(userRefreshTokenRepository).deleteExpiredTokens(BASE_TIME);
    }
}
