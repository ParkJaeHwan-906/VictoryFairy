package com.skhynix.user.support.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

// 빈 배열은 허용(변경 없음)이라 @NotEmpty 가 아니라 @NotNull 이다.
public record SupportPlayersRequest(
        @NotNull(message = "선수 목록이 필요합니다.") List<Long> playerIds) {
}
