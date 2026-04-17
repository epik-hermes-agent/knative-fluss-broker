plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.knative.fluss.broker"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven { url = uri("https://repository.apache.org/content/repositories/snapshots/") }
    }
}

subprojects {
    apply(plugin = "java-library")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
        // Pass Docker socket to forked test JVMs (needed for Testcontainers on macOS Docker Desktop)
        jvmArgs(
            "-Dtestcontainers.dockerSocket=/Users/pasha/.docker/run/docker.sock",
            "-DDOCKER_HOST=unix:///Users/pasha/.docker/run/docker.sock",
            // Required for Apache Arrow (used by Fluss client) on Java 17+
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        )
        environment("DOCKER_HOST", "unix:///Users/pasha/.docker/run/docker.sock")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/Users/pasha/.docker/run/docker.sock")
    }

    afterEvaluate {
        dependencies {
            "testImplementation"(libs.bundles.testing.core)
            "testRuntimeOnly"(libs.junit.jupiter.engine)
        }
    }
}
