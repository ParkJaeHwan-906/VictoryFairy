package com.skhynix.quiz.quizroom.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomLike;
import com.skhynix.domain.quizroom.repository.QuizRoomLikeRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quizroom.dto.QuizRoomLikeResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 상태 지정(멱등). 토글이 아니다 — 같은 값을 다시 보내면 아무것도 바뀌지 않는다.
 *
 * <p>방 행 락이 같은 방의 추천 변경을 줄 세운다: 카운터를 엔티티 값에서 더하므로 락이 없으면 서로 다른 계정의 동시 추천 일부가
 * 유실되고, 같은 계정의 동시 2건이 둘 다 "행 없음"을 봐 카운터가 2 오른다. 풀이 여부·작성자 여부는 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class QuizRoomLikeService {

    private final QuizRoomRepository roomRepository;
    private final QuizRoomLikeRepository likeRepository;
    private final UserAccountRepository userAccountRepository;
    private final Clock kstClock;

    @Transactional
    public QuizRoomLikeResponse setLiked(Long userAccountId, long roomId, boolean liked) {
        QuizRoom room = roomRepository.findWithLockById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        boolean current = likeRepository.existsByUserAccount_IdAndRoom_Id(userAccountId, roomId);
        if (liked && !current) {
            likeRepository.save(QuizRoomLike.builder()
                    .userAccount(userAccountRepository.getReferenceById(userAccountId))
                    .room(room)
                    .createdAt(LocalDateTime.now(kstClock))
                    .build());
            room.increaseLikeCount();
        } else if (!liked && current) {
            likeRepository.deleteByUserAccountIdAndRoomId(userAccountId, roomId);
            room.decreaseLikeCount();
        }
        return new QuizRoomLikeResponse(liked, room.getLikeCount());
    }
}
