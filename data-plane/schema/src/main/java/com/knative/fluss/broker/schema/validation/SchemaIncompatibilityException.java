package com.knative.fluss.broker.schema.validation;

/**
 * Thrown when a schema evolution is not backward-compatible.
 */
public class SchemaIncompatibilityException extends RuntimeException {
    public SchemaIncompatibilityException(String message) {
        super(message);
    }
}
