package com.knative.fluss.broker.delivery.http;

/**
 * Result of a delivery attempt to a subscriber.
 */
public record DeliveryResult(
    boolean success,
    int statusCode,
    String responseBody,
    long latencyMs,
    String error
) {
    public static DeliveryResult success(int statusCode, String body, long latencyMs) {
        return new DeliveryResult(true, statusCode, body, latencyMs, null);
    }

    public static DeliveryResult failure(int statusCode, String body, long latencyMs, String error) {
        return new DeliveryResult(false, statusCode, body, latencyMs, error);
    }

    public static DeliveryResult timeout(long latencyMs) {
        return new DeliveryResult(false, 0, null, latencyMs, "timeout");
    }

    public static DeliveryResult error(long latencyMs, String error) {
        return new DeliveryResult(false, 0, null, latencyMs, error);
    }

    /** Whether this status code is retryable (5xx or timeout). */
    public boolean isRetryable() {
        return statusCode >= 500 || statusCode == 0; // 0 = timeout/connection error
    }

    /** Whether this is a non-retryable client error (4xx). */
    public boolean isNonRetryableClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
}
