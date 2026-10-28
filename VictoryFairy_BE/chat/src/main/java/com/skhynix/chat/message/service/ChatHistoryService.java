package com.skhynix.chat.message.service;

import com.skhynix.chat.message.dto.HistoryResponse;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserBlockRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 히스토리(CHAT-GC-68~76). history-writer 가 적재한 Stream 만 읽는다. 202 직후의 메시지는 아직 없을 수 있다.
 */
@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {

    static final int PAGE_SIZE = 30;

    private final ChatRoomGuard roomGuard;
    private final ChatStreamReader streamReader;
    private final UserBlockRepository userBlockRepository;

    public HistoryResponse getHistory(String gameId, Long cursor, Long userAccountId) {
        // 히스토리는 방 메타를 만들지 않는다(CHAT-GC-107).
        roomGuard.requireExisting(gameId);

        List<ChatStreamEntry> page;
        Set<Long> blinded;
        try {
            page = streamReader.latest(gameId, cursor, PAGE_SIZE);
            blinded = streamReader.blinded(gameId, page.stream().map(ChatStreamEntry::msgId).toList());
        } catch (DataAccessException e) {
            // 빈 배열 200 으로 덮지 않는다. 빈 방과 장애가 같은 모양이 된다(CHAT-GC-75).
            log.warn("히스토리 조회 실패 gameId={}", gameId, e);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        }
        if (page.isEmpty()) {
            return new HistoryResponse(List.of(), null, false);
        }

        // 차단 관계(양방향)는 조회 시점에만 숨긴다. 페이지당 1회 조회(CHAT-GC-72).
        Set<Long> related = userBlockRepository.findRelatedAccountIds(userAccountId);
        List<ChatMessageView> messages = page.stream()
                .filter(entry -> !blinded.contains(entry.msgId()))
                .filter(entry -> entry.senderId() == null || !related.contains(entry.senderId()))
                .map(ChatStreamEntry::toView)
                .toList();

        // 커서·hasNext 는 필터 이전 원본 기준(CHAT-GC-70).
        long nextCursor = page.get(page.size() - 1).msgId();
        return new HistoryResponse(messages, nextCursor, page.size() == PAGE_SIZE);
    }
}
