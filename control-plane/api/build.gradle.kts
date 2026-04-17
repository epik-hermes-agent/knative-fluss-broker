plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    
    // Fabric8 CRD model
    api(libs.fabric8.kubernetes.client.api)
    api(libs.fabric8.kubernetes.model.core)
    
    // Jackson for serialization
    api(libs.bundles.jackson)
    
    compileOnly(libs.slf4j.api)
}
