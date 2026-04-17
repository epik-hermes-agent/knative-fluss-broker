package com.knative.fluss.broker.common.cloudevents;

import com.knative.fluss.broker.common.model.Envelope;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.data.BytesCloudEventData;
import io.cloudevents.core.data.PojoCloudEventData;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a {@link CloudEvent} to the internal {@link Envelope} representation.
 */
public final class CloudEventToEnvelopeMapper {

    private CloudEventToEnvelopeMapper() {}

    /**
     * Convert a CloudEvent to an Envelope for storage in Fluss.
     *
     * @param event the incoming CloudEvent
     * @return the envelope ready for Fluss write
     */
    public static Envelope toEnvelope(CloudEvent event) {
        var builder = Envelope.builder()
            .eventId(event.getId())
            .eventSource(event.getSource().toString())
            .eventType(event.getType())
            .contentType(event.getDataContentType() != null
                ? event.getDataContentType() : "application/json");

        if (event.getTime() != null) {
            builder.eventTime(event.getTime().toInstant());
        }

        if (event.getData() != null) {
            var data = event.getData();
            if (data instanceof BytesCloudEventData bytesData) {
                builder.data(bytesData.toBytes());
            } else if (data instanceof PojoCloudEventData<?> pojoData) {
                builder.data(pojoData.getValue().toString().getBytes());
            } else {
                builder.data(data.toBytes());
            }
        }

        // Map extension attributes
        Map<String, String> attrs = new HashMap<>();
        for (String extName : event.getExtensionNames()) {
            Object extValue = event.getExtension(extName);
            if (extValue != null) {
                attrs.put(extName, extValue.toString());
            }
        }
        builder.attributes(attrs);

        return builder.build();
    }
}
