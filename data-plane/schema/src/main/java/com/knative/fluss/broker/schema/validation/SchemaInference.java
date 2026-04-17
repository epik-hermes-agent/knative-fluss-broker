package com.knative.fluss.broker.schema.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knative.fluss.broker.common.config.SchemaConfig;

/**
 * Infers JSON Schema from event data for auto-registration.
 */
public final class SchemaInference {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaInference() {}

    /**
     * Infer a JSON Schema from the given JSON data bytes.
     *
     * @param data     the raw JSON data
     * @param config   schema config (depth/property limits)
     * @return JSON Schema string, or null if not JSON content
     */
    public static String infer(byte[] data, SchemaConfig config) {
        if (data == null || data.length == 0) return null;

        try {
            JsonNode node = MAPPER.readTree(data);
            return inferFromNode(node, 0, config.inferenceMaxDepth()).toString();
        } catch (Exception e) {
            return null; // Not valid JSON
        }
    }

    private static JsonNode inferFromNode(JsonNode node, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return MAPPER.createObjectNode().put("type", "object");
        }

        var schema = MAPPER.createObjectNode();

        if (node.isObject()) {
            schema.put("type", "object");
            var properties = schema.putObject("properties");
            node.fieldNames().forEachRemaining(field -> {
                properties.set(field, inferFromNode(node.get(field), depth + 1, maxDepth));
            });
        } else if (node.isArray()) {
            schema.put("type", "array");
            if (node.size() > 0) {
                schema.set("items", inferFromNode(node.get(0), depth + 1, maxDepth));
            }
        } else if (node.isTextual()) {
            schema.put("type", "string");
        } else if (node.isInt() || node.isLong()) {
            schema.put("type", "integer");
        } else if (node.isNumber()) {
            schema.put("type", "number");
        } else if (node.isBoolean()) {
            schema.put("type", "boolean");
        } else if (node.isNull()) {
            schema.put("type", "null");
        }

        return schema;
    }
}
