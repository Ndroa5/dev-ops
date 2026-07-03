package com.university.catalogservice.service;

import com.university.catalogservice.dto.BookRequest;
import com.university.catalogservice.dto.BookResponse;
import com.university.catalogservice.model.Book;
import com.university.catalogservice.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    private BookService bookService;

    @BeforeEach
    void setUp() {
        bookService = new BookService(bookRepository);
    }

    private Book sampleBook() {
        return Book.builder()
                .id(1L).title("Dune").author("Frank Herbert").isbn("9780441172719")
                .genre("Sci-Fi").price(new BigDecimal("15.99")).stockQuantity(10)
                .build();
    }

    @Test
    void findAll_mapsAllBooksToResponses() {
        when(bookRepository.findAll()).thenReturn(List.of(sampleBook()));

        List<BookResponse> result = bookService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Dune");
    }

    @Test
    void findById_returnsBookWhenPresent() {
        when(bookRepository.findById(1L)).thenReturn(Optional.of(sampleBook()));

        BookResponse response = bookService.findById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.stockQuantity()).isEqualTo(10);
    }

    @Test
    void findById_throwsWhenMissing() {
        when(bookRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.findById(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void create_savesNewBookFromRequest() {
        BookRequest request = new BookRequest("Dune", "Frank Herbert", "9780441172719",
                "Sci-Fi", new BigDecimal("15.99"), 10);
        when(bookRepository.save(any(Book.class))).thenAnswer(inv -> {
            Book b = inv.getArgument(0);
            b.setId(1L);
            return b;
        });

        BookResponse response = bookService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Dune");
        assertThat(response.isbn()).isEqualTo("9780441172719");
    }

    @Test
    void update_throwsWhenBookMissing() {
        BookRequest request = new BookRequest("Dune", "Frank Herbert", "9780441172719",
                "Sci-Fi", new BigDecimal("15.99"), 10);
        when(bookRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.update(99L, request))
                .isInstanceOf(NoSuchElementException.class);
        verify(bookRepository, never()).save(any());
    }

    @Test
    void update_appliesNewFieldsToExistingBook() {
        Book existing = sampleBook();
        when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

        BookRequest request = new BookRequest("Dune Messiah", "Frank Herbert", "9780441172719",
                "Sci-Fi", new BigDecimal("12.50"), 5);

        BookResponse response = bookService.update(1L, request);

        assertThat(response.title()).isEqualTo("Dune Messiah");
        assertThat(response.price()).isEqualByComparingTo("12.50");
        assertThat(response.stockQuantity()).isEqualTo(5);
    }

    @Test
    void delete_throwsWhenBookMissing() {
        when(bookRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> bookService.delete(99L))
                .isInstanceOf(NoSuchElementException.class);
        verify(bookRepository, never()).deleteById(any());
    }

    @Test
    void delete_removesExistingBook() {
        when(bookRepository.existsById(1L)).thenReturn(true);

        bookService.delete(1L);

        verify(bookRepository).deleteById(1L);
    }

    @Test
    void decrementStock_reducesStockWhenSufficient() {
        Book existing = sampleBook();
        when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));
        ArgumentCaptor<Book> captor = ArgumentCaptor.forClass(Book.class);
        when(bookRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        BookResponse response = bookService.decrementStock(1L, 3);

        assertThat(response.stockQuantity()).isEqualTo(7);
        assertThat(captor.getValue().getStockQuantity()).isEqualTo(7);
    }

    @Test
    void decrementStock_throwsWhenInsufficientStock() {
        Book existing = sampleBook();
        when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> bookService.decrementStock(1L, 999))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient stock");

        verify(bookRepository, never()).save(any());
    }

    @Test
    void decrementStock_throwsWhenBookMissing() {
        when(bookRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.decrementStock(99L, 1))
                .isInstanceOf(NoSuchElementException.class);
    }
}
