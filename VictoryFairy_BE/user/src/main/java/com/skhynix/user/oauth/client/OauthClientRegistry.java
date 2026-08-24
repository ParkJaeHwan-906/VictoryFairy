package com.skhynix.user.oauth.client;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 경로의 provider 이름({@code kakao}·{@code naver}·{@code google}) → 실제 호출자.
 *
 * <p><b>자격증명이 주입되지 않은 provider 는 이 지도에 들어오지 않는다.</b> 등록해 두고 호출 시점에
 * 실패하게 두면 그 실패가 "지원하지 않음"이 아니라 provider 통신 오류(502)로 둔갑해, 설정 누락이
 * 외부 장애처럼 보인다.
 */
@Component
public class OauthClientRegistry {

    private final Map<OauthProvider, OauthClient> clients = new EnumMap<>(OauthProvider.class);

    public OauthClientRegistry(List<OauthClient> clients) {
        for (OauthClient client : clients) {
            if (client.isConfigured()) {
                this.clients.put(client.provider(), client);
            }
        }
    }

    /**
     * @param provider 경로 변수 그대로의 문자열. 우리가 모르는 이름과 설정되지 않은 provider 를
     *                 같은 400 으로 흡수한다 — 사용자가 할 수 있는 일이 같고, 구분해 알려 주면
     *                 어떤 provider 가 설정 대기 중인지가 밖으로 드러난다
     */
    public OauthClient get(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        OauthProvider parsed;
        try {
            parsed = OauthProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        OauthClient client = clients.get(parsed);
        if (client == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
        }
        return client;
    }
}
