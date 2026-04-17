package com.knative.fluss.broker.schema.model;

import java.time.Instant;

/**
 * Represents a registered event data schema in the schema registry.
 * Each schema is uniquely identified by (eventType, contentType, schemaVersion).
 */
public record Schema(
    int schemaId,
    String eventType,
    String contentType,
    int schemaVersion,
    String schemaBody,
    Instant createdAt
) {
    /** Create a new schema with the given ID and version 1. */
    public static Schema initial(int schemaId, String eventType, String contentType, String schemaBody) {
        return new Schema(schemaId, eventType, contentType, 1, schemaBody, Instant.now());
    }

    /** Create a new version of an existing schema. */
    public Schema nextVersion(int newSchemaId, String newSchemaBody) {
        return new Schema(newSchemaId, eventType, contentType, schemaVersion + 1, newSchemaBody, Instant.now());
    }
}
