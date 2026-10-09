package com.skhynix.chat.subscription.service;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구독·복구(CHAT-GC-30~44)와 퇴장(CHAT-GC-45~49).
 *
 * <p>구독 순서: 방 확인(404면 등록 없음) → 레지스트리 등록(로컬 last-one-wins 축출) → {@code :connected} 주석
 * (응답 헤더를 바로 내보낸다) → Last-Event-ID 복구 →
 * 실시간 합류 → 다른 파드 축출 명령 발행. 복구 중 도착한 실시간 프레임은 구독 대기열이 붙잡아 두었다가 복구 뒤에
 * 흘린다 — 등록을 복구보다 먼저 해야 복구를 읽는 사이의 메시지를 놓치지 않고, 붙잡아 두어야 실시간 프레임이 복구
 * 프레임을 앞지르지 않는다. 겹치는 구간의 중복 전달은 계약상 허용이다(CHAT-GC-43).
 */
@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatSubscriptionService {

    // 첫 의존성으로 둔다. API 를 켜고 게이트웨이를 끈 조합이면 레지스트리 빈이 없어 "빈 없음"으로 먼저 죽는데,
    // 이 바인딩이 먼저 일어나야 기동 실패 사유가 역할 조합 위반(CHAT-GC-6)으로 찍힌다.
    private final ChatProperties chatProperties;
    private final ChatRoomGuard roomGuard;
    private final SseEmitterRegistry registry;
    private final SseFrameWriter writer;
    private final ChatStreamReader streamReader;
    private final ChatKafkaProducer producer;

    /**
     * @param lastEventId {@code Last-Event-ID} 헤더 원문. 0 이상의 정수가 아니면 무시한다(CHAT-GC-41)
     */
    public SseEmitter subscribe(String gameId, Long userAccountId, String lastEventId) {
        roomGuard.requireOrCreate(gameId);

        Long lastMsgId = parseLastEventId(lastEventId);
        ChatSubscription subscription = registry.register(gameId, userAccountId, lastMsgId != null);
        writer.open(subscription);
        if (lastMsgId != null) {
            try {
                recover(subscription, gameId, userAccountId, lastMsgId);
            } catch (RuntimeException e) {
                // Redis 실패 포함. 스트림은 유지하고 reset 후 실시간에 합류한다(CHAT-GC-42).
                log.warn("Last-Event-ID 복구 실패 gameId={} lastEventId={}", gameId, lastMsgId, e);
                writer.enqueue(subscription, SseFrame.reset());
            } finally {
                writer.goLive(subscription);
            }
        }

        publishClose(SubscriptionCloseCommand.evict(userAccountId, registry.instanceId()));
        return subscription.emitter();
    }

    /** 방 존재를 확인하지 않는다(CHAT-GC-46). 구독이 없어도 200 이다(CHAT-GC-47). */
    public void unsubscribe(String gameId, Long userAccountId) {
        registry.closeSubscriptions(gameId, userAccountId);
        publishClose(SubscriptionCloseCommand.leave(userAccountId, registry.instanceId(), gameId));
    }

    // 실패해도 요청은 성공이다 — 다른 파드의 구독은 타임아웃·하트비트 안전망으로 회수된다(CHAT-GC-48).
    private void publishClose(SubscriptionCloseCommand command) {
        try {
            producer.sendControl(command);
        } catch (ChatPublishException e) {
            log.warn("구독 종료 명령 발행 실패 targetUserAccountId={} allRooms={} gameId={}",
                    command.targetUserAccountId(), command.allRooms(), command.gameId(), e);
        }
    }

    /**
     * CHAT-GC-37~40. 페이지(최대 batch-size 건) 하나가 {@code messages} 이벤트 하나이고 {@code id:} 는 그 페이지의
     * 마지막 offset 이다. blind·본인 메시지를 걸러 비면 그 페이지는 이벤트를 보내지 않는다.
     */
    private void recover(ChatSubscription subscription, String gameId, Long userAccountId, long lastMsgId) {
        int batchSize = chatProperties.recovery().batchSize();
        int maxBatches = chatProperties.recovery().maxBatches();

        Optional<ChatStreamEntry> oldest = streamReader.oldest(gameId);
        if (oldest.isEmpty()) {
            return; // 비어 있는 Stream — 놓친 것이 없다
        }
        if (lastMsgId < oldest.get().msgId()) {
            // 트리밍·재생성으로 그 지점이 Stream 에 없다. 조용한 공백 대신 reset(CHAT-GC-40)
            writer.enqueue(subscription, SseFrame.reset());
            return;
        }

        long cursor = lastMsgId;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<ChatStreamEntry> page = streamReader.after(gameId, cursor, batchSize);
            if (page.isEmpty()) {
                return;
            }
            cursor = page.getLast().msgId();
            sendPage(subscription, gameId, userAccountId, page, cursor);
            if (page.size() < batchSize) {
                return;
            }
        }
        // max-batches 페이지를 전부 꽉 채웠다. 더 남아 있으면 reset 하고 실시간에 합류한다(CHAT-GC-39).
        if (!streamReader.after(gameId, cursor, 1).isEmpty()) {
            writer.enqueue(subscription, SseFrame.reset());
        }
    }

    private void sendPage(ChatSubscription subscription, String gameId, Long userAccountId,
            List<ChatStreamEntry> page, long pageLastMsgId) {
        Set<Long> blinded = streamReader.blinded(gameId, page.stream().map(ChatStreamEntry::msgId).toList());
        List<ChatMessageView> views = new ArrayList<>(page.size());
        for (ChatStreamEntry entry : page) {
            // CHAT-GC-38. 실시간에는 blind 필터가 없고(사후 deleted 로 알린다) 복구에만 있다.
            if (blinded.contains(entry.msgId()) || Objects.equals(entry.senderId(), userAccountId)) {
                continue;
            }
            views.add(entry.toView());
        }
        if (!views.isEmpty()) {
            writer.enqueue(subscription, SseFrame.messages(views, pageLastMsgId));
        }
    }

    static Long parseLastEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
