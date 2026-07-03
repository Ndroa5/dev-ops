package com.university.catalogservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record BookRequest(
        @NotBlank String title,
        @NotBlank String author,
        @NotBlank String isbn,
        String genre,
        @NotNull @Positive BigDecimal price,
        @NotNull @PositiveOrZero Integer stockQuantity
) {
}
