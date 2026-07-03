package com.university.notificationservice.service;

import com.university.notificationservice.dto.NotificationRequest;
import com.university.notificationservice.dto.NotificationResponse;
import com.university.notificationservice.model.NotificationLog;
import com.university.notificationservice.model.NotificationStatus;
import com.university.notificationservice.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationLogRepository);
    }

    @Test
    void simulateSend_persistsSentLogAndReturnsResponse() {
        NotificationRequest request = new NotificationRequest("alice@example.com", "Your order shipped");
        when(notificationLogRepository.save(any(NotificationLog.class))).thenAnswer(inv -> {
            NotificationLog log = inv.getArgument(0);
            log.setId(1L);
            log.setSentAt(Instant.parse("2026-07-03T00:00:00Z"));
            return log;
        });

        NotificationResponse response = notificationService.simulateSend(request).blockingGet();

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.recipient()).isEqualTo("alice@example.com");
        assertThat(response.message()).isEqualTo("Your order shipped");
        assertThat(response.status()).isEqualTo(NotificationStatus.SENT);

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void findAll_mapsAllPersistedLogs() {
        NotificationLog log = NotificationLog.builder()
                .id(1L).recipient("alice@example.com").message("hi")
                .status(NotificationStatus.SENT).sentAt(Instant.parse("2026-07-03T00:00:00Z"))
                .build();
        when(notificationLogRepository.findAll()).thenReturn(List.of(log));

        List<NotificationResponse> responses = notificationService.findAll();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).recipient()).isEqualTo("alice@example.com");
    }
}
