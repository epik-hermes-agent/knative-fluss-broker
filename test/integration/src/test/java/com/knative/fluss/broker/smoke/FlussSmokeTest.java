package com.knative.fluss.broker.smoke;

import com.knative.fluss.broker.storage.fluss.client.FlussConnectionManager;
import com.knative.fluss.broker.common.config.FlussConfig;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Simple smoke test to verify the Fluss docker-compose cluster is running.
 */
class FlussSmokeTest {

    @Test
    void shouldConnectToFlussCluster() {
        FlussConfig config = new FlussConfig(
                "fluss://127.0.0.1:9123",
                50, 100, 10000, 3, 100
        );
        var connManager = new FlussConnectionManager(config);
        assertThat(connManager.getConnection()).isNotNull();
        assertThat(connManager.getConfig().endpoint()).contains("127.0.0.1:9123");
        connManager.close();
    }
}
