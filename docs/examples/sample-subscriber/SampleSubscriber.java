package com.example.subscriber;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample subscriber that receives CloudEvents from the Fluss broker dispatcher.
 * Registers as a Trigger subscriber at http://localhost:9090/events
 *
 * Usage:
 *   javac SampleSubscriber.java && java SampleSubscriber
 *
 * Then create a Trigger:
 *   kubectl apply -f config/samples/trigger-order-created.yaml
 *   (update subscriber URI to http://host.docker.internal:9090/events)
 */
public class SampleSubscriber {

    private static final AtomicInteger received = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);

        server.createContext("/events", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            int count = received.incrementAndGet();

            System.out.printf("[%d] Received CloudEvent:%n%s%n---%n", count, bodyStr);

            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        server.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });

        server.start();
        System.out.println("Subscriber listening on http://localhost:9090/events");
        System.out.println("Press Ctrl+C to stop.");
    }
}
