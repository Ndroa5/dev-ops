package com.university.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Brings up the real docker-compose.e2e.yml stack (all 5 services, Postgres, RabbitMQ, built from
 * the actual Dockerfiles - not a Spring test context) and drives the full order flow through real
 * HTTP calls against the gateway, proving REST, RabbitMQ, and the RxJava notification pipeline all
 * work together across independently running containers. The per-service integration tests already
 * cover each service in isolation; this is the one test that proves the whole chain.
 */
@Testcontainers
class FullOrderFlowE2ETest {

    private static final String GATEWAY = "http://localhost:9180";

    @Container
    static final ComposeContainer environment =
            new ComposeContainer(new File("docker-compose.e2e.yml"))
                    .withLocalCompose(true)
                    .waitingFor("api-gateway", Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(10)));

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullOrderFlowWorksAcrossAllServices() throws Exception {
        long unique = System.currentTimeMillis();
        String email = "e2e-" + unique + "@example.com";

        HttpResponse<String> registerResponse = post("/api/auth/register", Map.of(
                "username", "e2euser" + unique,
                "email", email,
                "password", "password123"
        ));
        assertThat(registerResponse.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(registerResponse.body()).get("token").asText()).isNotBlank();

        HttpResponse<String> bookResponse = post("/api/books", Map.of(
                "title", "E2E Test Book",
                "author", "E2E Test Author",
                "isbn", "isbn-" + unique,
                "genre", "Test",
                "price", 9.99,
                "stockQuantity", 5
        ));
        assertThat(bookResponse.statusCode()).isEqualTo(201);
        JsonNode book = mapper.readTree(bookResponse.body());
        long bookId = book.get("id").asLong();
        assertThat(book.get("stockQuantity").asInt()).isEqualTo(5);

        HttpResponse<String> orderResponse = post("/api/orders", Map.of(
                "bookId", bookId,
                "quantity", 2,
                "buyerEmail", email
        ));
        assertThat(orderResponse.statusCode()).isEqualTo(201);
        JsonNode order = mapper.readTree(orderResponse.body());
        long orderId = order.get("id").asLong();
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");

        // Stock decrement happened synchronously over REST as part of order creation.
        JsonNode updatedBook = mapper.readTree(get("/api/books/" + bookId).body());
        assertThat(updatedBook.get("stockQuantity").asInt()).isEqualTo(3);

        // Notification arrives asynchronously via RabbitMQ -> RxJava pipeline, so poll for it.
        await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            JsonNode notifications = mapper.readTree(get("/api/notifications").body());
            boolean found = false;
            for (JsonNode n : notifications) {
                if (n.get("recipient").asText().equals(email)
                        && n.get("message").asText().contains("order #" + orderId)) {
                    found = true;
                    assertThat(n.get("status").asText()).isEqualTo("SENT");
                }
            }
            assertThat(found).as("notification for order #%s to %s", orderId, email).isTrue();
        });
    }

    private static HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GATEWAY + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GATEWAY + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
