package com.university.notificationservice.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.notificationservice.dto.NotificationRequest;
import com.university.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes OrderCreatedEvent messages published by order-service and routes them through the
 * same RxJava pipeline (NotificationService.simulateSend) used by the manual REST endpoint, so
 * both entry points produce identical NotificationLog records.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void handleOrderCreated(String message) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("Consumed OrderCreatedEvent: {}", event);

            NotificationRequest request = new NotificationRequest(
                    event.buyerEmail(),
                    "Your order #%d for book #%d (qty %d, total %s) has been confirmed."
                            .formatted(event.orderId(), event.bookId(), event.quantity(), event.totalPrice())
            );

            notificationService.simulateSend(request).blockingGet();
        } catch (Exception e) {
            log.error("Failed to process OrderCreatedEvent message: {}", message, e);
        }
    }
}
