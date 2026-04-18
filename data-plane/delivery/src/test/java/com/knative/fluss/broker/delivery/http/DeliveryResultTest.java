package com.knative.fluss.broker.delivery.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DeliveryResultTest {

    @Test
    void successResultShouldContainStatusCodeAndBody() {
        var result = DeliveryResult.success(200, "OK", 42);

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.responseBody()).isEqualTo("OK");
        assertThat(result.latencyMs()).isEqualTo(42);
        assertThat(result.error()).isNull();
    }

    @Test
    void failureResultShouldContainError() {
        var result = DeliveryResult.failure(503, "Service Unavailable", 100, "HTTP 503");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(result.responseBody()).isEqualTo("Service Unavailable");
        assertThat(result.latencyMs()).isEqualTo(100);
        assertThat(result.error()).isEqualTo("HTTP 503");
    }

    @Test
    void isNonRetryableClientErrorShouldReturnTrueFor4xx() {
        assertThat(DeliveryResult.failure(400, "", 10, "HTTP 400").isNonRetryableClientError()).isTrue();
        assertThat(DeliveryResult.failure(404, "", 10, "HTTP 404").isNonRetryableClientError()).isTrue();
        assertThat(DeliveryResult.failure(422, "", 10, "HTTP 422").isNonRetryableClientError()).isTrue();
    }

    @Test
    void isNonRetryableClientErrorShouldReturnFalseFor5xx() {
        assertThat(DeliveryResult.failure(500, "", 10, "HTTP 500").isNonRetryableClientError()).isFalse();
        assertThat(DeliveryResult.failure(502, "", 10, "HTTP 502").isNonRetryableClientError()).isFalse();
        assertThat(DeliveryResult.failure(503, "", 10, "HTTP 503").isNonRetryableClientError()).isFalse();
    }

    @Test
    void timeoutResultShouldHaveZeroStatusCode() {
        var result = DeliveryResult.timeout(5000);

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(0);
        assertThat(result.latencyMs()).isEqualTo(5000);
        assertThat(result.error()).isEqualTo("timeout");
    }

    @Test
    void errorResultShouldContainErrorMessage() {
        var result = DeliveryResult.error(200, "Connection refused");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(0);
        assertThat(result.error()).isEqualTo("Connection refused");
        assertThat(result.latencyMs()).isEqualTo(200);
    }

    @Test
    void successShouldNotBeRetryableNorClientError() {
        var result = DeliveryResult.success(200, "OK", 10);

        assertThat(result.isNonRetryableClientError()).isFalse();
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void isRetryableShouldReturnTrueFor5xxAndTimeout() {
        assertThat(DeliveryResult.failure(500, "", 10, "HTTP 500").isRetryable()).isTrue();
        assertThat(DeliveryResult.failure(502, "", 10, "HTTP 502").isRetryable()).isTrue();
        assertThat(DeliveryResult.timeout(1000).isRetryable()).isTrue();
        assertThat(DeliveryResult.failure(400, "", 10, "HTTP 400").isRetryable()).isFalse();
    }
}
