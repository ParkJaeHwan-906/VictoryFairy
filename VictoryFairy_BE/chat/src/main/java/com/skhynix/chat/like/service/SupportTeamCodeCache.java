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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 사용자별 응원 구단 코드의 파드 메모리 캐시. Redis 를 쓰지 않는다.
 *
 * <p>출처는 메시지 전송({@code ChatMessageSendService})과 같은 조회다 — 어긋나면 같은 사용자의 {@code messages.teamCode}
 * 와 {@code likes} 코드가 달라진다. {@code @EntityGraph} 조회라 트랜잭션 밖에서도 LAZY 초기화가 일어나지 않는다.
 *
 * <p>구단 변경은 {@link SupportTeamChangedSubscriber} 가 {@link #invalidate}·{@link #invalidateAll} 로 반영한다. TTL 은
 * 그 신호가 유실됐을 때의 안전망이다. TTL 은 첫 조회 시점부터 센다(접근으로 연장하지 않는다) — 연장하면 신호를 놓친
 * 사용자가 계속 누르는 동안 옛 구단이 영영 남는다. 빈 결과는 캐시하지 않는다 — 첫 구단 설정은 변경 신호가 없어서
 * 이것만이 즉시 반영을 보장한다.
 *
 * <p>같은 사용자의 캐시 미스가 동시에 나면 SELECT 가 겹칠 수 있다. 조회를 {@code computeIfAbsent} 안에 넣으면
 * 맵 빈(bin) 잠금을 쥔 채 DB I/O 를 하게 되어 그러지 않았다.
 *
 * <p><b>세대 번호</b>: 조회가 잠금 밖이라 "조회가 커밋 전 옛 값을 읽음 → 무효화 → 옛 값 적재" 가 가능하다. 그래서 무효화마다
 * 전역 카운터 {@link #generation} 을 올리고, 조회는 시작 시점의 값을 기억했다가 그 뒤에 자기 사용자(단건) 또는 전체
 * 무효화가 있었으면 적재하지 않는다. 단건 세대를 사용자별 카운터가 아니라 전역 카운터의 스냅숏으로 두는 이유: 전체
 * 비우기를 "모든 사용자의 세대가 바뀐 것" 으로 보이게 하려면 사용자별 값과 비교 가능한 하나의 수열이어야 한다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
public class SupportTeamCodeCache {

    /** @param generation 이 값을 읽어 온 조회가 시작될 때의 {@link #generation} */
    private record Entry(String teamCode, long expiresAtNanos, long generation) {
    }

    /** @param generation 무효화가 올린 {@link #generation} 값 */
    private record Invalidation(long generation, long atNanos) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /**
     * 사용자별 마지막 단건 무효화. 그 무효화 전에 시작된 조회가 끝날 때까지만 필요하므로 {@link #evictExpired} 가 TTL 이
     * 지난 것을 걷는다 — 그보다 오래 걸린 조회가 적재하는 항목은 만료 시각(조회 시작 + TTL)이 이미 지나 있어 쓰이지 않는다.
     */
    private final Map<Long, Invalidation> invalidations = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    /** 마지막 전체 비우기가 올린 {@link #generation} 값. 이보다 작은 세대의 항목·조회는 무효다. */
    private final AtomicLong clearedGeneration = new AtomicLong();
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
        // 순서 주의: 시각을 세대보다 먼저 잡아야 invalidations 를 TTL 기준으로 걷어도 안전하다(필드 주석 참고).
        long now = System.nanoTime();
        long startGeneration = generation.get();
        Entry cached = entries.get(userAccountId);
        if (cached != null && now - cached.expiresAtNanos() < 0 && cached.generation() >= clearedGeneration.get()) {
            return Optional.of(cached.teamCode());
        }
        Optional<String> loaded = userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(UserSupportTeam::getTeam)
                .map(Team::getCode)
                .filter(code -> !code.isBlank());
        if (loaded.isPresent()) {
            Entry fresh = new Entry(loaded.get(), now + ttlNanos, startGeneration);
            // 무효화 기록 확인과 적재를 같은 키 잠금 안에서 한다. invalidate 는 기록을 남긴 뒤 같은 키를 remove 하므로,
            // 이 compute 가 기록을 못 봤다면 그 remove 가 뒤에서 지운다.
            entries.compute(userAccountId, (id, current) -> {
                if (isInvalidatedSince(id, startGeneration)) {
                    return current;
                }
                return current == null || current.generation() <= startGeneration ? fresh : current;
            });
        } else if (cached != null) {
            entries.remove(userAccountId, cached);
        }
        // 이 요청은 무효화 여부와 무관하게 읽은 값으로 발행한다. 막는 것은 캐시에 남는 것뿐이다.
        return loaded;
    }

    private boolean isInvalidatedSince(Long userAccountId, long startGeneration) {
        if (clearedGeneration.get() > startGeneration) {
            return true;
        }
        Invalidation last = invalidations.get(userAccountId);
        return last != null && last.generation() > startGeneration;
    }

    /** 그 사용자 항목을 지우고, 진행 중인 그 사용자의 조회가 끝나도 적재하지 않게 한다. 캐시에 없는 id 면 사실상 무동작. */
    public void invalidate(long userAccountId) {
        Invalidation mark = new Invalidation(generation.incrementAndGet(), System.nanoTime());
        invalidations.merge(userAccountId, mark, (old, neu) -> old.generation() >= neu.generation() ? old : neu);
        entries.remove(userAccountId);
    }

    /**
     * 전부 비우고, 진행 중인 모든 조회의 적재를 막는다. 조회 경로가 항목 세대를 {@link #clearedGeneration} 과 비교하므로
     * {@code clear()} 가 동시 적재와 엇갈려 항목이 남더라도 쓰이지 않는다.
     */
    public void invalidateAll() {
        long mark = generation.incrementAndGet();
        clearedGeneration.accumulateAndGet(mark, Math::max);
        entries.clear();
    }

    /**
     * 다시 오지 않는 사용자의 만료 항목과 더는 필요 없는 무효화 기록이 쌓이지 않게 걷는다. 조회 정확성은
     * {@link #teamCode} 의 검사가 맡는다.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void evictExpired() {
        long now = System.nanoTime();
        long cleared = clearedGeneration.get();
        entries.values().removeIf(entry -> now - entry.expiresAtNanos() >= 0 || entry.generation() < cleared);
        invalidations.values().removeIf(mark -> mark.generation() <= cleared || now - mark.atNanos() >= ttlNanos);
    }
}
