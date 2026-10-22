package com.skhynix.chat.integration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * chat 앱을 DB 없이 띄우는 테스트 전용 구성. 운영 {@code ChatApplication} 과 달리 JPA·DataSource 자동설정을 배제하고
 * (속성 {@code spring.autoconfigure.exclude}) 리포지토리 4종은 각 테스트가 {@code @MockitoBean} 으로 채운다.
 * 로컬 .env 가 원격 DB 를 가리키므로 실제 DB 연결이 일어나지 않게 하는 것이 목적이다.
 *
 * <p>스캔 범위는 메인 코드의 패키지로 좁힌다 — 테스트 클래스 안의 {@code @Configuration}(예: ChatStartupTest)이 딸려 오지 않게.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.skhynix.chat.room", "com.skhynix.chat.message", "com.skhynix.chat.subscription",
        "com.skhynix.chat.realtime", "com.skhynix.chat.gateway", "com.skhynix.chat.history",
        "com.skhynix.chat.shared", "com.skhynix.chat.global.config", "com.skhynix.chat.global.validation",
        "com.skhynix.chat.like"
}, useDefaultFilters = true)
public class ChatE2eApplication {
}
