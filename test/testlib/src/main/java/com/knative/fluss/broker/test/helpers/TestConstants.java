package com.knative.fluss.broker.test.helpers;

/**
 * Shared test constants.
 */
public final class TestConstants {

    private TestConstants() {}

    public static final String TEST_NAMESPACE = "test-ns";
    public static final String TEST_BROKER = "test-broker";
    public static final String TEST_TRIGGER = "test-trigger";
    public static final String TEST_SUBSCRIBER_URI = "http://localhost:18080/events";
    public static final String TEST_EVENT_TYPE = "com.example.test.event";
    public static final String TEST_EVENT_SOURCE = "/test/source";
}
