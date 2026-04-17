plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    
    api(libs.bundles.testing.core)
    api(libs.assertj.core)
    
    compileOnly(libs.slf4j.api)
}
