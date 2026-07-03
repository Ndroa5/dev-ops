package com.university.notificationservice.service;

import com.university.notificationservice.dto.NotificationRequest;
import com.university.notificationservice.dto.NotificationResponse;
import com.university.notificationservice.model.NotificationLog;
import com.university.notificationservice.model.NotificationStatus;
import com.university.notificationservice.repository.NotificationLogRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shared pipeline for both notification entry points: OrderEventListener (RabbitMQ) and
 * NotificationController's manual REST trigger both call simulateSend(...).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository notificationLogRepository;

    public Single<NotificationResponse> simulateSend(NotificationRequest request) {
        return Single.fromCallable(() -> {
                    log.info("Simulating notification send to {}: {}", request.recipient(), request.message());
                    NotificationLog saved = notificationLogRepository.save(
                            NotificationLog.builder()
                                    .recipient(request.recipient())
                                    .message(request.message())
                                    .status(NotificationStatus.SENT)
                                    .build()
                    );
                    return toResponse(saved);
                })
                .subscribeOn(Schedulers.io());
    }

    public List<NotificationResponse> findAll() {
        return notificationLogRepository.findAll().stream().map(this::toResponse).toList();
    }

    private NotificationResponse toResponse(NotificationLog log) {
        return new NotificationResponse(
                log.getId(), log.getRecipient(), log.getMessage(), log.getStatus(), log.getSentAt()
        );
    }
}
