package com.knative.fluss.broker.schema.registry;

import com.knative.fluss.broker.schema.model.Schema;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.schema.validation.SchemaIncompatibilityException;
import com.knative.fluss.broker.schema.validation.SchemaValidator;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded schema registry for managing CloudEvent data schemas.
 * Backed by a Fluss table for persistence, with in-memory cache for fast lookup.
 */
public class SchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    private final SchemaConfig config;
    private final FlussTablePath registryTablePath;
    private final AtomicInteger nextSchemaId = new AtomicInteger(1);

    // Cache: "eventType::contentType" -> latest Schema
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    public SchemaRegistry(SchemaConfig config, FlussTablePath registryTablePath) {
        this.config = config;
        this.registryTablePath = registryTablePath;
    }

    /**
     * Resolve or register a schema for the given event type and content type.
     * If the schema is known, returns the existing (schemaId, schemaVersion).
     * If auto-register is enabled and the schema is new, registers it.
     *
     * @return the resolved or newly registered schema
     */
    public Schema resolveOrRegister(String eventType, String contentType, String inferredSchema) {
        if (!config.enabled()) {
            // Schema registry disabled — return synthetic schema with nulls
            return new Schema(0, eventType, contentType, 0, null, null);
        }

        String key = eventType + "::" + contentType;
        Schema existing = cache.get(key);

        if (existing != null) {
            if (config.enforce()) {
                // Check compatibility (v1: backward-compatible only)
                boolean compatible = SchemaValidator.isBackwardCompatible(
                    existing.schemaBody(), inferredSchema);
                if (!compatible) {
                    throw new SchemaIncompatibilityException(
                        "Schema evolution incompatible for " + key);
                }
            }
            // If evolved, register new version
            if (inferredSchema != null && !inferredSchema.equals(existing.schemaBody())) {
                return registerNewVersion(existing, inferredSchema);
            }
            return existing;
        }

        // New schema — auto-register if enabled
        if (config.autoRegister()) {
            return registerNew(eventType, contentType, inferredSchema);
        }

        // Return without schema info
        return new Schema(0, eventType, contentType, 0, null, null);
    }

    /**
     * Look up a schema by its ID and version.
     */
    public Optional<Schema> lookup(int schemaId, int schemaVersion) {
        return cache.values().stream()
            .filter(s -> s.schemaId() == schemaId && s.schemaVersion() == schemaVersion)
            .findFirst();
    }

    /**
     * Look up the latest schema for an event type and content type.
     */
    public Optional<Schema> lookupLatest(String eventType, String contentType) {
        return Optional.ofNullable(cache.get(eventType + "::" + contentType));
    }

    private Schema registerNew(String eventType, String contentType, String schemaBody) {
        int id = nextSchemaId.getAndIncrement();
        Schema schema = Schema.initial(id, eventType, contentType, schemaBody);
        String key = eventType + "::" + contentType;
        cache.put(key, schema);
        log.info("Registered new schema id={} for {}", id, key);
        // Persist to Fluss: INSERT INTO schema_registry ...
        return schema;
    }

    private Schema registerNewVersion(Schema existing, String newSchemaBody) {
        int id = nextSchemaId.getAndIncrement();
        Schema schema = existing.nextVersion(id, newSchemaBody);
        String key = existing.eventType() + "::" + existing.contentType();
        cache.put(key, schema);
        log.info("Registered schema version {} (id={}) for {}", schema.schemaVersion(), id, key);
        // Persist to Fluss
        return schema;
    }
}
