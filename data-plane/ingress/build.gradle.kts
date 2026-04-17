plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    api(project(":data-plane:schema"))
    
    // CloudEvents
    api(libs.bundles.cloudevents)
    
    compileOnly(libs.slf4j.api)
}
