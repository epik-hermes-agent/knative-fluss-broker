plugins {
    `java-library`
    application
}

application {
    mainClass.set("com.knative.fluss.broker.controller.FlussBrokerController")
}

dependencies {
    api(project(":control-plane:api"))
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    api(project(":data-plane:schema"))
    api(project(":data-plane:ingress"))
    api(project(":data-plane:dispatcher"))
    api(project(":data-plane:delivery"))
    
    // Fabric8 client
    implementation(libs.fabric8.kubernetes.client)
    
    // SLF4J + Logback
    compileOnly(libs.slf4j.api)
    implementation(libs.logback.classic)
    
    // Jackson
    implementation(libs.bundles.jackson)

    // Testing
    testImplementation(libs.bundles.testing.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
