plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    
    // HTTP client
    api(libs.okhttp)
    
    // CloudEvents
    api(libs.bundles.cloudevents)
    
    // Jackson for serialization
    api(libs.bundles.jackson)
    
    compileOnly(libs.slf4j.api)
}
