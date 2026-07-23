package com.skhynix.quiz.chat.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이징 응답 래퍼. Spring Data {@code Page}를 직접 직렬화하지 않고(포맷 불안정) 필요한 메타만 노출한다.
 *
 * @param content       현재 페이지 항목
 * @param page          현재 페이지 번호(0-base)
 * @param size          페이지 크기
 * @param totalElements 전체 항목 수(유효 메시지 기준)
 * @param totalPages    전체 페이지 수
 * @param hasNext       다음 페이지 존재 여부
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
