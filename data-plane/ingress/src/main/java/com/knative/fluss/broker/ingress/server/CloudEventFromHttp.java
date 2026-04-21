package com.knative.fluss.broker.ingress.server;

import com.knative.fluss.broker.ingress.validation.CloudEventValidator;
import com.knative.fluss.broker.ingress.validation.InvalidCloudEventException;
import com.sun.net.httpserver.HttpExchange;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Parses HTTP requests into {@link CloudEvent} objects.
 *
 * <p>Supports two CloudEvents content modes over HTTP:
 * <ul>
 *   <li><b>Binary mode:</b> Event metadata in {@code ce-*} headers, raw data in body</li>
 *   <li><b>Structured mode:</b> Entire event as JSON in body with
 *       {@code Content-Type: application/cloudevents+json}</li>
 * </ul>
 */
public final class CloudEventFromHttp {

    private static final Logger log = LoggerFactory.getLogger(CloudEventFromHttp.class);

    /** Content type for structured CloudEvents JSON format. */
    private static final String CE_JSON_CONTENT_TYPE = "application/cloudevents+json";

    private CloudEventFromHttp() {}

    /**
     * Parse an HTTP exchange into a CloudEvent.
     *
     * @param exchange the HTTP exchange (reads headers and body)
     * @return the parsed CloudEvent
     * @throws InvalidCloudEventException if the request cannot be parsed into a valid CloudEvent
     * @throws IOException if reading the request body fails
     */
    public static CloudEvent parse(HttpExchange exchange) throws IOException {
        String contentType = getContentType(exchange);

        CloudEvent event;
        if (CE_JSON_CONTENT_TYPE.equals(contentType)) {
            event = parseStructured(exchange);
        } else {
            event = parseBinary(exchange);
        }

        // Validate after parsing
        CloudEventValidator.validate(event);
        return event;
    }

    // ─────────────────────────────────────────────
    // Binary mode: ce-* headers + raw body
    // ─────────────────────────────────────────────

    private static CloudEvent parseBinary(HttpExchange exchange) {
        var builder = CloudEventBuilder.v1(); // specversion is already "1.0"

        String id = getRequiredHeader(exchange, "ce-id");
        builder.withId(id);

        String type = getRequiredHeader(exchange, "ce-type");
        builder.withType(type);

        String source = getRequiredHeader(exchange, "ce-source");
        builder.withSource(URI.create(source));

        // Optional headers
        String subject = getHeader(exchange, "ce-subject");
        if (subject != null) {
            builder.withSubject(subject);
        }

        String time = getHeader(exchange, "ce-time");
        if (time != null) {
            builder.withTime(OffsetDateTime.parse(time));
        }

        String dataContentType = getHeader(exchange, "ce-datacontenttype");
        if (dataContentType == null) {
            dataContentType = getContentType(exchange);
            if (dataContentType == null) {
                dataContentType = "application/json";
            }
        }
        builder.withDataContentType(dataContentType);

        // Extension attributes: any header starting with "ce-" that isn't a core attribute
        for (var entry : exchange.getRequestHeaders().entrySet()) {
            String headerName = entry.getKey().toLowerCase();
            if (headerName.startsWith("ce-") && !isCoreAttribute(headerName)) {
                String extName = headerName.substring(3); // strip "ce-"
                List<String> values = entry.getValue();
                if (!values.isEmpty()) {
                    builder.withExtension(extName, values.get(0));
                }
            }
        }

        // Body
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) {
                builder.withData(dataContentType, body);
            }
        } catch (IOException e) {
            throw new InvalidCloudEventException("Failed to read request body: " + e.getMessage());
        }

        return builder.build();
    }

    // ─────────────────────────────────────────────
    // Structured mode: application/cloudevents+json
    // ─────────────────────────────────────────────

    private static CloudEvent parseStructured(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            throw new InvalidCloudEventException("Structured CloudEvent body must not be empty");
        }

        // Use the EventFormatProvider to get the Jackson-backed JSON format
        EventFormat format = EventFormatProvider.getInstance().resolveFormat(CE_JSON_CONTENT_TYPE);
        if (format == null) {
            throw new InvalidCloudEventException(
                "No EventFormat registered for " + CE_JSON_CONTENT_TYPE
                + ". Ensure cloudevents-json-jackson is on the classpath.");
        }

        try {
            CloudEvent event = format.deserialize(body);
            if (event == null) {
                throw new InvalidCloudEventException("Deserialized CloudEvent is null");
            }
            return event;
        } catch (InvalidCloudEventException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCloudEventException(
                "Failed to parse structured CloudEvent JSON: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private static String getContentType(HttpExchange exchange) {
        return getHeader(exchange, "Content-Type");
    }

    private static String getHeader(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static String getRequiredHeader(HttpExchange exchange, String name) {
        String value = getHeader(exchange, name);
        if (value == null || value.isBlank()) {
            throw new InvalidCloudEventException(
                "Missing required CloudEvent header: " + name);
        }
        return value;
    }

    private static boolean isCoreAttribute(String headerName) {
        return switch (headerName) {
            case "ce-specversion", "ce-id", "ce-type", "ce-source",
                 "ce-subject", "ce-time", "ce-datacontenttype" -> true;
            default -> false;
        };
    }
}
