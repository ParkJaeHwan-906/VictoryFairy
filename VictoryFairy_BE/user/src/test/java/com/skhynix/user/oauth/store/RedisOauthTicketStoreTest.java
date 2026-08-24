package com.skhynix.user.oauth.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.user.entity.OauthProvider;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RedisOauthTicketStore} — 세 종류 티켓의 저장·TTL·소비 계약. 요구사항:
 * {@code docs/requirements/user/oauth-login.md} USER-OAU-28(10분 TTL), 75(소비 시 재사용 불가),
 * 79/94(쿨다운은 티켓 단위), 93(최신 발송 주소만 유효).
 *
 * <p>{@code StringRedisTemplate}을 목으로 대체하는 이유는 {@code RedisProfileImageUploadLimitStoreTest}와
 * 같다 — 이 모듈에 임베디드/실행 중인 Redis가 없어(DB 전략 문서 참고) 실제 TTL 만료·키 소멸은 유닛으로
 * 증명되지 않는다. 여기서는 "TTL을 건 인자가 맞는가"·"어떤 키를 지우는가"까지만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisOauthTicketStoreTest {

    private static final String TOKEN = "test-ticket-token";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisOauthTicketStore store;

    private RedisOauthTicketStore newStore() {
        return new RedisOauthTicketStore(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("[USER-OAU-28] issue()는 티켓 TTL을 10분으로 건다")
    void issue_setsTicketWithTenMinuteTtl() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        OauthTicket ticket = OauthTicket.signup(OauthProvider.GOOGLE, "sub-1", "user@example.com", true);
        store = newStore();

        // when
        String token = store.issue(ticket);

        // then: 발급된 토큰이 비어 있지 않고, TTL이 정확히 10분으로 걸린다
        assertThat(token).isNotBlank();
        verify(valueOperations).set(eq("oauth:ticket:" + token), any(String.class),
                eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("issue()는 매 호출마다 서로 다른(추측 불가한) 토큰을 발급한다")
    void issue_generatesDistinctTokensAcrossCalls() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        OauthTicket ticket = OauthTicket.input(OauthProvider.KAKAO, "kakao-id-1");
        store = newStore();

        // when
        String first = store.issue(ticket);
        String second = store.issue(ticket);

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("find()는 저장된 값을 역직렬화해 돌려준다")
    void find_existingTicket_returnsDeserializedTicket() {
        // given
        OauthTicket original = OauthTicket.link(OauthProvider.NAVER, "naver-id-1", "user@naver.com");
        String json = objectMapper.writeValueAsString(original);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:ticket:" + TOKEN)).willReturn(json);
        store = newStore();

        // when
        Optional<OauthTicket> found = store.find(TOKEN);

        // then
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(original);
    }

    @Test
    @DisplayName("[USER-OAU-36, 76] find()는 존재하지 않는 티켓에 대해 빈 값을 돌려준다(만료·이미 소비됨과 "
            + "구분하지 않는다)")
    void find_missingTicket_returnsEmpty() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:ticket:" + TOKEN)).willReturn(null);
        store = newStore();

        // when
        Optional<OauthTicket> found = store.find(TOKEN);

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("find()는 null/공백 티켓 문자열에 대해 조회 없이 빈 값을 돌려준다")
    void find_blankTicket_returnsEmptyWithoutLookup() {
        // given
        store = newStore();

        // when
        Optional<OauthTicket> found = store.find("");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("find()는 역직렬화에 실패하는 값(배포로 모양이 바뀐 옛 값)을 만료와 동일하게 빈 값으로 흡수한다")
    void find_malformedValue_returnsEmpty() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:ticket:" + TOKEN)).willReturn("not-a-valid-json");
        store = newStore();

        // when
        Optional<OauthTicket> found = store.find(TOKEN);

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("[USER-OAU-75] consume()은 티켓·코드·시도횟수·대상주소를 모두 지우지만 쿨다운은 남긴다"
            + "(방금 보낸 메일이 없던 일이 되지 않는다)")
    void consume_deletesTicketAndCodeStateButKeepsCooldown() {
        // given
        store = newStore();

        // when
        store.consume(TOKEN);

        // then
        verify(redisTemplate).delete("oauth:ticket:" + TOKEN);
        verify(redisTemplate).delete("oauth:ticket:code:" + TOKEN);
        verify(redisTemplate).delete("oauth:ticket:attempts:" + TOKEN);
        verify(redisTemplate).delete("oauth:ticket:target:" + TOKEN);
        verify(redisTemplate, never()).delete("oauth:ticket:cooldown:" + TOKEN);
    }

    @Test
    @DisplayName("[USER-OAU-93] saveCode()는 인증번호와 이번 발송 대상 주소를 함께, 같은 5분 TTL로 저장한다")
    void saveCode_storesCodeAndTargetEmailWithSameTtl() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        store = newStore();

        // when
        store.saveCode(TOKEN, "123456", "latest@example.com");

        // then
        verify(valueOperations).set("oauth:ticket:code:" + TOKEN, "123456", Duration.ofMinutes(5));
        verify(valueOperations).set("oauth:ticket:target:" + TOKEN, "latest@example.com",
                Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("[USER-OAU-93] findTargetEmail()은 가장 최근에 saveCode()로 저장된 주소를 그대로 돌려준다")
    void findTargetEmail_returnsLatestStoredTarget() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:ticket:target:" + TOKEN)).willReturn("second@example.com");
        store = newStore();

        // when
        Optional<String> target = store.findTargetEmail(TOKEN);

        // then
        assertThat(target).contains("second@example.com");
    }

    @Test
    @DisplayName("invalidateCode()는 코드·시도횟수·대상주소 셋을 함께 지운다(재발송 시 이전 상태 전량 무효화)")
    void invalidateCode_deletesCodeAttemptsAndTarget() {
        // given
        store = newStore();

        // when
        store.invalidateCode(TOKEN);

        // then
        verify(redisTemplate).delete("oauth:ticket:code:" + TOKEN);
        verify(redisTemplate).delete("oauth:ticket:attempts:" + TOKEN);
        verify(redisTemplate).delete("oauth:ticket:target:" + TOKEN);
    }

    @Test
    @DisplayName("[USER-OAU-78] incrementAttempts()는 최초 증가(결과=1)에서만 코드와 같은 5분 TTL을 건다")
    void incrementAttempts_firstCall_setsTtlOnlyOnce() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment("oauth:ticket:attempts:" + TOKEN)).willReturn(1L);
        store = newStore();

        // when
        int attempts = store.incrementAttempts(TOKEN);

        // then
        assertThat(attempts).isEqualTo(1);
        verify(redisTemplate).expire("oauth:ticket:attempts:" + TOKEN, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("incrementAttempts()는 두 번째 이후 증가에서는 TTL을 다시 걸지 않는다(코드보다 오래 살아남지 않도록)")
    void incrementAttempts_subsequentCall_doesNotResetTtl() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment("oauth:ticket:attempts:" + TOKEN)).willReturn(3L);
        store = newStore();

        // when
        int attempts = store.incrementAttempts(TOKEN);

        // then
        assertThat(attempts).isEqualTo(3);
        verify(redisTemplate, never()).expire(eq("oauth:ticket:attempts:" + TOKEN), any(Duration.class));
    }

    @Test
    @DisplayName("getAttempts()는 키가 없으면(미시도) 0을 돌려준다")
    void getAttempts_missingKey_returnsZero() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:ticket:attempts:" + TOKEN)).willReturn(null);
        store = newStore();

        // when & then
        assertThat(store.getAttempts(TOKEN)).isZero();
    }

    @Test
    @DisplayName("[USER-OAU-79, 94] startCooldown()은 60초 TTL로 티켓 단위 쿨다운 키를 만든다")
    void startCooldown_setsSixtySecondTtlKeyedByTicket() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        store = newStore();

        // when
        store.startCooldown(TOKEN);

        // then
        verify(valueOperations).set("oauth:ticket:cooldown:" + TOKEN, "1", Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("[USER-OAU-94] isCoolingDown()은 이메일이 아니라 티켓 문자열로만 키를 조회한다")
    void isCoolingDown_checksByTicketKeyOnly() {
        // given
        given(redisTemplate.hasKey("oauth:ticket:cooldown:" + TOKEN)).willReturn(true);
        store = newStore();

        // when & then
        assertThat(store.isCoolingDown(TOKEN)).isTrue();
        verify(redisTemplate).hasKey("oauth:ticket:cooldown:" + TOKEN);
    }
}
