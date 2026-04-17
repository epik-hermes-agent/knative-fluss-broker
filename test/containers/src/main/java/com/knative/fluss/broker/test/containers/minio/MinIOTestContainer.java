package com.knative.fluss.broker.test.containers.minio;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers wrapper for MinIO (S3-compatible storage).
 * Uses pgsty/minio community fork for faster pulls.
 * Used for Iceberg tiering integration tests.
 */
public class MinIOTestContainer extends MinIOContainer {

    private static final DockerImageName IMAGE = DockerImageName.parse("pgsty/minio")
            .asCompatibleSubstituteFor("minio/minio");

    public MinIOTestContainer() {
        super(IMAGE);
    }

    /** Get the S3 endpoint URL. */
    public String getS3Endpoint() {
        return "http://" + getHost() + ":" + getMappedPort(9000);
    }
}
