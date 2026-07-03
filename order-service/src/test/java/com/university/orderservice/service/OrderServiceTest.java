package com.university.orderservice.service;

import com.university.orderservice.client.BookDto;
import com.university.orderservice.client.CatalogClient;
import com.university.orderservice.dto.OrderRequest;
import com.university.orderservice.dto.OrderResponse;
import com.university.orderservice.event.OrderCreatedEvent;
import com.university.orderservice.event.OrderEventPublisher;
import com.university.orderservice.model.Order;
import com.university.orderservice.model.OrderStatus;
import com.university.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CatalogClient catalogClient;
    @Mock
    private OrderEventPublisher orderEventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, catalogClient, orderEventPublisher);
    }

    @Test
    void createOrder_decrementsStockSavesOrderAndPublishesEvent() {
        OrderRequest request = new OrderRequest(1L, 2, "alice@example.com");
        BookDto book = new BookDto(1L, "Dune", "Frank Herbert", "9780441172719", "Sci-Fi",
                new BigDecimal("15.99"), 10);

        when(catalogClient.getBook(1L)).thenReturn(book);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            o.setCreatedAt(Instant.parse("2026-07-03T00:00:00Z"));
            return o;
        });

        OrderResponse response = orderService.createOrder(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.buyerEmail()).isEqualTo("alice@example.com");
        assertThat(response.totalPrice()).isEqualByComparingTo("31.98");
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);

        verify(catalogClient).decrementStock(1L, 2);

        ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(orderEventPublisher).publishOrderCreated(eventCaptor.capture());
        OrderCreatedEvent event = eventCaptor.getValue();
        assertThat(event.orderId()).isEqualTo(1L);
        assertThat(event.bookId()).isEqualTo(1L);
        assertThat(event.quantity()).isEqualTo(2);
        assertThat(event.buyerEmail()).isEqualTo("alice@example.com");
        assertThat(event.totalPrice()).isEqualByComparingTo("31.98");
    }

    @Test
    void createOrder_rejectsInsufficientStockWithoutDecrementingOrPublishing() {
        OrderRequest request = new OrderRequest(1L, 100, "alice@example.com");
        BookDto book = new BookDto(1L, "Dune", "Frank Herbert", "9780441172719", "Sci-Fi",
                new BigDecimal("15.99"), 10);

        when(catalogClient.getBook(1L)).thenReturn(book);

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");

        verify(catalogClient, never()).decrementStock(any(), anyInt());
        verify(orderRepository, never()).save(any());
        verify(orderEventPublisher, never()).publishOrderCreated(any());
    }

    @Test
    void findById_returnsOrderWhenPresent() {
        Order order = Order.builder().id(1L).bookId(1L).quantity(2).buyerEmail("alice@example.com")
                .totalPrice(new BigDecimal("31.98")).status(OrderStatus.CONFIRMED)
                .createdAt(Instant.parse("2026-07-03T00:00:00Z")).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.findById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.buyerEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void findById_throwsWhenMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findAll_mapsAllOrders() {
        Order order = Order.builder().id(1L).bookId(1L).quantity(2).buyerEmail("alice@example.com")
                .totalPrice(new BigDecimal("31.98")).status(OrderStatus.CONFIRMED)
                .createdAt(Instant.parse("2026-07-03T00:00:00Z")).build();
        when(orderRepository.findAll()).thenReturn(List.of(order));

        List<OrderResponse> responses = orderService.findAll();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).bookId()).isEqualTo(1L);
    }
}
