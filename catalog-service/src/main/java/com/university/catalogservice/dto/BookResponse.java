package com.university.catalogservice.dto;

import java.math.BigDecimal;

public record BookResponse(
        Long id,
        String title,
        String author,
        String isbn,
        String genre,
        BigDecimal price,
        Integer stockQuantity
) {
}
