package com.skhynix.chat.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 컨트롤러에서 {@code @Valid} 를 붙이지 않는다. 판정 순서가 404 → 400 이라 서비스가 방 확인 뒤에 검증한다(CHAT-GC-51).
 *
 * @param clientMsgId 메시지마다 새로 만드는 UUID. dedup 키에 그대로 들어가므로 형식을 강제한다(CHAT-GC-53)
 */
public record SendMessageRequest(

        @NotBlank
        @Size(max = 500)
        String content,

        @NotNull
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String clientMsgId
) {
}
