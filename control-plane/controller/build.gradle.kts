plugins {
    `java-library`
}

dependencies {
    api(project(":control-plane:api"))
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    api(project(":data-plane:schema"))
    
    // Fabric8 client
    implementation(libs.fabric8.kubernetes.client)
    
    compileOnly(libs.slf4j.api)
}
