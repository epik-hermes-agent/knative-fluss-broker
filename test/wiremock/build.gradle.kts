plugins {
    `java-library`
}

dependencies {
    api(project(":test:testlib"))
    
    api(libs.wiremock)
    api(libs.bundles.testing.core)
    
    compileOnly(libs.slf4j.api)
}
