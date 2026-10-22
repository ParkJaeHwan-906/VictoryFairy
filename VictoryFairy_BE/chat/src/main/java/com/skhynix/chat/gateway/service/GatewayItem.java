package com.skhynix.chat.gateway.service;

import com.skhynix.chat.shared.ChatMessageView;

/** 방별 큐 원소. 컨슈머 스레드가 쌓고 배처가 꺼낸다. */
sealed interface GatewayItem {

    /** chat-messages 레코드. {@code senderId} 는 발신자 제외(CHAT-GC-88)에만 쓰고 밖으로 내보내지 않는다. */
    record Message(Long senderId, ChatMessageView view) implements GatewayItem {
    }

    /** chat-control blind 툼스톤. */
    record Deleted(long msgId) implements GatewayItem {
    }
}
