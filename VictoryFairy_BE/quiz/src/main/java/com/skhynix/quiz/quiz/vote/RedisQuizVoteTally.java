package com.skhynix.quiz.quiz.vote;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 보기별 투표 수를 문제당 Redis Hash 한 키(<code>quiz:votes:{quizId}</code>)에 쌓는다.
 *
 * <p><b>프로파일 분기가 없다(의도).</b> 같은 앱의 실시간 fan-out 은 기동 시 구독 연결을 여는 리스너
 * 컨테이너 때문에 {@code @Profile("prod")} 지만, 집계는 dev·prod 모두 실제 Redis 에 쓴다 — 이 저장소의
 * 최종 검증이 항상 dev 프로파일 docker 기동이라, prod 전용으로 만들면 표가 실제로 쌓이는 것을 한 번도
 * 못 본 채 배포된다. 아래 예외 삼킴이 그 대가를 치러 준다(Redis 없이 맨몸 기동해도 no-op 으로 degrade).
 *
 * <p><b>왕복 수는 문제 수·보기 수와 무관한 상수다.</b> 두 경로 모두 {@code executePipelined} 안에서
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
    public void initialize(Map<Long, List<Integer>> optionNosByQuizId) {
        if (optionNosByQuizId == null || optionNosByQuizId.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                optionNosByQuizId.forEach((quizId, optionNos) -> {
                    if (quizId == null || optionNos == null || optionNos.isEmpty()) {
                        return;
                    }
                    byte[] key = key(quizId);
                    for (Integer optionNo : optionNos) {
                        if (optionNo == null) {
                            continue;
                        }
                        // HSETNX 여야 한다 — 조회 후 분기하거나 HSET 으로 덮으면 이미 쌓인 표가
                        // 뒤늦은 /today 호출 한 번에 0 으로 밀린다.
                        connection.hashCommands().hSetNX(key, field(optionNo), ZERO);
                    }
                    applyTtlIfAbsent(connection, key);
                });
                return null;
            });
        } catch (Exception e) {
            log.warn("퀴즈 투표 집계 초기화 실패 optionNosByQuizId={}", optionNosByQuizId, e);
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
