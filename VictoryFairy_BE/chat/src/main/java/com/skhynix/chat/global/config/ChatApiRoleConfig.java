package com.skhynix.chat.global.config;

import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.profanity.ProfanityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * API 역할 전용 부품. 욕설 탐지는 전송 경로만 쓰므로 API 를 끈 파드는 금지어 데이터를 읽지 않는다.
 * 데이터가 없거나 깨지면 탐지기 빈 생성이 실패해 기동이 멈춘다(의도된 fail-fast).
 */
@Configuration
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@Import(ProfanityConfig.class)
public class ChatApiRoleConfig {
}
