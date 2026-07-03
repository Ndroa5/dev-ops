package com.university.notificationservice.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Local representation of order-service's OrderCreatedEvent payload. Deliberately not a shared
 * class between services (deserialized from the raw JSON body by OrderEventListener) so the two
 * services don't depend on each other's code, only on the agreed-upon JSON shape.
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
