plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":data-plane:common"))
    testImplementation(project(":data-plane:ingress"))
    testImplementation(project(":data-plane:dispatcher"))
    testImplementation(project(":data-plane:storage-fluss"))
    testImplementation(project(":test:testlib"))
    testImplementation(project(":test:containers"))
    testImplementation(project(":test:wiremock"))
    
    testImplementation(libs.bundles.testing.core)
    testImplementation(libs.bundles.testing.containers)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    
    testRuntimeOnly(libs.logback.classic)
}
