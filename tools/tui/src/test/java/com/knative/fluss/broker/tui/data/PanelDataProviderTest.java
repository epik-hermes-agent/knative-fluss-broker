package com.knative.fluss.broker.tui.data;

import com.knative.fluss.broker.tui.model.EventRecord;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PanelDataProviderTest {

    private FlussTableScanner mockScanner;
    private PanelDataProvider provider;

    @BeforeEach
    void setUp() {
        mockScanner = mock(FlussTableScanner.class);
        when(mockScanner.isReachable()).thenReturn(true);
        // Default stubs: all scanner methods return empty/null
        when(mockScanner.scanRecentEvents(any(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mockScanner.readAllFromBeginning(any(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mockScanner.getRowCount(any()))
                .thenReturn(0L);
        when(mockScanner.queryIcebergTiering(anyString(), anyString(), anyString()))
                .thenReturn(null);
        provider = new PanelDataProvider(
                mockScanner, "http://localhost:8181", "warehouse",
                "knative_default", "broker_default", 10000, null
        );
    }

    @Test
    void defaultState_shouldReturnEmptyLists() {
        // Then — before any refresh, all lists are empty
        assertThat(provider.getRecentEvents()).isEmpty();
        assertThat(provider.getDlqEntries()).isEmpty();
        assertThat(provider.getTieredEvents()).isEmpty();
        assertThat(provider.getTriggerStates()).isEmpty();
        assertThat(provider.getLastError()).isNull();
        assertThat(provider.isFlussReachable()).isTrue();
        assertThat(provider.isGatewayReachable()).isTrue();
    }

    @Test
    void forceRefresh_shouldAggregateRecentEvents() {
        // Given — return event from readAllFromBeginning (first code path)
        var envelope = createEnvelope("evt-1", "test.type");
        doReturn(List.of(envelope)).when(mockScanner)
                .readAllFromBeginning(any(), eq("dashboard"), anyInt());

        // When
        provider.forceRefresh();

        // Then
        assertThat(provider.getRecentEvents()).hasSize(1);
        assertThat(provider.getRecentEvents().get(0).eventId()).isEqualTo("evt-1");
        assertThat(provider.getRecentEvents().get(0).eventType()).isEqualTo("test.type");
    }

    @Test
    void forceRefresh_shouldAggregateDlqEntries() {
        // Given
        var envelope = createEnvelope("evt-dlq-1", "test.type");
        doReturn(List.of(envelope)).when(mockScanner)
                .readAllFromBeginning(any(), eq("dlq-dashboard"), anyInt());

        // When
        provider.forceRefresh();

        // Then
        assertThat(provider.getDlqEntries()).hasSize(1);
        assertThat(provider.getDlqEntries().get(0).eventId()).isEqualTo("evt-dlq-1");
        assertThat(provider.getDlqEntries().get(0).dlqAttempts()).isEqualTo(1);
    }

    @Test
    void forceRefresh_withScannerError_shouldCaptureLastError() {
        // Given — make scanner throw
        doThrow(new RuntimeException("Fluss unreachable")).when(mockScanner)
                .readAllFromBeginning(any(), anyString(), anyInt());

        // When
        provider.forceRefresh();

        // Then
        assertThat(provider.getLastError()).isEqualTo("Fluss unreachable");
    }

    @Test
    void forceRefresh_shouldDeduplicateEventIds() {
        // Given — same event ID appears in recent events (which deduplicates)
        var envelope1 = createEnvelope("evt-dup", "type.a");
        var envelope2 = createEnvelope("evt-dup", "type.b");
        // readAllFromBeginning returns 2 events with same ID
        doReturn(List.of(envelope1, envelope2)).when(mockScanner)
                .readAllFromBeginning(any(), eq("dashboard"), anyInt());

        // When
        provider.forceRefresh();

        // Then — recentEvents should be deduplicated to 1
        assertThat(provider.getRecentEvents()).hasSize(1);
        assertThat(provider.getRecentEvents().get(0).eventId()).isEqualTo("evt-dup");
    }

    @Test
    void close_shouldNotThrow() {
        // When / Then — no exception
        provider.close();
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static Envelope createEnvelope(String eventId, String eventType) {
        var now = Instant.now();
        return new Envelope(
                eventId,
                "test.source",
                eventType,
                now,
                "application/json",
                "{\"test\":true}".getBytes(),
                null, null, Map.of(),
                now,
                now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        );
    }
}
