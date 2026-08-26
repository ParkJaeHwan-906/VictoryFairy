package com.skhynix.quiz.quiz.vote;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 보기별 투표 수를 문제당 Redis Hash 한 키(<code>quiz:votes:{quizId}</code>)에 쌓고, 서빙 시점 분포를
 * 그 키에서 읽어 준다.
 *
 * <p><b>프로파일 분기가 없다(의도).</b> 같은 앱의 실시간 fan-out 은 기동 시 구독 연결을 여는 리스너
 * 컨테이너 때문에 {@code @Profile("prod")} 지만, 집계는 dev·prod 모두 실제 Redis 에 쓴다 — 이 저장소의
 * 최종 검증이 항상 dev 프로파일 docker 기동이라, prod 전용으로 만들면 표가 실제로 쌓이는 것을 한 번도
 * 못 본 채 배포된다. 아래 예외 삼킴이 그 대가를 치러 준다(Redis 없이 맨몸 기동해도 no-op 으로 degrade).
 *
 * <p><b>왕복 수는 문제 수·보기 수와 무관한 상수다.</b> 적재·초기화 경로는 {@code executePipelined} 안에서
 * 명령을 쌓고 한 번에 흘려보낸다 — 명령 수는 필드 수만큼 늘어도 응답을 기다리는 횟수는 1 이다.
 * 문제마다·보기마다 응답을 받는 형태로 "단순화"하면 20문제 × 4보기 = 80왕복이 된다.
 *
 * <p><b>Redis 7.0 이상을 전제한다</b> — TTL 을 "없을 때만 설정"(연장 금지)으로 만드는 수단이
 * {@code EXPIRE key seconds NX} 이기 때문이다. 6.x 이하로 내리면 이 조건이 무시되는 게 아니라
 * 명령 자체가 에러가 나고, 그 에러는 아래에서 삼켜져 <b>TTL 없는 영구 키</b>로 남는다.
 */
@Component
@Slf4j
public class RedisQuizVoteTally implements QuizVoteTally {

    /**
     * 같은 Redis 를 쓰는 다른 용도(실시간 fan-out 채널 {@code realtime:events})와 콜론 네임스페이스
     * 규약을 공유한다 — {@code SCAN MATCH quiz:votes:*} 하나로 갈라진다.
     */
    private static final String KEY_PREFIX = "quiz:votes:";

    private static final byte[] ZERO = "0".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public RedisQuizVoteTally(StringRedisTemplate redisTemplate,
            @Value("${quiz.vote.ttl:12h}") Duration ttl) {
        // 0 이하면 EXPIRE 가 키를 즉시 지운다 — 조용히 표를 날리느니 기동을 못 하게 막는다.
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("quiz.vote.ttl 은 양수여야 합니다: " + ttl);
        }
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttl.toSeconds();
    }

    @Override
    public void increment(long quizId, int optionNo) {
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] key = key(quizId);
                connection.hashCommands().hIncrBy(key, field(optionNo), 1L);
                // /today 때 Redis 가 죽어 있었다면 이 HINCRBY 가 키를 처음 만든다 — 증가 경로에도
                // 만료를 걸지 않으면 그때 영구 키가 남는다.
                applyTtlIfAbsent(connection, key);
                return null;
            });
        } catch (Exception e) {
            // 이 표는 여기서 영구 유실된다(재시도·보정 없음). 정본은 quiz_users_submit 이고,
            // 필요하면 거기서 재구성한다 — 그 배치는 이번 범위에 없다.
            log.warn("퀴즈 투표 집계 증가 실패 quizId={} optionNo={}", quizId, optionNo, e);
        }
    }

    @Override
    public Map<Long, Map<Integer, Long>> initializeAndRead(
            Map<Long, List<Integer>> optionNosByQuizId) {
        Map<Long, List<Integer>> targets = normalize(optionNosByQuizId);
        if (targets.isEmpty()) {
            return Map.of();
        }
        try {
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                targets.forEach((quizId, optionNos) -> {
                    byte[] key = key(quizId);
                    for (Integer optionNo : optionNos) {
                        // HSETNX 여야 한다 — 조회 후 분기하거나 HSET 으로 덮으면 이미 쌓인 표가
                        // 뒤늦은 /today 호출 한 번에 0 으로 밀린다.
                        connection.hashCommands().hSetNX(key, field(optionNo), ZERO);
                    }
                    applyTtlIfAbsent(connection, key);
                    // 한 파이프라인 안에서 HSETNX -> EXPIRE NX -> HGETALL 순으로 흘려보낸다. Redis 는
                    // 받은 순서대로 실행하므로 "초기화가 끝난 뒤 읽는다"가 구조적으로 보장되고,
                    // 왕복은 문제 수·보기 수와 무관하게 1 회로 유지된다.
                    connection.hashCommands().hGetAll(key);
                });
                return null;
            });
            return collect(targets.keySet(), results);
        } catch (Exception e) {
            // 읽기 실패는 서빙을 막지 않는다 — 호출부가 빈 결과를 전 보기 0 으로 채운다.
            // 로그는 문제마다가 아니라 요청당 1 건이다(호출이 요청당 1 회라 이 catch 도 1 회).
            log.warn("퀴즈 투표 집계 초기화·조회 실패 quizIds={}", targets.keySet(), e);
            return Map.of();
        }
    }

    @Override
    public Map<Integer, Long> read(long quizId) {
        try {
            String key = KEY_PREFIX + quizId;
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
            return parseCounts(hash);
        } catch (Exception e) {
            log.warn("퀴즈 투표 집계 조회 실패 quizId={}", quizId, e);
            return Map.of();
        }
    }

    /** null·빈 항목을 걸러 낸다. 순서를 고정해야 아래 파이프라인 결과와 문제를 짝지을 수 있다. */
    private static Map<Long, List<Integer>> normalize(Map<Long, List<Integer>> optionNosByQuizId) {
        if (optionNosByQuizId == null || optionNosByQuizId.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Integer>> targets = new LinkedHashMap<>();
        optionNosByQuizId.forEach((quizId, optionNos) -> {
            if (quizId == null || optionNos == null) {
                return;
            }
            List<Integer> valid = optionNos.stream().filter(Objects::nonNull).toList();
            if (!valid.isEmpty()) {
                targets.put(quizId, valid);
            }
        });
        return targets;
    }

    /**
     * 파이프라인 결과에서 HGETALL 응답만 골라 문제와 짝짓는다. 결과에 담기는 Map 은 HGETALL 뿐이고
     * 순서는 명령을 쌓은 순서 그대로다.
     *
     * <p>개수가 어긋나면 <b>짝짓지 않고 통째로 포기한다</b> — 한 칸 밀린 채 짝지으면 다른 문제의 표가
     * 그 문제의 것인 양 200 으로 나가고, 그건 전부 0 으로 나가는 것보다 나쁘다.
     */
    private Map<Long, Map<Integer, Long>> collect(Set<Long> quizIds, List<Object> results) {
        List<Object> hashes = results == null ? List.of()
                : results.stream().filter(Map.class::isInstance).toList();
        if (hashes.size() != quizIds.size()) {
            log.warn("퀴즈 투표 집계 조회 결과 수 불일치 quizzes={} hashes={}", quizIds.size(), hashes.size());
            return Map.of();
        }
        Map<Long, Map<Integer, Long>> counts = new HashMap<>();
        int index = 0;
        for (Long quizId : quizIds) {
            Map<Integer, Long> parsed = parseCounts((Map<?, ?>) hashes.get(index++));
            if (!parsed.isEmpty()) {
                counts.put(quizId, parsed);
            }
        }
        return counts;
    }

    /**
     * 필드·값이 0 이상의 정수로 해석되지 않으면 그 항목을 버린다(호출부가 0 으로 채운다).
     * 항목마다 로그를 남기지 않는 것은 의도다 — 깨진 키 하나가 로그 폭주가 되면 안 된다.
     */
    private static Map<Integer, Long> parseCounts(Map<?, ?> hash) {
        Map<Integer, Long> parsed = new HashMap<>();
        hash.forEach((field, value) -> {
            Integer optionNo = toInt(asString(field));
            Long count = toCount(asString(value));
            if (optionNo != null && count != null) {
                parsed.put(optionNo, count);
            }
        });
        return parsed;
    }

    /** 파이프라인 결과는 템플릿 직렬화기를 타 보통 String 이지만, 원시 byte[] 로 올 여지도 남긴다. */
    private static String asString(Object raw) {
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw == null ? null : raw.toString();
    }

    private static Integer toInt(String raw) {
        try {
            return raw == null ? null : Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toCount(String raw) {
        try {
            long count = raw == null ? -1L : Long.parseLong(raw.trim());
            return count < 0 ? null : count;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code EXPIRE key seconds NX} — 만료가 <b>없을 때만</b> 건다. 무조건 걸면 호출할 때마다 수명이
     * 되살아나(연장 금지 위반) 키가 사실상 영구가 된다.
     */
    private void applyTtlIfAbsent(RedisConnection connection, byte[] key) {
        connection.keyCommands().expire(key, ttlSeconds, ExpirationOptions.Condition.NX);
    }

    private static byte[] key(long quizId) {
        return (KEY_PREFIX + quizId).getBytes(StandardCharsets.UTF_8);
    }

    /** 필드 이름은 {@code quiz_options.option} 값(0-based)의 십진 문자열 그대로다. */
    private static byte[] field(int optionNo) {
        return Integer.toString(optionNo).getBytes(StandardCharsets.UTF_8);
    }
}
