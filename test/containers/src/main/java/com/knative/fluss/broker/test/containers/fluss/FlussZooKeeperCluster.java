package com.knative.fluss.broker.test.containers.fluss;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Helper that groups a ZooKeeper container, Fluss coordinator, and Fluss tablet server
 * with correct startup ordering for integration testing, using a shared
 * Docker network for container-to-container communication.
 *
 * <p>The full Fluss cluster (coordinator + tablet server + ZooKeeper) is required
 * for the client to create databases, tables, and perform I/O operations.
 * The coordinator alone cannot store data — it needs tablet servers.
 *
 * <p>Usage with {@code @Container}:
 * <pre>
 * &#64;Container
 * static final FlussZooKeeperCluster cluster = new FlussZooKeeperCluster();
 *
 * // After containers start (in &#64;BeforeEach or &#64;Test):
 * String endpoint = cluster.getFlussEndpoint();  // host:port for Fluss client
 * </pre>
 */
public class FlussZooKeeperCluster extends GenericContainer<FlussZooKeeperCluster> {

    private static final int FLUSS_CLIENT_PORT = 9123;
    private static final int ZOOKEEPER_CLIENT_PORT = 2181;

    private final GenericContainer<?> zookeeper;
    private final GenericContainer<?> coordinator;
    private final GenericContainer<?> tabletServer;

    public FlussZooKeeperCluster() {
        // Use busybox — start() and stop() are overridden
        super(DockerImageName.parse("busybox:latest"));

        // Create a shared Docker network so containers can resolve each other by name
        Network network = Network.newNetwork();

        // ZooKeeper container
        this.zookeeper = new GenericContainer<>(DockerImageName.parse("zookeeper:3.9"))
                .withNetwork(network)
                .withNetworkAliases("zookeeper")
                .withExposedPorts(ZOOKEEPER_CLIENT_PORT)
                .withEnv("ZOOKEEPER_CLIENT_PORT", String.valueOf(ZOOKEEPER_CLIENT_PORT))
                .withEnv("ZOOKEEPER_TICK_TIME", "2000")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
                .withStartupTimeout(Duration.ofSeconds(60));

        // Fluss coordinator
        this.coordinator = new GenericContainer<>(DockerImageName.parse("apache/fluss:1.0-SNAPSHOT"))
                .withNetwork(network)
                .withNetworkAliases("coordinator")
                .withCommand("coordinatorServer")
                .withExposedPorts(FLUSS_CLIENT_PORT)
                .withEnv("FLUSS_PROPERTIES",
                    "bind.listeners: FLUSS://0.0.0.0:9123\n" +
                    "zookeeper.address: zookeeper:2181\n" +
                    "data.dir: /tmp/fluss-data\n" +
                    "remote.data.dir: /tmp/fluss-remote-data")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

        // Fluss tablet server — needs ZK address and coordinator address
        this.tabletServer = new GenericContainer<>(DockerImageName.parse("apache/fluss:1.0-SNAPSHOT"))
                .withNetwork(network)
                .withNetworkAliases("tablet")
                .withCommand("tabletServer")
                .withEnv("FLUSS_PROPERTIES",
                    "bind.listeners: FLUSS://0.0.0.0:9124\n" +
                    "zookeeper.address: zookeeper:2181\n" +
                    "data.dir: /tmp/fluss-data\n" +
                    "remote.data.dir: /tmp/fluss-remote-data\n" +
                    "tablet-server.id: 0")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));
    }

    @Override
    public void start() {
        try {
            // Start ZooKeeper first
            zookeeper.start();

            // Start coordinator (depends on ZK)
            coordinator.start();

            // Start tablet server (depends on ZK and coordinator)
            tabletServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Fluss+ZooKeeper cluster", e);
        }
    }

    @Override
    public void stop() {
        try {
            tabletServer.stop();
        } finally {
            try {
                coordinator.stop();
            } finally {
                zookeeper.stop();
            }
        }
    }

    // ─────────────────────────────────────────────
    // Accessors (safe to call after start())
    // ─────────────────────────────────────────────

    /**
     * Get the Fluss endpoint in "fluss://host:port" format for the Fluss client.
     */
    public String getFlussEndpoint() {
        return "fluss://" + getHost() + ":" + coordinator.getMappedPort(FLUSS_CLIENT_PORT);
    }

    /**
     * Get the Fluss endpoint in "host:port" format (without scheme).
     */
    public String getFlussEndpointPlain() {
        return getHost() + ":" + coordinator.getMappedPort(FLUSS_CLIENT_PORT);
    }

    /**
     * Get the ZooKeeper connection string using host-mapped port.
     * For test clients that run on the host machine.
     */
    public String getZookeeperAddress() {
        return getHost() + ":" + zookeeper.getMappedPort(ZOOKEEPER_CLIENT_PORT);
    }

    @Override
    public String getHost() {
        // Use 127.0.0.1 (IPv4) to avoid IPv6 resolution issues on macOS Docker Desktop.
        // The Fluss container binds to 0.0.0.0 (IPv4 only), and macOS may resolve
        // "localhost" to IPv6 [::], causing Connection refused errors.
        return "127.0.0.1";
    }

    @Override
    public String toString() {
        return "FlussZooKeeperCluster{" +
                "coordinator=" + coordinator +
                ", tabletServer=" + tabletServer +
                ", zookeeper=" + zookeeper +
                '}';
    }
}
