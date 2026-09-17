package com.skhynix.user.cleanup.service;

import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.quiz.repository.QuizLikeRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ExpiredAccountView;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 데이터 정리의 <b>트랜잭션 단위</b>를 담는 자리. 회차 전체를 도는 쪽
 * ({@link ExpiredDataCleanupService})은 트랜잭션을 열지 않고, 여기 두 메서드가 각각 하나씩 연다.
 *
 * <p>클래스를 나눈 이유는 배치가 아니라 <b>프록시</b>다. 같은 빈 안에서 부르면 {@code @Transactional}
 * 이 적용되지 않아 "계정 1건 = 트랜잭션 1개"가 조용히 깨진다 — 그러면 한 계정의 실패가 회차 전체를
 * 되돌린다.
 */
@Service
@RequiredArgsConstructor
public class ExpiredAccountEraser {

    private final QuizLikeRepository quizLikeRepository;
    private final ChatroomRepository chatroomRepository;
    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;

    /**
     * 계정 1건의 이관·정리·삭제를 <b>한 트랜잭션</b>으로 처리한다. 중간에 무엇이 실패하든 그 계정은
     * 손대기 전 상태로 되돌아간다 — "소유권만 넘어가고 계정은 남은" 중간 상태가 관측되지 않고,
     * 이관이 실패한 계정은 삭제되지 않는다(fail-closed: 이관 없는 삭제는 공용 데이터 소실이다).
     *
     * <p><b>아래 순서는 계약이다. 바꾸지 말 것.</b>
     * <ol>
     *   <li>취소한 좋아요({@code liked = false}) 삭제 — <b>계정 삭제 전에만</b> 할 수 있다. 뒤로 밀면
     *       소유자가 이미 NULL 이라 어느 행이 누구 것인지 가릴 수 없다</li>
     *   <li>채팅방·채팅 소유권을 더미 계정으로 이관 — {@code chatrooms} 는 FK 가 NO ACTION + NOT NULL
     *       이라 남아 있으면 3단계가 아예 실패한다</li>
     *   <li>{@code users} 행 삭제 — 나머지 자식은 DB 의 CASCADE/SET NULL 이 처리한다</li>
     * </ol>
     *
     * <p>이 계정의 refresh 토큰·BQ·응원·퀴즈 제출을 여기서 지우지 않는 것은 빠뜨린 것이 아니라
     * 3단계의 CASCADE 가 하는 일이다. 애플리케이션이 지우는 행은 {@code users} 하나뿐이다.
     */
    @Transactional
    public AccountEraseResult erase(ExpiredAccountView target, UserAccount unknownAccount) {
        int cancelledLikes = quizLikeRepository.deleteCancelledByUserAccountId(target.accountId());
        int chatrooms = chatroomRepository.reassignOwner(target.accountId(), unknownAccount);
        int chats = chatRepository.reassignSender(target.accountId(), unknownAccount);
        int removed = userRepository.deleteUserById(target.userId());
        return new AccountEraseResult(chatrooms, chats, cancelledLikes, removed > 0);
    }

    /**
     * 만료된 refresh 토큰 행 삭제. 계정 처리와 <b>별개 트랜잭션</b>이라 여기서 실패해도 앞서 삭제된
     * 계정이 되살아나지 않는다.
     *
     * @return 삭제된 행 수
     */
    @Transactional
    public int purgeExpiredTokens(LocalDateTime baseTime) {
        return userRefreshTokenRepository.deleteExpiredTokens(baseTime);
    }
}
