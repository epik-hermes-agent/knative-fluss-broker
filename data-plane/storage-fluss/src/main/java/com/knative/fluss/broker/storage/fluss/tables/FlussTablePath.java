package com.knative.fluss.broker.storage.fluss.tables;

/**
 * Represents the Fluss namespace and table path for a broker instance.
 * 
 * <p>Convention:
 * <ul>
 *   <li>Database: {@code knative_{namespace}}</li>
 *   <li>Table: {@code broker_{brokerName}}</li>
 *   <li>DLQ Table: {@code dlq_{brokerName}_{triggerName}}</li>
 *   <li>Schema Registry Table: {@code schema_registry}</li>
 * </ul>
 */
public record FlussTablePath(
    String database,
    String table
) {
    /** Full qualified path for Fluss client: "database.table" */
    public String fullPath() {
        return database + "." + table;
    }

    /** Create the main broker event table path. */
    public static FlussTablePath brokerTable(String namespace, String brokerName) {
        return new FlussTablePath(
            "knative_" + sanitize(namespace),
            "broker_" + sanitize(brokerName)
        );
    }

    /** Create the DLQ table path for a trigger. */
    public static FlussTablePath dlqTable(String namespace, String brokerName, String triggerName) {
        return new FlussTablePath(
            "knative_" + sanitize(namespace),
            "dlq_" + sanitize(brokerName) + "_" + sanitize(triggerName)
        );
    }

    /** Create the schema registry table path. */
    public static FlussTablePath schemaRegistry(String namespace) {
        return new FlussTablePath(
            "knative_" + sanitize(namespace),
            "schema_registry"
        );
    }

    public static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
