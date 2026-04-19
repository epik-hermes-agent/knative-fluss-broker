plugins {
    `java-library`
}

dependencies {
    api(project(":test:testlib"))
    
    api(libs.bundles.testing.containers)
    api(libs.testcontainers.kafka)

    // Fluss for container testing
    api(libs.fluss.client)
    api(libs.fluss.common)
    
    compileOnly(libs.slf4j.api)
}
