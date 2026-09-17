package com.skhynix.quiz.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(

        @NotBlank
        @Size(max = 500)
        String content
) {
}
