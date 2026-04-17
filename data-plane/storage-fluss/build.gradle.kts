plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    
    // Fluss
    api(libs.fluss.client)
    api(libs.fluss.common)
    
    // Jackson for config
    implementation(libs.bundles.jackson)
    
    compileOnly(libs.slf4j.api)
}
