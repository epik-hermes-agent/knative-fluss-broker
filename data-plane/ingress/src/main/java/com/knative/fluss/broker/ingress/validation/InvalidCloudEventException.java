package com.knative.fluss.broker.ingress.validation;

/**
 * Thrown when an incoming CloudEvent fails validation.
 * Results in HTTP 400 to the event source.
 */
public class InvalidCloudEventException extends RuntimeException {
    public InvalidCloudEventException(String message) {
        super(message);
    }
}
