package com.skhynix.profanity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 욕설 탐지 빈 등록. 라이브러리 모듈이라 소비 앱의 컴포넌트 스캔 범위 밖이므로 {@code @Import} 로 명시해 쓴다.
 *
 * <p>{@link ProfanityDetector} 생성 시 JSON 을 읽고 패턴을 컴파일한다 — 데이터가 없거나 깨지면 빈 생성이
 * 실패해 앱이 기동하지 않는다(필터가 조용히 꺼진 채 도는 것보다 낫다).
 */
@Configuration(proxyBeanMethods = false)
public class ProfanityConfig {

    @Bean
    public ProfanityDataLoader profanityDataLoader(ObjectMapper objectMapper) {
        return new ProfanityDataLoader(objectMapper);
    }

    @Bean
    public ProfanityDetector profanityDetector(ProfanityDataLoader profanityDataLoader) {
        return new ProfanityDetector(profanityDataLoader);
    }
}
