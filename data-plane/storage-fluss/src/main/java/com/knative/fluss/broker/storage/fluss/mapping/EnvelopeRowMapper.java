package com.knative.fluss.broker.storage.fluss.mapping;

import com.knative.fluss.broker.common.model.Envelope;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.GenericArray;
import org.apache.fluss.row.GenericMap;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.row.InternalMap;
import org.apache.fluss.row.TimestampLtz;
import org.apache.fluss.row.TimestampNtz;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps between {@link Envelope} objects and Fluss {@link GenericRow} representations.
 *
 * <p>Schema (matching the Fluss DDL in {@code FlussTableManager}):
 * <pre>
 *   0: event_id        STRING
 *   1: event_source    STRING
 *   2: event_type      STRING
 *   3: event_time      TIMESTAMP(3)           — nullable
 *   4: content_type    STRING
 *   5: data            BYTES                  — nullable
 *   6: schema_id       INT                    — nullable
 *   7: schema_version  INT                    — nullable
 *   8: attributes      MAP&lt;STRING, STRING&gt;    — nullable
 *   9: ingestion_time  TIMESTAMP_LTZ(3)
 *  10: ingestion_date  DATE
 * </pre>
 */
public final class EnvelopeRowMapper {

    /** Number of columns in the envelope schema. */
    public static final int COLUMN_COUNT = 11;

    // Column indices
    public static final int COL_EVENT_ID = 0;
    public static final int COL_EVENT_SOURCE = 1;
    public static final int COL_EVENT_TYPE = 2;
    public static final int COL_EVENT_TIME = 3;
    public static final int COL_CONTENT_TYPE = 4;
    public static final int COL_DATA = 5;
    public static final int COL_SCHEMA_ID = 6;
    public static final int COL_SCHEMA_VERSION = 7;
    public static final int COL_ATTRIBUTES = 8;
    public static final int COL_INGESTION_TIME = 9;
    public static final int COL_INGESTION_DATE = 10;

    private EnvelopeRowMapper() {}

    /**
     * Convert an {@link Envelope} to a Fluss {@link GenericRow} for writing.
     *
     * @param envelope the envelope to convert
     * @return a GenericRow with all 11 columns populated
     */
    public static GenericRow toGenericRow(Envelope envelope) {
        GenericRow row = new GenericRow(COLUMN_COUNT);

        // 0: event_id
        row.setField(COL_EVENT_ID, BinaryString.fromString(envelope.eventId()));
        // 1: event_source
        row.setField(COL_EVENT_SOURCE, BinaryString.fromString(envelope.eventSource()));
        // 2: event_type
        row.setField(COL_EVENT_TYPE, BinaryString.fromString(envelope.eventType()));
        // 3: event_time (nullable)
        row.setField(COL_EVENT_TIME, envelope.eventTime() != null
                ? toTimestampNtz(envelope.eventTime())
                : null);
        // 4: content_type
        row.setField(COL_CONTENT_TYPE, BinaryString.fromString(envelope.contentType()));
        // 5: data (nullable)
        row.setField(COL_DATA, envelope.data());
        // 6: schema_id (nullable)
        row.setField(COL_SCHEMA_ID, envelope.schemaId());
        // 7: schema_version (nullable)
        row.setField(COL_SCHEMA_VERSION, envelope.schemaVersion());
        // 8: attributes (nullable — empty map becomes null for Fluss MAP type)
        row.setField(COL_ATTRIBUTES, toFlussMap(envelope.attributes()));
        // 9: ingestion_time
        row.setField(COL_INGESTION_TIME, toTimestampLtz(envelope.ingestionTime()));
        // 10: ingestion_date
        row.setField(COL_INGESTION_DATE, toIntDate(envelope.ingestionDate()));

        return row;
    }

    /**
     * Convert a Fluss {@link GenericRow} back to an {@link Envelope}.
     *
     * @param row the GenericRow read from Fluss
     * @return the reconstructed Envelope
     */
    public static Envelope fromGenericRow(GenericRow row) {
        return Envelope.builder()
                .eventId(row.getString(COL_EVENT_ID).toString())
                .eventSource(row.getString(COL_EVENT_SOURCE).toString())
                .eventType(row.getString(COL_EVENT_TYPE).toString())
                .eventTime(row.isNullAt(COL_EVENT_TIME)
                        ? null
                        : fromTimestampNtz(row.getTimestampNtz(COL_EVENT_TIME, 3)))
                .contentType(row.getString(COL_CONTENT_TYPE).toString())
                .data(row.isNullAt(COL_DATA) ? null : row.getBytes(COL_DATA))
                .schemaId(row.isNullAt(COL_SCHEMA_ID) ? null : row.getInt(COL_SCHEMA_ID))
                .schemaVersion(row.isNullAt(COL_SCHEMA_VERSION) ? null : row.getInt(COL_SCHEMA_VERSION))
                .attributes(fromFlussMap(row, COL_ATTRIBUTES))
                .ingestionTime(fromTimestampLtz(row.getTimestampLtz(COL_INGESTION_TIME, 3)))
                .ingestionDate(fromIntDate(row.getInt(COL_INGESTION_DATE)))
                .build();
    }

    /**
     * Convert an {@link org.apache.fluss.row.InternalRow} (from LogScanner) to an Envelope.
     * InternalRow doesn't have getField(), so we use type-specific getters.
     */
    public static Envelope fromInternalRow(org.apache.fluss.row.InternalRow row) {
        Envelope.Builder builder = Envelope.builder()
                .eventId(row.getString(COL_EVENT_ID).toString())
                .eventSource(row.getString(COL_EVENT_SOURCE).toString())
                .eventType(row.getString(COL_EVENT_TYPE).toString())
                .contentType(row.getString(COL_CONTENT_TYPE).toString());

        // Nullable fields
        if (!row.isNullAt(COL_EVENT_TIME)) {
            builder.eventTime(fromTimestampNtz(row.getTimestampNtz(COL_EVENT_TIME, 3)));
        }
        if (!row.isNullAt(COL_DATA)) {
            builder.data(row.getBytes(COL_DATA));
        }
        if (!row.isNullAt(COL_SCHEMA_ID)) {
            builder.schemaId(row.getInt(COL_SCHEMA_ID));
        }
        if (!row.isNullAt(COL_SCHEMA_VERSION)) {
            builder.schemaVersion(row.getInt(COL_SCHEMA_VERSION));
        }
        if (!row.isNullAt(COL_ATTRIBUTES)) {
            builder.attributes(fromInternalMap(row.getMap(COL_ATTRIBUTES)));
        }

        builder.ingestionTime(fromTimestampLtz(row.getTimestampLtz(COL_INGESTION_TIME, 3)));
        builder.ingestionDate(fromIntDate(row.getInt(COL_INGESTION_DATE)));

        return builder.build();
    }

    // ─────────────────────────────────────────────
    // Fluss ↔ Java type conversions
    // ─────────────────────────────────────────────

    private static TimestampNtz toTimestampNtz(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return TimestampNtz.fromLocalDateTime(ldt);
    }

    private static Instant fromTimestampNtz(TimestampNtz ts) {
        if (ts == null) return null;
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static TimestampLtz toTimestampLtz(Instant instant) {
        return TimestampLtz.fromEpochMillis(
                instant.getEpochSecond() * 1000 + instant.getNano() / 1_000_000);
    }

    private static Instant fromTimestampLtz(TimestampLtz ts) {
        if (ts == null) return null;
        return Instant.ofEpochMilli(ts.getEpochMillisecond());
    }

    private static int toIntDate(LocalDate date) {
        return (int) date.toEpochDay();
    }

    private static LocalDate fromIntDate(int epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    /**
     * Convert a Java Map to a Fluss GenericMap for MAP&lt;STRING, STRING&gt; columns.
     * Returns null for empty/null maps.
     */
    private static GenericMap toFlussMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Map<BinaryString, BinaryString> flussMap = new LinkedHashMap<>(map.size());
        for (var entry : map.entrySet()) {
            flussMap.put(
                    BinaryString.fromString(entry.getKey()),
                    BinaryString.fromString(entry.getValue())
            );
        }
        return new GenericMap(flussMap);
    }

    /**
     * Extract a Java Map from a Fluss GenericRow MAP column.
     */
    private static Map<String, String> fromFlussMap(GenericRow row, int colIndex) {
        if (row.isNullAt(colIndex)) {
            return Map.of();
        }
        InternalMap mapValue = (InternalMap) row.getField(colIndex);
        return fromInternalMap(mapValue);
    }

    /**
     * Convert a Fluss InternalMap to a Java Map&lt;String, String&gt;.
     */
    private static Map<String, String> fromInternalMap(InternalMap internalMap) {
        if (internalMap == null || internalMap.size() == 0) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>(internalMap.size());
        var keyArray = internalMap.keyArray();
        var valueArray = internalMap.valueArray();
        for (int i = 0; i < internalMap.size(); i++) {
            String key = keyArray.getString(i).toString();
            String val = valueArray.isNullAt(i) ? null : valueArray.getString(i).toString();
            result.put(key, val);
        }
        return Map.copyOf(result);
    }
}
