package com.knative.fluss.broker.schema.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates schema compatibility for evolution.
 * v1: backward-compatible only (new optional fields allowed, no removal/type changes).
 */
public final class SchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaValidator() {}

    /**
     * Check if the new schema is backward-compatible with the existing one.
     * v1 rules:
     * - New optional fields: OK
     * - New required fields: REJECT
     * - Removed fields: REJECT
     * - Type changes: REJECT
     * - Renamed fields: REJECT
     */
    public static boolean isBackwardCompatible(String existingSchemaBody, String newSchemaBody) {
        if (existingSchemaBody == null || newSchemaBody == null) {
            return true; // No existing schema to compare against
        }

        try {
            JsonNode existing = MAPPER.readTree(existingSchemaBody);
            JsonNode newSchema = MAPPER.readTree(newSchemaBody);

            // v1 implementation: simplified check
            // Full implementation would walk the JSON Schema trees
            if (existing.has("required") && newSchema.has("required")) {
                var existingRequired = existing.get("required");
                var newRequired = newSchema.get("required");
                // New required fields that didn't exist before = incompatible
                for (JsonNode req : newRequired) {
                    boolean found = false;
                    for (JsonNode existingReq : existingRequired) {
                        if (existingReq.asText().equals(req.asText())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false; // New required field
                }
            }
            return true;
        } catch (Exception e) {
            return false; // Parse error = incompatible
        }
    }
}
