package com.skhynix.chat.support;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 서블릿 컨테이너 없이 SseEmitter 에 "쓰려고 시도한 내용"을 읽는다.
 *
 * <p>핸들러가 붙기 전({@code handler == null})의 {@code send()} 는 예외 없이 {@code earlySendAttempts} 에 쌓인다.
 * 그 private 필드를 리플렉션으로 읽어 SSE 와이어 형식({@code event:/id:/data:/주석})으로 되돌린다.
 * quiz 의 SseEmitterRegistryTest 가 같은 기법을 쓴다.
 */
public final class SseCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 이벤트 하나. 주석 프레임이면 {@code comment} 만 채워진다. */
    public record Event(String name, String id, String data, String comment) {

        public boolean isComment() {
            return comment != null;
        }
    }

    private SseCapture() {
    }

    public static List<Event> events(SseEmitter emitter) {
        StringBuilder wire = new StringBuilder();
        for (Object attempt : earlySendAttempts(emitter)) {
            try {
                Object data = attempt.getClass().getMethod("getData").invoke(attempt);
                wire.append(data instanceof CharSequence ? data.toString() : MAPPER.writeValueAsString(data));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        List<Event> events = new ArrayList<>();
        for (String block : wire.toString().split("\n\n")) {
            if (block.isEmpty()) {
                continue;
            }
            String name = null;
            String id = null;
            String data = null;
            String comment = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length());
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length());
                } else if (line.startsWith("data:")) {
                    data = (data == null ? "" : data) + line.substring("data:".length());
                } else if (line.startsWith(":")) {
                    comment = line.substring(1);
                }
            }
            events.add(new Event(name, id, data, comment));
        }
        return events;
    }

    /** 주석(:ping)이 아닌 이벤트만. */
    public static List<Event> dataEvents(SseEmitter emitter) {
        return events(emitter).stream().filter(e -> !e.isComment()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> earlySendAttempts(SseEmitter emitter) {
        try {
            Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
            field.setAccessible(true);
            Set<Object> source = (Set<Object>) field.get(emitter);
            // 쓰기 스레드가 동시에 추가 중일 수 있다(비스레드안전 LinkedHashSet). 복사가 깨지면 다시 시도한다.
            for (int attempt = 0; ; attempt++) {
                try {
                    return new java.util.LinkedHashSet<>(source);
                } catch (java.util.ConcurrentModificationException e) {
                    if (attempt > 50) {
                        throw e;
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
