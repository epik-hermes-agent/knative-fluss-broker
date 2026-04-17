package com.knative.fluss.broker.delivery.http;

import com.knative.fluss.broker.common.config.DeliveryConfig;
import com.knative.fluss.broker.common.model.Envelope;
import com.knative.fluss.broker.common.cloudevents.CloudEventToEnvelopeMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for delivering events to Knative subscribers.
 * Sends CloudEvents in structured JSON mode.
 */
public class SubscriberClient {

    private static final Logger log = LoggerFactory.getLogger(SubscriberClient.class);
    private static final MediaType JSON = MediaType.get("application/cloudevents+json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final DeliveryConfig config;

    public SubscriberClient(DeliveryConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(config.subscriberTimeoutMs()))
            .readTimeout(Duration.ofMillis(config.subscriberTimeoutMs()))
            .writeTimeout(Duration.ofMillis(config.subscriberTimeoutMs()))
            .build();
    }

    /**
     * Deliver an event envelope to a subscriber endpoint.
     *
     * @param subscriberUri the subscriber's HTTP endpoint
     * @param envelope      the event envelope to deliver
     * @return future with the delivery result
     */
    public CompletableFuture<DeliveryResult> deliver(String subscriberUri, Envelope envelope) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                CloudEvent cloudEvent = rebuildCloudEvent(envelope);
                String json = serializeCloudEvent(cloudEvent);

                Request request = new Request.Builder()
                    .url(subscriberUri)
                    .post(RequestBody.create(json, JSON))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    long latency = System.currentTimeMillis() - start;
                    String body = response.body() != null ? response.body().string() : "";

                    if (response.isSuccessful()) {
                        return DeliveryResult.success(response.code(), body, latency);
                    } else {
                        return DeliveryResult.failure(response.code(), body, latency,
                            "HTTP " + response.code());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                return DeliveryResult.timeout(System.currentTimeMillis() - start);
            } catch (Exception e) {
                return DeliveryResult.error(System.currentTimeMillis() - start, e.getMessage());
            }
        });
    }

    /** Rebuild a CloudEvent from an envelope for subscriber delivery. */
    private CloudEvent rebuildCloudEvent(Envelope envelope) {
        var builder = CloudEventBuilder.v1()
            .withId(envelope.eventId())
            .withSource(URI.create(envelope.eventSource()))
            .withType(envelope.eventType())
            .withDataContentType(envelope.contentType());

        if (envelope.eventTime() != null) {
            builder.withTime(envelope.eventTime().atOffset(java.time.ZoneOffset.UTC));
        }
        if (envelope.data() != null) {
            builder.withData(envelope.data());
        }
        // Restore extension attributes
        if (envelope.attributes() != null) {
            envelope.attributes().forEach((k, v) -> builder.withExtension(k, v));
        }

        return builder.build();
    }

    /** Serialize a CloudEvent to structured JSON. */
    private String serializeCloudEvent(CloudEvent event) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            var node = mapper.createObjectNode();
            node.put("specversion", event.getSpecVersion().toString());
            node.put("id", event.getId());
            node.put("source", event.getSource().toString());
            node.put("type", event.getType());
            if (event.getDataContentType() != null) {
                node.put("datacontenttype", event.getDataContentType());
            }
            if (event.getTime() != null) {
                node.put("time", event.getTime().toString());
            }
            if (event.getSubject() != null) {
                node.put("subject", event.getSubject());
            }
            if (event.getData() != null) {
                node.put("data", new String(event.getData().toBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String ext : event.getExtensionNames()) {
                Object val = event.getExtension(ext);
                if (val != null) node.put(ext, val.toString());
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CloudEvent", e);
        }
    }

    /** Shutdown the HTTP client. */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
