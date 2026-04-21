plugins {
    `java-library`
    application
}

dependencies {
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    api(project(":data-plane:schema"))
    
    // CloudEvents
    api(libs.bundles.cloudevents)
    
    compileOnly(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.knative.fluss.broker.ingress.server.IngressServerMain")
}
