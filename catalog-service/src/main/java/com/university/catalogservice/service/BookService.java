package com.university.catalogservice.service;

import com.university.catalogservice.dto.BookRequest;
import com.university.catalogservice.dto.BookResponse;
import com.university.catalogservice.model.Book;
import com.university.catalogservice.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;

    public List<BookResponse> findAll() {
        return bookRepository.findAll().stream().map(this::toResponse).toList();
    }

    public BookResponse findById(Long id) {
        return toResponse(getBookOrThrow(id));
    }

    public BookResponse create(BookRequest request) {
        Book book = Book.builder()
                .title(request.title())
                .author(request.author())
                .isbn(request.isbn())
                .genre(request.genre())
                .price(request.price())
                .stockQuantity(request.stockQuantity())
                .build();
        return toResponse(bookRepository.save(book));
    }

    public BookResponse update(Long id, BookRequest request) {
        Book book = getBookOrThrow(id);
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setIsbn(request.isbn());
        book.setGenre(request.genre());
        book.setPrice(request.price());
        book.setStockQuantity(request.stockQuantity());
        return toResponse(bookRepository.save(book));
    }

    public void delete(Long id) {
        if (!bookRepository.existsById(id)) {
            throw new NoSuchElementException("Book not found: " + id);
        }
        bookRepository.deleteById(id);
    }

    public BookResponse decrementStock(Long id, int quantity) {
        Book book = getBookOrThrow(id);
        if (book.getStockQuantity() < quantity) {
            throw new IllegalStateException("Insufficient stock for book: " + id);
        }
        book.setStockQuantity(book.getStockQuantity() - quantity);
        return toResponse(bookRepository.save(book));
    }

    private Book getBookOrThrow(Long id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Book not found: " + id));
    }

    private BookResponse toResponse(Book book) {
        return new BookResponse(
                book.getId(), book.getTitle(), book.getAuthor(),
                book.getIsbn(), book.getGenre(), book.getPrice(), book.getStockQuantity()
        );
    }
}
