# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**idkit** is a Kotlin/JVM library providing thread-safe ID generators: Snowflake (Long), Flake (configurable Long), and UUID v7. Published to Maven Central as `io.github.dornol:idkit`.

## Build Commands

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run all tests
./gradlew test --tests "io.github.dornol.idkit.flake.SnowflakeIdGeneratorTest"  # Run a single test class
./gradlew publish        # Publish to Maven Central (requires credentials in ~/.gradle/gradle.properties)
```

On Windows, use `./gradlew.bat` instead of `./gradlew`.

## Tech Stack

- **Language:** Kotlin 1.9.25, targeting JVM 11
- **Build:** Gradle with Kotlin DSL
- **Testing:** JUnit 5
- **Logging:** SLF4J API
- **Docs:** Dokka (generates javadocJar for Maven Central)
- **Publishing:** Vanniktech Maven Publish plugin → Central Publishing Portal

## Architecture

All generators implement the common `IdGenerator<T>` interface (`src/main/kotlin/.../idkit/IdGenerator.kt`) with a single `fun nextId(): T` method.

### Generator Hierarchy

- `FlakeIdGenerator` (open class) — configurable bit layout (timestamp/datacenter/worker/sequence bits), custom epoch, and adjustable timestamp resolution via `timestampDivisor`. Thread safety via `@Synchronized`.
- `SnowflakeIdGenerator` extends `FlakeIdGenerator` — fixed Twitter Snowflake layout (41/5/5/12 bits), no additional logic.
- `UuidV7IdGenerator` — standalone implementation producing `java.util.UUID`. Uses `AtomicLong` + CAS for monotonic timestamps; randomness via `ThreadLocalRandom`.

### Key Design Decisions

- Clock regression handling: Flake/Snowflake pin to last timestamp; UUID v7 uses CAS to ensure monotonicity.
- Sequence overflow in Flake/Snowflake triggers busy-spin wait (`Thread.onSpinWait()`) until the next time slice.

## CI/CD

GitHub Actions workflow (`.github/workflows/maven-publish.yml`) triggers on version tags (`*.*.*`), builds with JDK 11 (Temurin), and publishes to Maven Central.

## Code Conventions

- Source language is Kotlin; comments and documentation are in Korean.
- Test method names use Kotlin backtick syntax with descriptive English phrases (e.g., `` `ids are strictly increasing and positive` ``).
- Package structure: `io.github.dornol.idkit` with sub-packages `flake` and `uuidv7`.
