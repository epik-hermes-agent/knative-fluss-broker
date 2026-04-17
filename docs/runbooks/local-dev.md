# Local Development Runbook

## Prerequisites
- Java 21 (via Homebrew: `brew install openjdk@21`)
- Docker and Docker Compose
- Git

## Quick Start

```bash
# Clone and enter repo
cd knative-fluss-broker

# Start infrastructure (Fluss + ZooKeeper)
docker compose up -d

# Run unit tests
./gradlew test

# Run integration tests (Docker required)
./gradlew integrationTest

# Run all tests
./gradlew build
```

## Full Lakehouse Stack

```bash
# Start everything including MinIO, Hive Metastore, Flink
docker compose --profile lakehouse up -d

# Run with Iceberg tests
./gradlew build -Plakehouse

# Access Flink UI: http://localhost:8081
# Access MinIO Console: http://localhost:9001 (minioadmin/minioadmin)
```

## Service Endpoints

| Service | Endpoint | Purpose |
|---------|----------|---------|
| Fluss | localhost:9123 | Event storage |
| ZooKeeper | localhost:2181 | Fluss coordination |
| MinIO | localhost:9000 | S3 API |
| MinIO Console | localhost:9001 | S3 management |
| Hive Metastore | localhost:9083 | Iceberg catalog (lakehouse) |
| Flink | localhost:8081 | Tiering services (lakehouse) |
| PostgreSQL | localhost:5432 | HMS backend (lakehouse) |

## Useful Commands

```bash
# Build specific module
./gradlew :data-plane:ingress:build

# Run specific test
./gradlew test --tests "EnvelopeTest"

# Clean build
./gradlew clean build

# View dependency tree
./gradlew :data-plane:dispatcher:dependencies

# Stop infrastructure
docker compose down -v

# Stop lakehouse stack
docker compose --profile lakehouse down -v
```

## IDE Setup

1. Import as Gradle project
2. Set Java SDK to 21
3. Enable annotation processing (for fabric8 CRD generator)
4. Run configurations: use `./gradlew` tasks
