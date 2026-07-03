package com.university.notificationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.university.notificationservice.event.OrderCreatedEvent;
import com.university.notificationservice.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses a real RabbitMQ testcontainer (unlike order-service's integration test, which mocks
 * RabbitTemplate) because this is where the actual publish -> consume round trip needs proving:
 * a real message goes on the wire and the real @RabbitListener has to pick it up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class NotificationConsumerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQContainer::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitMQContainer::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitMQContainer::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key}")
    private String routingKey;

    @Test
    void listenerConsumesOrderCreatedEventAndPersistsNotificationLog() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                42L, 7L, 3, "consumer-test@example.com", new BigDecimal("47.97"), Instant.now()
        );
        String payload = objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(exchange, routingKey, payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notificationLogRepository.findAll())
                        .anyMatch(log -> log.getRecipient().equals("consumer-test@example.com")
                                && log.getMessage().contains("order #42"))
        );
    }

    @Test
    void manualEndpointStillWorksIndependentlyOfRabbitMq() throws Exception {
        String body = """
                {"recipient":"manual@example.com","message":"Manual trigger"}
                """;

        mockMvc.perform(post("/api/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        assertThat(notificationLogRepository.findAll())
                .anyMatch(log -> log.getRecipient().equals("manual@example.com"));
    }
}
