package com.knative.fluss.broker.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Shared formatting utilities for the TUI dashboard.
 */
public final class FormatUtils {

    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private FormatUtils() {}

    /**
     * Truncate a string to maxLen characters, adding "…" if truncated.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }

    /**
     * Pretty-print JSON if possible, otherwise return the raw string.
     */
    public static String prettyPrint(String content) {
        if (content == null || content.isBlank()) return "(empty)";
        try {
            var parsed = PRETTY_MAPPER.readTree(content);
            return PRETTY_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            // Not JSON — return as-is
            return content;
        }
    }

    /**
     * Format an attributes map as "key1=val1, key2=val2".
     */
    public static String formatAttributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : attrs.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Format an Instant as "HH:mm:ss.SSS".
     */
    public static String fmtTime(Instant instant) {
        if (instant == null) return "—";
        return TIME_FMT.format(instant.atZone(ZoneId.systemDefault()));
    }

    /**
     * Format an Instant as "yyyy-MM-dd HH:mm:ss".
     */
    public static String fmtDateTime(Instant instant) {
        if (instant == null) return "—";
        return DATE_TIME_FMT.format(instant.atZone(ZoneId.systemDefault()));
    }

    /**
     * Format a LocalDate as "yyyy-MM-dd".
     */
    public static String fmtDate(LocalDate date) {
        if (date == null) return "—";
        return date.toString();
    }

    /**
     * Convert any object to string, handling nulls.
     */
    public static String str(Object o) {
        return o == null ? "—" : o.toString();
    }
}
