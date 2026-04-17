plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    api(project(":data-plane:schema"))
    api(project(":data-plane:delivery"))
    
    // CloudEvents
    implementation(libs.bundles.cloudevents)
    
    // OkHttp for subscriber delivery
    implementation(libs.okhttp)
    
    compileOnly(libs.slf4j.api)
}
