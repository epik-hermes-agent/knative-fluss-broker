package com.knative.fluss.broker.ingress.handler;

import com.knative.fluss.broker.common.cloudevents.CloudEventToEnvelopeMapper;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.common.config.SchemaConfig;
import com.knative.fluss.broker.schema.registry.SchemaRegistry;
import com.knative.fluss.broker.schema.validation.SchemaInference;
import com.knative.fluss.broker.storage.fluss.client.FlussEventWriter;
import com.knative.fluss.broker.storage.fluss.tables.FlussTablePath;
import com.knative.fluss.broker.ingress.validation.CloudEventValidator;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Main ingress handler that processes incoming CloudEvents.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Validate CloudEvent format</li>
 *   <li>Resolve or register schema</li>
 *   <li>Build envelope with schema metadata</li>
 *   <li>Write to Fluss log table</li>
 *   <li>Return 202 Accepted</li>
 * </ol>
 */
public class IngressHandler {

    private static final Logger log = LoggerFactory.getLogger(IngressHandler.class);

    private final FlussEventWriter writer;
    private final SchemaRegistry schemaRegistry;
    private final SchemaConfig schemaConfig;
    private final FlussTablePath brokerTablePath;

    public IngressHandler(FlussEventWriter writer, SchemaRegistry schemaRegistry,
                          SchemaConfig schemaConfig, FlussTablePath brokerTablePath) {
        this.writer = writer;
        this.schemaRegistry = schemaRegistry;
        this.schemaConfig = schemaConfig;
        this.brokerTablePath = brokerTablePath;
    }

    /**
     * Handle an incoming CloudEvent.
     *
     * @param event the validated CloudEvent
     * @return future with the accepted envelope (including schema metadata)
     */
    public CompletableFuture<Envelope> handle(CloudEvent event) {
        // Step 1: Validate
        CloudEventValidator.validate(event);

        // Step 2: Build envelope
        Envelope envelope = CloudEventToEnvelopeMapper.toEnvelope(event);

        // Step 3: Resolve schema
        String inferredSchema = null;
        if (schemaConfig.enabled() && schemaConfig.autoRegister()
            && "application/json".equals(envelope.contentType()) && envelope.data() != null) {
            inferredSchema = SchemaInference.infer(envelope.data(), schemaConfig);
        }

        var schema = schemaRegistry.resolveOrRegister(
            envelope.eventType(), envelope.contentType(), inferredSchema);

        // Step 4: Enrich envelope with schema info
        Envelope enriched = new Envelope(
            envelope.eventId(), envelope.eventSource(), envelope.eventType(),
            envelope.eventTime(), envelope.contentType(), envelope.data(),
            schema.schemaId(), schema.schemaVersion(),
            envelope.attributes(), envelope.ingestionTime(), envelope.ingestionDate()
        );

        // Step 5: Write to Fluss
        return writer.write(brokerTablePath, enriched).thenApply(v -> {
            log.info("Ingress accepted event id={} type={} schema={}/{}",
                enriched.eventId(), enriched.eventType(),
                enriched.schemaId(), enriched.schemaVersion());
            return enriched;
        });
    }
}
