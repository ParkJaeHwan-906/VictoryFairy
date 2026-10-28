package com.skhynix.user.community.dto;

import jakarta.validation.constraints.NotNull;

public record ReactionRequest(@NotNull ReactionChoice reaction) {
}
