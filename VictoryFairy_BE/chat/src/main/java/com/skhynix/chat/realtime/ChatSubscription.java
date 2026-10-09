package com.skhynix.chat.realtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 구독 하나와 그 구독자 전용 송신 대기열.
 *
 * <p>한 구독자에게 가는 프레임은 대기열을 거쳐 한 번에 한 스레드만 쓴다({@code draining}). 쓰기 풀에서 같은 구독자의
 * 프레임 두 개가 동시에 돌면 순서가 뒤집혀 클라이언트의 "msgId ≤ 마지막 처리값은 버린다" 규칙이 정상 메시지를 버린다.
 *
 * <p>복구 중(Last-Event-ID)에는 실시간 프레임을 {@code held} 에 붙잡아 둔다. 복구 프레임보다 실시간 프레임이 먼저
 * 나가면 클라이언트가 그 뒤의 복구 프레임을 "이미 본 번호"로 버려 놓친 구간이 그대로 사라진다.
 *
 * <p>동일성은 객체 동일성이다(레지스트리 Set 의 원소).
 */
public final class ChatSubscription {

    enum Offer { CLAIMED, QUEUED, DROPPED, OVERFLOW }

    private final String gameId;
    private final Long userAccountId;
    private final ChatSseEmitter emitter;

    private final Object lock = new Object();
    // 아래 넷은 lock 으로 보호한다
    private final ArrayDeque<SseFrame> outbox = new ArrayDeque<>();
    private List<SseFrame> held;
    private boolean draining;
    private boolean closed;

    private final AtomicBoolean dead = new AtomicBoolean();
    private volatile Runnable onDead = () -> { };
    private volatile long writeStartedNanos;

    ChatSubscription(String gameId, Long userAccountId, ChatSseEmitter emitter, boolean recovering) {
        this.gameId = gameId;
        this.userAccountId = userAccountId;
        this.emitter = emitter;
        this.held = recovering ? new ArrayList<>() : null;
    }

    public String gameId() {
        return gameId;
    }

    public Long userAccountId() {
        return userAccountId;
    }

    public ChatSseEmitter emitter() {
        return emitter;
    }

    /**
     * @param realtime 게이트웨이 전달분이면 true — 복구 중엔 held 로 간다. 복구·하트비트 프레임은 false
     * @return CLAIMED 면 호출부가 drain 을 시작해야 한다
     */
    Offer offer(SseFrame frame, boolean realtime, int capacity) {
        synchronized (lock) {
            if (closed) {
                return Offer.DROPPED;
            }
            if (realtime && held != null) {
                if (held.size() >= capacity) {
                    return Offer.OVERFLOW;
                }
                held.add(frame);
                return Offer.QUEUED;
            }
            if (outbox.size() >= capacity) {
                return Offer.OVERFLOW;
            }
            outbox.add(frame);
            if (draining) {
                return Offer.QUEUED;
            }
            draining = true;
            return Offer.CLAIMED;
        }
    }

    /** 복구를 끝내고 붙잡아 둔 실시간 프레임을 대기열 뒤에 잇는다. @return true 면 호출부가 drain 을 시작해야 한다 */
    boolean goLive() {
        synchronized (lock) {
            if (held == null) {
                return false;
            }
            outbox.addAll(held);
            held = null;
            if (closed || draining || outbox.isEmpty()) {
                return false;
            }
            draining = true;
            return true;
        }
    }

    /** 다음 프레임. 없거나 닫혔으면 drain 소유권을 내려놓고 null. */
    SseFrame next() {
        synchronized (lock) {
            if (closed || outbox.isEmpty()) {
                outbox.clear();
                draining = false;
                return null;
            }
            return outbox.poll();
        }
    }

    /** 풀이 작업을 거부했을 때 drain 소유권을 되돌린다. */
    void releaseDrain() {
        synchronized (lock) {
            draining = false;
        }
    }

    void markClosed() {
        synchronized (lock) {
            closed = true;
            outbox.clear();
            held = null;
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    void onDead(Runnable callback) {
        this.onDead = callback;
    }

    /** 쓰기 실패·쓰기 타임아웃·대기열 초과. 콜백(레지스트리 회수)은 한 번만 돈다. */
    void die() {
        if (dead.compareAndSet(false, true)) {
            onDead.run();
        }
    }

    void writeStarted(long nanos) {
        this.writeStartedNanos = nanos;
    }

    void writeFinished() {
        this.writeStartedNanos = 0L;
    }

    long writeStartedNanos() {
        return writeStartedNanos;
    }
}
