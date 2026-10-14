package com.skhynix.quiz.quizroom.dto;

import jakarta.validation.constraints.NotNull;

public record LikeRequest(@NotNull(message = "liked는 필수입니다.") Boolean liked) {
}
