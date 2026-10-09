package com.skhynix.chat.like.service;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사용자별 응원 구단 코드의 파드 메모리 캐시(CHAT-LK-10~15). Redis 를 쓰지 않는다.
 *
 * <p>출처는 메시지 전송({@code ChatMessageSendService})과 같은 조회다 — 어긋나면 같은 사용자의 {@code messages.teamCode}
 * 와 {@code likes} 코드가 달라진다. {@code @EntityGraph} 조회라 트랜잭션 밖에서도 LAZY 초기화가 일어나지 않는다.
 *
 * <p>TTL 은 첫 조회 시점부터 센다(접근으로 연장하지 않는다) — 연장하면 계속 누르는 사용자의 구단 변경이 영영 반영되지
 * 않는다. 빈 결과는 캐시하지 않는다(CHAT-LK-14).
 *
 * <p>같은 사용자의 캐시 미스가 동시에 나면 SELECT 가 겹칠 수 있다. 조회를 {@code computeIfAbsent} 안에 넣으면
 * 맵 빈(bin) 잠금을 쥔 채 DB I/O 를 하게 되어 그러지 않았다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
public class SupportTeamCodeCache {

    private record Entry(String teamCode, long expiresAtNanos) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final long ttlNanos;

    public SupportTeamCodeCache(UserSupportTeamRepository userSupportTeamRepository, ChatLikesProperties properties) {
        this.userSupportTeamRepository = userSupportTeamRepository;
        this.ttlNanos = TimeUnit.SECONDS.toNanos(Math.max(0L, properties.teamCacheTtlSeconds()));
    }

    /**
     * @return 응원 구단이 없으면 empty
     * @throws RuntimeException DB 조회 실패. 버릴지는 호출부가 정한다
     */
    public Optional<String> teamCode(Long userAccountId) {
        long now = System.nanoTime();
        Entry cached = entries.get(userAccountId);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return Optional.of(cached.teamCode());
        }
        Optional<String> loaded = userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(UserSupportTeam::getTeam)
                .map(Team::getCode)
                .filter(code -> !code.isBlank());
        if (loaded.isPresent()) {
            entries.put(userAccountId, new Entry(loaded.get(), now + ttlNanos));
        } else if (cached != null) {
            entries.remove(userAccountId, cached);
        }
        return loaded;
    }

    /** 다시 오지 않는 사용자의 만료 항목이 쌓이지 않게 걷는다. 조회 정확성은 {@link #teamCode} 의 만료 검사가 맡는다. */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void evictExpired() {
        long now = System.nanoTime();
        entries.values().removeIf(entry -> now - entry.expiresAtNanos() >= 0);
    }
}
