package com.skhynix.chat.message.service;

import com.skhynix.chat.global.validation.RequestBodyValidator;
import com.skhynix.chat.message.dto.SendMessageRequest;
import com.skhynix.chat.message.dto.SendMessageResponse;
import com.skhynix.chat.message.service.MessageDedupStore.Claim;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.profanity.ProfanityDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 전송(CHAT-GC-50~67). 판정 순서가 계약이다:
 * 404(방, 지연 생성 포함) → 400(본문) → 마스킹 → 429(속도) → dedup 선점(409/재반환) → produce(503) → dedup 확정 → 202.
 *
 * <p>트랜잭션을 걸지 않는다. Kafka ack 를 기다리는 동안 DB 커넥션을 잡지 않기 위해서이고, 읽는 연관(응원 구단)은
 * {@code @EntityGraph} 조회라 트랜잭션 밖에서도 LAZY 초기화가 없다.
 */
@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatMessageSendService {

    private static final MethodParameter REQUEST_PARAMETER = RequestBodyValidator.parameterOf(
            ChatMessageSendService.class, "send", 2, String.class, Long.class, SendMessageRequest.class);

    private final ChatRoomGuard roomGuard;
    private final RequestBodyValidator requestValidator;
    private final ProfanityDetector profanityDetector;
    private final SendRateLimiter rateLimiter;
    private final MessageDedupStore dedupStore;
    private final ChatKafkaProducer producer;
    private final UserAccountRepository userAccountRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final ChatClock clock;

    public SendMessageResponse send(String gameId, Long senderId, SendMessageRequest request)
            throws MethodArgumentNotValidException {
        roomGuard.requireOrCreate(gameId);
        requestValidator.validate(request, REQUEST_PARAMETER);

        // 실패는 삼키지 않는다(500). 원문을 내보내는 fallback 은 필터가 꺼진 줄 모르고 욕설이 퍼지는 결과를 만든다.
        String masked = profanityDetector.maskWithAsterisks(request.content());

        // dedup 재반환·409 요청도 계수한다(CHAT-GC-61). 그래서 dedup 보다 먼저다.
        rateLimiter.check(senderId);

        Claim claim = dedupStore.claim(gameId, request.clientMsgId());
        if (claim instanceof Claim.InFlight) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_IN_FLIGHT);
        }
        if (claim instanceof Claim.Replay replay) {
            return new SendMessageResponse(gameId, replay.value().msgId(), replay.value().content());
        }
        String dedupKey = claim instanceof Claim.Acquired acquired ? acquired.key() : null;

        long msgId;
        try {
            msgId = producer.sendMessage(snapshot(gameId, senderId, masked));
        } catch (ChatPublishException e) {
            log.warn("채팅 메시지 발행 실패 gameId={}", gameId, e);
            releaseQuietly(dedupKey);
            throw new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE);
        } catch (RuntimeException e) {
            // produce 되지 않은 메시지의 선점을 남기지 않는다(발신자 조회 실패 등).
            releaseQuietly(dedupKey);
            throw e;
        }

        if (dedupKey != null) {
            dedupStore.confirm(dedupKey, msgId, masked);
        }
        return new SendMessageResponse(gameId, msgId, masked);
    }

    // 발신자 표시 정보는 전송 시점 스냅샷이다(CHAT-GC-64). 조회 경로에 계정 조인이 없다.
    private ChatMessagePayload snapshot(String gameId, Long senderId, String maskedContent) {
        UserAccount sender = userAccountRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        String teamCode = userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(senderId)
                .map(UserSupportTeam::getTeam)
                .map(team -> team.getCode())
                .orElse(null);
        return new ChatMessagePayload(gameId, senderId, sender.getNickname(), teamCode,
                sender.getProfileImgUrl(), maskedContent, clock.sentAtNow());
    }

    private void releaseQuietly(String dedupKey) {
        if (dedupKey != null) {
            dedupStore.release(dedupKey);
        }
    }
}
