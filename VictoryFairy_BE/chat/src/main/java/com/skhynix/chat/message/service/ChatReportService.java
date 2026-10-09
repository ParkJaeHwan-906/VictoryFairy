package com.skhynix.chat.message.service;

import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 신고(CHAT-GC-77~85). 판정 순서: 404(방 메타, 지연 생성 없음) → 404(Stream 엔트리) → 403(본인) → 툼스톤 발행.
 * 신고자·사유·횟수는 어디에도 저장하지 않는다(CHAT-GC-83). 이미 blind 된 메시지도 다시 발행한다(멱등, CHAT-GC-81).
 */
@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatReportService {

    private final ChatRoomGuard roomGuard;
    private final ChatStreamReader streamReader;
    private final ChatKafkaProducer producer;

    public void report(String gameId, long msgId, Long reporterId) {
        roomGuard.requireExisting(gameId);

        ChatStreamEntry entry;
        try {
            entry = streamReader.find(gameId, msgId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        } catch (DataAccessException e) {
            log.warn("신고 대상 조회 실패 gameId={} msgId={}", gameId, msgId, e);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        }
        if (Objects.equals(entry.senderId(), reporterId)) {
            throw new BusinessException(ErrorCode.SELF_REPORT_NOT_ALLOWED);
        }

        try {
            producer.sendControl(BlindTombstone.of(gameId, msgId));
        } catch (ChatPublishException e) {
            // 200 으로 삼키면 "신고됐다"가 거짓이 된다(CHAT-GC-82).
            log.warn("blind 툼스톤 발행 실패 gameId={} msgId={}", gameId, msgId, e);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        }
    }
}
