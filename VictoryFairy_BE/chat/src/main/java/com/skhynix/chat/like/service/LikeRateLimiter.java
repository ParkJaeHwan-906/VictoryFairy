package com.skhynix.chat.like.service;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.shared.ChatRoles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좋아요 사용자당 1초 고정 창 속도 제한(CHAT-LK-16~18). 파드 메모리에서 세며 Redis 명령을 쓰지 않는다.
 * 창은 그 사용자의 첫 좋아요 시점부터 1초이고 경기 불문 공유한다. 메시지 전송 속도 제한({@code SendRateLimiter})과는 별개다.
 *
 * <p>파드 단위라 파드 N개에 요청이 흩어지면 사용자당 실효 상한은 최대 N배다(요구사항 "알려진 결과 4").
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
public class LikeRateLimiter {

    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);

    private record Window(long startNanos, int count) {
    }

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();
    private final int perSecond;

    public LikeRateLimiter(ChatLikesProperties properties) {
        this.perSecond = properties.rateLimit().perSecond();
    }

    /** @return false 면 이번 창의 한도를 넘었다 */
    public boolean tryAcquire(Long userAccountId) {
        long now = System.nanoTime();
        boolean[] allowed = new boolean[1];
        windows.compute(userAccountId, (key, window) -> {
            if (window == null || now - window.startNanos() >= WINDOW_NANOS) {
                allowed[0] = perSecond > 0;
                return new Window(now, 1);
            }
            if (window.count() < perSecond) {
                allowed[0] = true;
                return new Window(window.startNanos(), window.count() + 1);
            }
            return window;
        });
        return allowed[0];
    }

    /** 끝난 창을 걷는다. 판정 정확성과 무관하다 — 끝난 창은 다음 요청이 어차피 새로 연다. */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void evictExpired() {
        long now = System.nanoTime();
        windows.values().removeIf(window -> now - window.startNanos() >= WINDOW_NANOS);
    }
}
