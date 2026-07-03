package com.university.notificationservice.controller;

import com.university.notificationservice.dto.NotificationRequest;
import com.university.notificationservice.dto.NotificationResponse;
import com.university.notificationservice.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/send")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        NotificationResponse response = notificationService.simulateSend(request).blockingGet();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<NotificationResponse> findAll() {
        return notificationService.findAll();
    }
}
