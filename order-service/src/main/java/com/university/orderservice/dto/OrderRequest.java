package com.university.orderservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderRequest(
        @NotNull Long bookId,
        @NotNull @Positive Integer quantity
) {
}
