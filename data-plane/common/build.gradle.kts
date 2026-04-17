plugins {
    `java-library`
}

dependencies {
    // CloudEvents
    api(libs.bundles.cloudevents)
    
    // Jackson
    api(libs.bundles.jackson)
    
    // Micrometer metrics
    api(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    
    // Guava
    implementation(libs.guava)
    
    // SLF4J
    compileOnly(libs.slf4j.api)
}
