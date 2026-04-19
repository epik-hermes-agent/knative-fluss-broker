plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":data-plane:common"))
    testImplementation(project(":data-plane:ingress"))
    testImplementation(project(":data-plane:dispatcher"))
    testImplementation(project(":data-plane:storage-fluss"))
    testImplementation(project(":data-plane:schema"))
    testImplementation(project(":data-plane:delivery"))

    testImplementation(project(":test:testlib"))
    testImplementation(project(":test:containers"))
    testImplementation(project(":test:wiremock"))
    
    testImplementation(libs.bundles.testing.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.bundles.testing.containers)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.testcontainers.kafka)

    testRuntimeOnly(libs.logback.classic)
}
