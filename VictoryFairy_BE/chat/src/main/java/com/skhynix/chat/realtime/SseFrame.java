package com.skhynix.chat.realtime;

import com.skhynix.chat.shared.ChatMessageView;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구독자에게 쓸 SSE 프레임 하나. 같은 프레임 객체를 여러 구독자가 공유한다(쓸 때마다 이벤트 빌더를 새로 만든다).
 *
 * <ul>
 *   <li>{@code messages}: 배열, {@code id:} = 배열 마지막 msgId(CHAT-GC-37·87)</li>
 *   <li>{@code deleted}: {@code {"msgId":n}}, {@code id:} 없음 — 삭제는 Last-Event-ID 워터마크를 움직이지 않는다(CHAT-GC-91)</li>
 *   <li>{@code reset}: {@code {}}(CHAT-GC-39·40·42)</li>
 *   <li>하트비트: 주석 {@code :ping}, {@code data:} 아님(CHAT-GC-32)</li>
 *   <li>연결 확인: 주석 {@code :connected}. 구독 직후 첫 프레임으로 응답 헤더를 내보낸다</li>
 * </ul>
 *
 * <p>주석 프레임은 {@code name} 이 null 이고 {@code data} 가 주석 문구다. 주석은 클라이언트 이벤트 핸들러에 잡히지 않는다.
 */
public record SseFrame(String name, String id, Object data) {

    public static final String MESSAGES = "messages";
    public static final String DELETED = "deleted";
    public static final String RESET = "reset";

    public static final SseFrame PING = new SseFrame(null, null, "ping");
    public static final SseFrame CONNECTED = new SseFrame(null, null, "connected");
    private static final SseFrame RESET_FRAME = new SseFrame(RESET, null, Map.of());

    /** @param messages 비어 있지 않은 offset 오름차순 목록. */
    public static SseFrame messages(List<ChatMessageView> messages, long lastMsgId) {
        return new SseFrame(MESSAGES, String.valueOf(lastMsgId), messages);
    }

    public static SseFrame deleted(long msgId) {
        return new SseFrame(DELETED, null, Map.of("msgId", msgId));
    }

    public static SseFrame reset() {
        return RESET_FRAME;
    }

    public boolean isComment() {
        return name == null;
    }

    void writeTo(SseEmitter emitter) throws IOException {
        if (isComment()) {
            emitter.send(SseEmitter.event().comment((String) data));
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(name);
        if (id != null) {
            event.id(id);
        }
        emitter.send(event.data(data, MediaType.APPLICATION_JSON));
    }
}
