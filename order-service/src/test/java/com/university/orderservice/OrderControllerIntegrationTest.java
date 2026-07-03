package com.university.orderservice;

import com.university.orderservice.client.BookDto;
import com.university.orderservice.client.CatalogClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitMQ is not started here on purpose: RabbitAutoConfiguration is excluded and RabbitTemplate
 * is mocked, so this test proves order-service calls publish with the right payload without
 * depending on a real broker. The real publish -> consume round trip is covered by
 * notification-service's integration test, which does use a RabbitMQ testcontainer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
)
@AutoConfigureMockMvc
@Testcontainers
class OrderControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CatalogClient catalogClient;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void createOrder_publishesEventAndPersistsOrder() throws Exception {
        BookDto book = new BookDto(1L, "Dune", "Frank Herbert", "9780441172719", "Sci-Fi",
                new BigDecimal("15.99"), 10);
        when(catalogClient.getBook(1L)).thenReturn(book);
        when(catalogClient.decrementStock(eq(1L), eq(2))).thenReturn(book);

        String orderBody = """
                {"bookId":1,"quantity":2,"buyerEmail":"alice@example.com"}
                """;

        String response = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyerEmail").value("alice@example.com"))
                .andExpect(jsonPath("$.totalPrice").value(31.98))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        Number orderIdNumber = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        long orderId = orderIdNumber.longValue();

        verify(catalogClient).decrementStock(1L, 2);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(eq("order-events-exchange"), eq("order.created"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"buyerEmail\":\"alice@example.com\"");
        assertThat(payload).contains("\"bookId\":1");
        assertThat(payload).contains("\"quantity\":2");

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerEmail").value("alice@example.com"));
    }

    @Test
    void createOrder_rejectsInsufficientStockAndDoesNotPublish() throws Exception {
        BookDto book = new BookDto(1L, "Dune", "Frank Herbert", "9780441172719", "Sci-Fi",
                new BigDecimal("15.99"), 1);
        when(catalogClient.getBook(1L)).thenReturn(book);

        String orderBody = """
                {"bookId":1,"quantity":50,"buyerEmail":"alice@example.com"}
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isConflict());

        verify(catalogClient, never()).decrementStock(any(), anyInt());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(String.class));
    }

    @Test
    void createOrder_rejectsInvalidPayload() throws Exception {
        String invalidBody = """
                {"bookId":1,"quantity":-1,"buyerEmail":"not-an-email"}
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void getOrder_notFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}
