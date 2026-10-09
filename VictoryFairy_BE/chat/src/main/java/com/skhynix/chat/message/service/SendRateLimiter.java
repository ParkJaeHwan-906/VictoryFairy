package com.skhynix.chat.message.service;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 사용자 단위 1초 고정 창 속도 제한(CHAT-GC-58). 방이 달라도 한 창을 공유한다.
 * Redis 가 실패하면 건너뛴다(fail-open, CHAT-GC-65).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SendRateLimiter {

    // INCR 과 "첫 증가 때만 EXPIRE" 를 한 번에 묶는다. 둘로 나눠 보내면 EXPIRE 만 실패했을 때 TTL 없는 키가
    // 남아 그 사용자가 영원히 429 를 받는다.
    private static final RedisScript<Long> INCR_WITH_WINDOW = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);
    private static final String WINDOW_SECONDS = "1";

    private final StringRedisTemplate redisTemplate;
    private final ChatProperties chatProperties;

    public void check(Long userAccountId) {
        Long count;
        try {
            count = redisTemplate.execute(INCR_WITH_WINDOW, List.of(ChatRedisKeys.rate(userAccountId)), WINDOW_SECONDS);
        } catch (DataAccessException e) {
            log.warn("속도 제한 확인 실패, 건너뛴다 userAccountId={}", userAccountId, e);
            return;
        }
        if (count != null && count > chatProperties.rateLimit().perSecond()) {
            throw new BusinessException(ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
        }
    }
}
