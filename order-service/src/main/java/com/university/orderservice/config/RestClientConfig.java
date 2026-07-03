package com.university.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${catalog-service.url}") String catalogServiceUrl
    ) {
        // Injecting Spring Boot's auto-configured RestClient.Builder (rather than calling the
        // static RestClient.builder() factory) is what wires in Micrometer's observation/tracing
        // instrumentation - without it, this client's calls to catalog-service carry no trace
        // context at all, and the resulting span never joins the caller's trace.
        //
        // But that auto-configured builder defaults to SimpleClientHttpRequestFactory (backed by
        // java.net.HttpURLConnection), which famously does not support the PATCH method
        // (java.net.ProtocolException: Invalid HTTP method: PATCH) - and this client uses PATCH
        // for /api/books/{id}/decrement-stock. Forcing JdkClientHttpRequestFactory (java.net.http
        // .HttpClient) here keeps the tracing instrumentation from the injected builder while
        // actually supporting PATCH.
        return restClientBuilder
                .baseUrl(catalogServiceUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }
}
