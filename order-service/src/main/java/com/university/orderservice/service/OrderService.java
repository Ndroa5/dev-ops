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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final OrderEventPublisher orderEventPublisher;

    public OrderResponse createOrder(OrderRequest request) {
        BookDto book = catalogClient.getBook(request.bookId());
        if (book.stockQuantity() < request.quantity()) {
            throw new IllegalStateException("Insufficient stock for book: " + request.bookId());
        }

        catalogClient.decrementStock(request.bookId(), request.quantity());

        BigDecimal totalPrice = book.price().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = Order.builder()
                .bookId(request.bookId())
                .quantity(request.quantity())
                .buyerEmail(request.buyerEmail())
                .totalPrice(totalPrice)
                .status(OrderStatus.CONFIRMED)
                .build();
        order = orderRepository.save(order);

        orderEventPublisher.publishOrderCreated(new OrderCreatedEvent(
                order.getId(), order.getBookId(), order.getQuantity(), order.getBuyerEmail(),
                order.getTotalPrice(), order.getCreatedAt()
        ));

        return toResponse(order);
    }

    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    public OrderResponse findById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + id));
        return toResponse(order);
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(), order.getBookId(), order.getQuantity(), order.getBuyerEmail(),
                order.getTotalPrice(), order.getStatus(), order.getCreatedAt()
        );
    }
}
