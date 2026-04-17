package com.knative.fluss.broker.test.containers.fluss;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Testcontainers wrapper for ZooKeeper.
 * Required as a prerequisite for Fluss coordinator.
 *
 * <p>ZooKeeper is used by Fluss for leader election, partition coordination,
 * and distributed locking. A single ZooKeeper instance is sufficient for
 * integration testing.
 */
public class ZooKeeperTestContainer extends GenericContainer<ZooKeeperTestContainer> {

    private static final DockerImageName IMAGE = DockerImageName.parse("zookeeper:3.9");
    private static final int ZOOKEEPER_CLIENT_PORT = 2181;

    public ZooKeeperTestContainer() {
        super(IMAGE);
        withExposedPorts(ZOOKEEPER_CLIENT_PORT);
        withEnv("ZOOKEEPER_CLIENT_PORT", String.valueOf(ZOOKEEPER_CLIENT_PORT));
        withEnv("ZOOKEEPER_TICK_TIME", "2000");
        // Wait for the client port to be listening
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        withStartupTimeout(Duration.ofSeconds(60));
    }

    /**
     * Get the ZooKeeper connection string (host:port) for Fluss configuration.
     */
    public String getZookeeperAddress() {
        return getHost() + ":" + getMappedPort(ZOOKEEPER_CLIENT_PORT);
    }
}
