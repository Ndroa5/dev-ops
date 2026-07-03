package com.university.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(@Value("${catalog-service.url}") String catalogServiceUrl) {
        return RestClient.builder()
                .baseUrl(catalogServiceUrl)
                .build();
    }
}
