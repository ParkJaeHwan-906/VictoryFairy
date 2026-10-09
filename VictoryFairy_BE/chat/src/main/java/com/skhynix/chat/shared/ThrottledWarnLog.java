package com.skhynix.chat.shared;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * 같은 사유의 WARN 을 간격당 최대 1줄로 줄인다. 줄마다 직전 줄 이후 쌓인 건수를 싣는다(CHAT-LK-52).
 * 장애 중에는 같은 사유가 초당 수천 번 나므로 건마다 남기면 로그가 장애를 덮는다. 정확한 건수는 메트릭이 맡는다.
 *
 * <p>잠금 없이 동작한다. 경계에서 한두 건이 다음 줄로 넘어갈 수 있지만 줄 수 상한은 지켜진다.
 */
public final class ThrottledWarnLog {

    private final Logger log;
    private final String message;
    private final long intervalNanos;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong nextAllowedNanos;

    /** @param message 로그 문구. 끝에 "(직전 로그 이후 N건)" 이 붙는다 */
    public ThrottledWarnLog(Logger log, String message, long interval, TimeUnit unit) {
        this.log = log;
        this.message = message;
        this.intervalNanos = unit.toNanos(interval);
        this.nextAllowedNanos = new AtomicLong(System.nanoTime());
    }

    /** @param cause null 이면 스택 없이 남긴다 */
    public void record(Throwable cause) {
        pending.incrementAndGet();
        long now = System.nanoTime();
        long next = nextAllowedNanos.get();
        if (now - next < 0 || !nextAllowedNanos.compareAndSet(next, now + intervalNanos)) {
            return;
        }
        long count = pending.getAndSet(0);
        if (cause == null) {
            log.warn("{} (직전 로그 이후 {}건)", message, count);
        } else {
            log.warn("{} (직전 로그 이후 {}건): {}", message, count, cause.toString());
        }
    }
}
