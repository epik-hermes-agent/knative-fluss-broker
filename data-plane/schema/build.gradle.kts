plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    
    implementation(libs.jackson.databind)
    
    compileOnly(libs.slf4j.api)
}
