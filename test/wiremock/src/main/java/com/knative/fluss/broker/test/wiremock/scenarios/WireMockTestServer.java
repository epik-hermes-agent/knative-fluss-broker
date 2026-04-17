package com.knative.fluss.broker.test.wiremock.scenarios;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Manages a WireMock server for subscriber simulation in tests.
 */
public class WireMockTestServer implements AutoCloseable {

    private final WireMockServer server;

    public WireMockTestServer() {
        this.server = new WireMockServer(wireMockConfig().dynamicPort());
    }

    public void start() {
        server.start();
        WireMock.configureFor("localhost", server.port());
    }

    /** Configure the mock subscriber to return 200 OK for all POST requests. */
    public void stubSuccessfulDelivery() {
        server.stubFor(WireMock.post(WireMock.anyUrl())
            .willReturn(WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"ok\"}")));
    }

    /** Configure the mock subscriber to return 500 for all requests. */
    public void stubFailure() {
        server.stubFor(WireMock.post(WireMock.anyUrl())
            .willReturn(WireMock.aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));
    }

    /** Configure the mock subscriber to return 400 (non-retryable). */
    public void stubClientError() {
        server.stubFor(WireMock.post(WireMock.anyUrl())
            .willReturn(WireMock.aResponse()
                .withStatus(400)
                .withBody("Bad Request")));
    }

    /** Get the mock subscriber URI. */
    public String getSubscriberUri() {
        return "http://localhost:" + server.port() + "/events";
    }

    /** Get the number of requests received. */
    public int getRequestCount() {
        return server.getAllServeEvents().size();
    }

    @Override
    public void close() {
        server.stop();
    }
}
