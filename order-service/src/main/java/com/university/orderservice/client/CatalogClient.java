package com.university.orderservice.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class CatalogClient {

    private final RestClient catalogRestClient;

    public BookDto getBook(Long bookId) {
        return catalogRestClient.get()
                .uri("/api/books/{id}", bookId)
                .retrieve()
                .body(BookDto.class);
    }

    public BookDto decrementStock(Long bookId, int quantity) {
        return catalogRestClient.patch()
                .uri("/api/books/{id}/decrement-stock?quantity={quantity}", bookId, quantity)
                .retrieve()
                .body(BookDto.class);
    }
}
