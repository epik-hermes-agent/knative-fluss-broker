package com.knative.fluss.broker.test.containers.fluss;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers wrapper for Apache Fluss.
 * Provides a single-node Fluss cluster for integration testing.
 */
public class FlussTestContainer extends GenericContainer<FlussTestContainer> {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/fluss:0.9.0-incubating");
    private static final int FLUSS_PORT = 9123;

    public FlussTestContainer() {
        super(IMAGE);
        withExposedPorts(FLUSS_PORT);
        withEnv("FLUSS_NODE_TYPE", "coordinator,tablet");
        withStartupTimeout(java.time.Duration.ofSeconds(120));
    }

    /** Get the Fluss endpoint in "fluss://host:port" format. */
    public String getFlussEndpoint() {
        return "fluss://" + getHost() + ":" + getMappedPort(FLUSS_PORT);
    }
}
