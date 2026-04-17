plugins {
    `java-library`
}

dependencies {
    api(project(":data-plane:common"))
    api(project(":data-plane:storage-fluss"))
    
    // Iceberg
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.hive)
    implementation(libs.iceberg.aws)
    
    compileOnly(libs.slf4j.api)
}
