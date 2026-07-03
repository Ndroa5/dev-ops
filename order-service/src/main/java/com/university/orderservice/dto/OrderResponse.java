package com.university.orderservice.dto;

import com.university.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long id,
        Long bookId,
        Integer quantity,
        BigDecimal totalPrice,
        OrderStatus status,
        Instant createdAt
) {
}
