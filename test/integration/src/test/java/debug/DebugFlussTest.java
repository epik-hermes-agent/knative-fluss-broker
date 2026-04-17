package debug;

import com.knative.fluss.broker.test.containers.fluss.FlussZooKeeperCluster;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DebugFlussTest {
    @Container
    static final FlussZooKeeperCluster cluster = new FlussZooKeeperCluster();

    @Test
    void debugClusterStartup() {
        System.out.println("=== DEBUG: Container Info ===");
        System.out.println("Fluss endpoint: " + cluster.getFlussEndpoint());
        System.out.println("Fluss host: " + cluster.getHost());
        System.out.println("ZK address: " + cluster.getZookeeperAddress());

        assertThat(cluster.getFlussEndpoint()).isNotNull();
        assertThat(cluster.getZookeeperAddress()).isNotNull();
    }
}
