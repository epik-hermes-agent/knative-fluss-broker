package com.knative.fluss.broker.common.model;

import java.util.Map;

/**
 * Evaluates trigger filters against an event envelope.
 * v1: exact-match attribute filters with prefix wildcard support.
 */
public final class EventFilter {

    private EventFilter() {}

    /**
     * Check if an envelope matches the given filter attributes.
     * All attributes must match (AND logic).
     *
     * @param envelope         the event to test
     * @param filterAttributes the trigger's filter criteria
     * @return true if the event matches
     */
    public static boolean matches(Envelope envelope, Map<String, String> filterAttributes) {
        if (filterAttributes == null || filterAttributes.isEmpty()) {
            return true; // No filter = match all
        }

        for (var entry : filterAttributes.entrySet()) {
            String filterKey = entry.getKey();
            String filterValue = entry.getValue();

            String actualValue = resolveAttribute(envelope, filterKey);
            if (actualValue == null || !matchesFilter(actualValue, filterValue)) {
                return false;
            }
        }
        return true;
    }

    private static String resolveAttribute(Envelope envelope, String key) {
        return switch (key) {
            case "type" -> envelope.eventType();
            case "source" -> envelope.eventSource();
            default -> {
                if (key.startsWith("ext_")) {
                    yield envelope.attributes().get(key.substring(4));
                }
                yield envelope.attributes().get(key);
            }
        };
    }

    /**
     * Match a value against a filter pattern.
     * Supports exact match and suffix wildcard (e.g., "/myapp/*").
     */
    public static boolean matchesFilter(String actual, String pattern) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return actual.startsWith(prefix);
        }
        return actual.equals(pattern);
    }
}
