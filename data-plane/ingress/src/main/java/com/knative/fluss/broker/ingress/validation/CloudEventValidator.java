package com.knative.fluss.broker.ingress.validation;

import io.cloudevents.CloudEvent;

/**
 * Validates CloudEvents for required attributes before ingestion.
 */
public final class CloudEventValidator {

    private CloudEventValidator() {}

    /**
     * Validate that a CloudEvent has all required attributes.
     *
     * @throws InvalidCloudEventException if validation fails
     */
    public static void validate(CloudEvent event) {
        if (event == null) {
            throw new InvalidCloudEventException("CloudEvent must not be null");
        }
        if (event.getId() == null || event.getId().isBlank()) {
            throw new InvalidCloudEventException("CloudEvent 'id' is required");
        }
        if (event.getSource() == null) {
            throw new InvalidCloudEventException("CloudEvent 'source' is required");
        }
        if (event.getType() == null || event.getType().isBlank()) {
            throw new InvalidCloudEventException("CloudEvent 'type' is required");
        }
        if (event.getSpecVersion() == null || !"1.0".equals(event.getSpecVersion().toString())) {
            throw new InvalidCloudEventException(
                "CloudEvent 'specversion' must be '1.0', got: " + event.getSpecVersion());
        }
    }
}
