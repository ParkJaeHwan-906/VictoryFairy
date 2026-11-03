package com.skhynix.chat.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 닫기를 시도만 해 보는 SseEmitter.
 *
 * <p>⚠ Spring 7 의 {@code ResponseBodyEmitter} 는 {@code send()} 와 {@code complete()} 가 같은 {@code writeLock} 을 잡는다.
 * 느린 클라이언트에게 쓰는 중인 emitter 를 다른 스레드(감시·컨슈머·요청 스레드)가 {@code complete()} 하면 그 쓰기가
 * 끝날 때까지 함께 멈춘다. 그래서 잠금을 바로 얻을 수 있을 때만 닫고, 아니면 호출부가 닫기를 다른 스레드로 넘긴다.
 */
public class ChatSseEmitter extends SseEmitter {

    public ChatSseEmitter(long timeoutMs) {
        super(timeoutMs);
    }

    /**
     * @return 닫았으면 true. 쓰기가 진행 중이라 잠금을 못 얻었으면 false(아무것도 하지 않았다)
     */
    public boolean tryComplete() {
        if (!writeLock.tryLock()) {
            return false;
        }
        try {
            complete();
            return true;
        } finally {
            writeLock.unlock();
        }
    }
}
