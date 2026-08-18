package com.skhynix.quiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

public record QuizSubmitRequest(

        @NotNull
        Integer option
) {
}
