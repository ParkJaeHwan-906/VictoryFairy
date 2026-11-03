package com.skhynix.chat.message.dto;

import com.skhynix.chat.shared.ChatMessageView;
import java.util.List;

/**
 * 히스토리 한 페이지(CHAT-GC-68). {@code nextCursor}·{@code hasNext} 는 필터 이전 원본 기준이라
 * {@code messages} 가 페이지 크기보다 적어도 끝이 아닐 수 있다(CHAT-GC-70).
 */
public record HistoryResponse(List<ChatMessageView> messages, Long nextCursor, boolean hasNext) {
}
