package com.university.orderservice.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload that will be published to RabbitMQ once messaging is wired up (see OrderEventPublisher).
 */
public record OrderCreatedEvent(
        Long orderId,
        Long bookId,
        Integer quantity,
        BigDecimal totalPrice,
        Instant createdAt
) {
}
