package com.skhynix.chat.room.service;

import com.skhynix.chat.room.dto.RoomResponse;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
public class ChatRoomService {

    private final GameRepository gameRepository;
    private final ChatRoomGuard roomGuard;
    private final ChatClock clock;

    /** 오늘 경기 = 오늘 방. Redis 를 보지 않는다(CHAT-GC-26). Redis 장애에도 목록은 200 이다. */
    public List<RoomResponse> getRooms() {
        return gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                        clock.startOfToday(), clock.startOfTomorrow())
                .stream()
                // naver_game_id 가 없는 경기는 방 단위 경로로 들어올 방법이 없어 목록에서도 뺀다.
                .filter(game -> Objects.nonNull(game.getNaverGameId()))
                .map(RoomResponse::from)
                .toList();
    }

    /** 메타로 존재를 확인한 뒤 games 의 현재 값을 돌려준다. 메타는 경기 상태를 들고 있지 않다(CHAT-GC-27). */
    public RoomResponse getRoom(String gameId) {
        Game game = roomGuard.requireOrCreate(gameId, gameRepository::findWithDetailsByNaverGameId)
                .or(() -> gameRepository.findWithDetailsByNaverGameId(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));
        return RoomResponse.from(game);
    }
}
