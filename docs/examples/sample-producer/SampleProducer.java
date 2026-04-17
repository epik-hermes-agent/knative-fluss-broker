package com.example.producer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sample CloudEvent producer that sends events to the Fluss broker ingress.
 *
 * Usage:
 *   javac SampleProducer.java && java SampleProducer
 *
 * Prerequisites:
 *   - Broker running on localhost:8080
 *   - Broker resource "default" in namespace "default"
 */
public class SampleProducer {

    private static final String BROKER_URL = "http://localhost:8080/default/default";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("Sending CloudEvents to " + BROKER_URL);

        for (int i = 0; i < 10; i++) {
            sendOrderCreated(i);
            Thread.sleep(100);
        }

        System.out.println("Sent 10 events.");
    }

    private static void sendOrderCreated(int orderId) throws Exception {
        String eventId = UUID.randomUUID().toString();
        String data = String.format(
            "{\"orderId\":\"ORDER-%d\",\"customer\":\"customer-%d\",\"amount\":%.2f}",
            orderId, orderId % 5, 10.0 + orderId * 5.5);

        String cloudEventJson = String.format(
            "{\"specversion\":\"1.0\",\"id\":\"%s\",\"source\":\"/sample-producer\"," +
            "\"type\":\"com.example.order.created\",\"datacontenttype\":\"application/json\"," +
            "\"time\":\"%s\",\"subject\":\"ORDER-%d\",\"data\":%s}",
            eventId, OffsetDateTime.now().toString(), orderId, data);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BROKER_URL))
            .header("Content-Type", "application/cloudevents+json")
            .POST(HttpRequest.BodyPublishers.ofString(cloudEventJson))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.printf("Event %s: HTTP %d%n", eventId.substring(0, 8), response.statusCode());
    }
}
