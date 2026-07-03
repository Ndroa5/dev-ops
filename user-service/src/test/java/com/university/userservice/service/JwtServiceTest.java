package com.university.userservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-at-least-32-bytes-long-for-hmac-sha";

    @Test
    void generatesTokenThatIsValidAndCarriesSubject() {
        JwtService jwtService = new JwtService(TEST_SECRET, 60_000);

        String token = jwtService.generateToken("alice", "USER");

        assertThat(token).isNotBlank();
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void expiredTokenIsInvalid() throws InterruptedException {
        JwtService jwtService = new JwtService(TEST_SECRET, 1);

        String token = jwtService.generateToken("alice", "USER");
        Thread.sleep(10);

        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    void malformedTokenIsInvalid() {
        JwtService jwtService = new JwtService(TEST_SECRET, 60_000);

        assertThat(jwtService.isTokenValid("not-a-real-token")).isFalse();
    }

    @Test
    void tokenSignedWithDifferentSecretIsInvalid() {
        JwtService issuer = new JwtService(TEST_SECRET, 60_000);
        JwtService verifier = new JwtService("a-completely-different-secret-key-of-sufficient-length", 60_000);

        String token = issuer.generateToken("alice", "USER");

        assertThat(verifier.isTokenValid(token)).isFalse();
    }
}
