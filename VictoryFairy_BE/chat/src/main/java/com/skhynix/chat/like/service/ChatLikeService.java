package com.skhynix.chat.like.service;

import com.skhynix.chat.shared.ChatRoles;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

/**
 * 좋아요 판정(CHAT-LK-2). 인증(401)·gameId 형식(400)은 필터·컨트롤러가 먼저 끝낸다. 여기서는
 * 응원 구단 → 속도 제한 → 발행 순서이고, 어느 단계에서 버려도 호출자는 202 를 받는다.
 *
 * <p>방 존재를 보지 않는다(CHAT-LK-49). 좋아요는 경기 단위 신호라, 방 메타 조회·지연 생성을 하면 좋아요 경로가
 * Redis 키를 만들고 Redis 장애 때 503 을 내게 된다.
 *
 * <p>트랜잭션을 걸지 않는다. 구단 조회는 {@code @EntityGraph} 조회라 트랜잭션 없이도 구단 코드를 읽을 수 있다.
 */
@Service
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ChatLikeService {

    private final SupportTeamCodeCache teamCodeCache;
    private final LikeRateLimiter rateLimiter;
    private final LikePublisher publisher;
    private final LikeMetrics metrics;

    public void like(String gameId, Long userAccountId) {
        Optional<String> teamCode;
        try {
            teamCode = teamCodeCache.teamCode(userAccountId);
        } catch (RuntimeException e) {
            metrics.teamLookupFailed();
            log.warn("응원 구단 조회 실패로 좋아요를 버렸다 userAccountId={}: {}", userAccountId, e.toString());
            return;
        }
        if (teamCode.isEmpty()) {
            // 정상 경로(가입 시 응원 구단 필수)에서는 생기지 않는다. SUPPORT_TEAM_REQUIRED 는 내지 않는다(CHAT-GC-13).
            metrics.noTeam();
            log.warn("응원 구단이 없는 계정의 좋아요를 버렸다 userAccountId={}", userAccountId);
            return;
        }
        if (!rateLimiter.tryAcquire(userAccountId)) {
            metrics.rateLimited();
            return;
        }
        publisher.publish(gameId, teamCode.get());
    }
}
