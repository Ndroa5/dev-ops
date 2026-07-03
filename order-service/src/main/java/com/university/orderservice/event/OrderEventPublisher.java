package com.university.orderservice.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Placeholder for phase 2 (RabbitMQ). Currently just logs the event that would be published,
 * so the call site in OrderService does not need to change once messaging is added.
 */
@Slf4j
@Component
public class OrderEventPublisher {

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("[stub] Would publish OrderCreatedEvent to RabbitMQ: {}", event);
    }
}
