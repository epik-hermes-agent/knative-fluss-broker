package com.knative.fluss.broker.dispatcher.dlq;

import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqHandlerTest {

    @Mock FlussEventWriter writer;

    private final FlussTablePath dlqPath = FlussTablePath.dlqTable("default", "broker1", "trigger1");

    @Test
    void sendToDlqShouldCallWriterWithCorrectArgs() {
        when(writer.writeToDlq(any(), any(), anyString(), anyInt(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));

        var handler = new DlqHandler(writer, dlqPath);
        var envelope = Envelope.builder()
            .eventId("evt-123").eventSource("/src").eventType("t").build();

        handler.sendToDlq(envelope, "retries_exhausted", 3, "Max retry attempts reached");

        verify(writer).writeToDlq(
            eq(dlqPath), eq(envelope),
            eq("retries_exhausted"), eq(3), eq("Max retry attempts reached"));
    }

    @Test
    void sendToDlqShouldNotThrowOnWriterFailure() {
        when(writer.writeToDlq(any(), any(), anyString(), anyInt(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DLQ write failed")));

        var handler = new DlqHandler(writer, dlqPath);
        var envelope = Envelope.builder()
            .eventId("evt-456").eventSource("/src").eventType("t").build();

        // Should NOT throw — exception is handled by .exceptionally()
        handler.sendToDlq(envelope, "non_retryable_client_error", 1, "HTTP 400");
    }

    @Test
    void sendToDlqShouldPassNullLastErrorWhenProvided() {
        when(writer.writeToDlq(any(), any(), anyString(), anyInt(), isNull()))
            .thenReturn(CompletableFuture.completedFuture(null));

        var handler = new DlqHandler(writer, dlqPath);
        var envelope = Envelope.builder()
            .eventId("evt-789").eventSource("/src").eventType("t").build();

        handler.sendToDlq(envelope, "unknown", 1, null);

        verify(writer).writeToDlq(eq(dlqPath), eq(envelope), eq("unknown"), eq(1), isNull());
    }
}
