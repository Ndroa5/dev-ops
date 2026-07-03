package com.university.apigateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class GatewayStatusController {

    @GetMapping("/gateway/status")
    public Mono<Map<String, String>> status() {
        return Mono.just(Map.of("service", "api-gateway", "status", "UP"));
    }
}
