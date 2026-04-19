package com.knative.fluss.broker.test.containers.fluss;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Testcontainers wrapper for Apache Fluss coordinator.
 * Provides a single-node Fluss cluster (coordinator) for integration testing.
 *
 * <p>The Fluss image uses a docker-entrypoint.sh that accepts server commands:
 * {@code fluss coordinatorServer} or {@code fluss tabletServer}.
 * This container starts a coordinator server. It requires ZooKeeper to be
 * running and accessible at the address provided via {@link #withZookeeper(String)}.
 *
 * <p><b>Important:</b> Do NOT add a default {@code waitingFor()} strategy in the
 * constructor. The wait must be applied AFTER {@link #withZookeeper(String)} is
 * called, because the ZooKeeper address is needed for Fluss to start properly.
 * Use {@link FlussZooKeeperCluster} which handles the correct startup ordering.
 */
public class FlussTestContainer extends GenericContainer<FlussTestContainer> {

    private static final DockerImageName IMAGE = DockerImageName.parse("apache/fluss:1.0-SNAPSHOT");
    private static final int FLUSS_PORT = 9123;

    public FlussTestContainer() {
        super(IMAGE);
        withExposedPorts(FLUSS_PORT);
        // The Fluss image entrypoint requires a command argument
        withCommand("coordinatorServer");
        // NO default waitingFor here — must be set after withZookeeper() is called.
        // FlussZooKeeperCluster handles this by setting wait strategy after ZK config.
        withStartupTimeout(Duration.ofSeconds(180));
    }

    /**
     * Set the ZooKeeper connection string for Fluss coordinator configuration.
     * This injects the required {@code zookeeper.address} into Fluss's server.yaml.
     *
     * <p>This also sets the wait strategy to check for Fluss port readiness,
     * since Fluss can now actually start (ZK address is configured).
     *
     * @param zookeeperAddress ZooKeeper address in "host:port" format
     * @return this container for chaining
     */
    public FlussTestContainer withZookeeper(String zookeeperAddress) {
        return withFlussProperties(zookeeperAddress);
    }

    /**
     * Configure Fluss to connect to ZooKeeper using a Docker network alias.
     * This is the preferred approach when both containers are on the same Docker network.
     *
     * @param dockerName the Docker network alias for ZooKeeper (e.g., "zookeeper")
     * @param port the ZooKeeper port (typically 2181)
     * @return this container for chaining
     */
    public FlussTestContainer withZookeeperDockerName(String dockerName, int port) {
        return withFlussProperties(dockerName + ":" + port);
    }

    private FlussTestContainer withFlussProperties(String zookeeperAddress) {
        // Fluss uses FLUSS_PROPERTIES to inject config into server.yaml.
        // The entrypoint removes bind.listeners, so we must add it back.
        withEnv("FLUSS_PROPERTIES",
            "bind.listeners: FLUSS://0.0.0.0:9123\n" +
            "zookeeper.address: " + zookeeperAddress + "\n" +
            "data.dir: /tmp/fluss-data\n" +
            "remote.data.dir: /tmp/fluss-remote-data");
        // Set wait strategy AFTER config is complete — now Fluss can actually start
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));
        return this;
    }

    /**
     * Get the Fluss endpoint in "fluss://host:port" format for the Fluss client.
     */
    public String getFlussEndpoint() {
        return "fluss://" + getHost() + ":" + getMappedPort(FLUSS_PORT);
    }

    /**
     * Get the Fluss endpoint in "host:port" format (without scheme).
     */
    public String getFlussEndpointPlain() {
        return getHost() + ":" + getMappedPort(FLUSS_PORT);
    }
}
