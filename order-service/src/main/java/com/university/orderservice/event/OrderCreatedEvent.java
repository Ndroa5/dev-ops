package com.university.orderservice.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload published to RabbitMQ by OrderEventPublisher. notification-service keeps its own
 * local copy of this shape (see notification-service's event package) rather than depending on
 * this class directly, so the two services stay decoupled at the code level.
 */
public record OrderCreatedEvent(
        Long orderId,
        Long bookId,
        Integer quantity,
        String buyerEmail,
        BigDecimal totalPrice,
        Instant createdAt
) {
}
