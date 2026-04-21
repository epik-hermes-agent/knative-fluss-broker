plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}

application {
    mainClass.set("com.knative.fluss.broker.tui.BrokerDashboardApp")
}

dependencies {
    // Project modules
    implementation(project(":data-plane:common"))
    implementation(project(":data-plane:storage-fluss"))

    // TamboUI
    implementation(platform("dev.tamboui:tamboui-bom:0.2.0-SNAPSHOT"))
    implementation("dev.tamboui:tamboui-toolkit")
    implementation("dev.tamboui:tamboui-jline3-backend")

    // HTTP client for Polaris REST API (Iceberg tiering)
    implementation(libs.okhttp)

    // JSON processing
    implementation(libs.bundles.jackson)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-opens=java.base/java.nio=ALL-UNNAMED"))
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}
