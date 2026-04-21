# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**idkit** is a Kotlin/JVM library providing thread-safe ID generators: Snowflake (Long), Flake (configurable Long), UUID v7, ULID, and NanoID. Published to Maven Central as `io.github.dornol:idkit`.

## Build Commands

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run all tests
./gradlew test --tests "io.github.dornol.idkit.flake.SnowflakeIdGeneratorTest"  # Run a single test class
./gradlew publish        # Publish to Maven Central (requires credentials in ~/.gradle/gradle.properties)
```

On Windows, use `./gradlew.bat` instead of `./gradlew`.

## Tech Stack

- **Language:** Kotlin 2.3.10, targeting JVM 11
- **Build:** Gradle with Kotlin DSL
- **Testing:** JUnit 5
- **Logging:** SLF4J API
- **Docs:** Dokka (generates javadocJar for Maven Central)
- **Publishing:** Vanniktech Maven Publish plugin → Central Publishing Portal

## Architecture

All generators implement the common `IdGenerator<T>` interface (`src/main/kotlin/.../idkit/IdGenerator.kt`) with a single `fun nextId(): T` method.

### Parsers

Each time-ordered generator has a matching parser:
- `FlakeIdParser` — decomposes a `Long` id into `FlakeComponents(timestamp, datacenterId, workerId, sequence)`. Has `FlakeIdParser.of(generator)` for mirroring a live generator's layout.
- `UlidParser` (object) — `timestampOf`, `toBytes`, `fromBytes`, `isValid`. ULID encode/decode helpers live in `UlidCodec.kt` as package-internal top-level functions so the generator and parser share a single implementation.
- `UuidV7Parser` (object) — `timestampOf` (strict v7 check) and `rawTimestampOf` (no version check).

NanoID deliberately has no parser: it is pure random and carries no recoverable metadata.

### Generator Hierarchy

- `FlakeIdGenerator` (open class) — configurable bit layout (timestamp/datacenter/worker/sequence bits), custom epoch, and adjustable timestamp resolution via `timestampDivisor`. Thread safety via `@Synchronized`.
- `SnowflakeIdGenerator` extends `FlakeIdGenerator` — fixed Twitter Snowflake layout (41/5/5/12 bits), no additional logic.
- `UuidV7IdGenerator` — standalone implementation producing `java.util.UUID`. Uses `AtomicLong` + CAS for monotonic timestamps; randomness via `ThreadLocalRandom`.
- `UlidIdGenerator` (open class) — 26-char Crockford Base32 string. 48-bit timestamp + 80-bit randomness, monotonic within a ms via randomness increment. Thread safety via `@Synchronized`.
- `NanoIdGenerator` — compact URL-safe random string (21 chars / 64-char alphabet by default). **Not time-ordered**; backed by `SecureRandom`. Fills the "opaque public identifier" slot.

### Key Design Decisions

- Clock regression handling: Flake/Snowflake throw `ClockMovedBackwardsException`; UUID v7 and ULID hold the previously observed timestamp and increment their internal counter/randomness to preserve monotonicity.
- Sequence overflow in Flake/Snowflake triggers a bounded busy-spin (`Thread.onSpinWait()`) until the next time slice.
- UUID v7 uses RFC 9562 §6.2 Method 2: the 12-bit `rand_a` field is a monotonic counter packed with the timestamp into a single `AtomicLong`, updated via CAS.
- ULID follows the spec's monotonic profile: within the same ms the 80-bit randomness is incremented by 1, so the emitted 26-char strings compare lexicographically in generation order.

## CI/CD

GitHub Actions workflow (`.github/workflows/maven-publish.yml`) triggers on version tags (`*.*.*`), builds with JDK 11 (Temurin), and publishes to Maven Central.

## Code Conventions

- Source language is Kotlin; comments and documentation are in English.
- Test method names use Kotlin backtick syntax with descriptive English phrases (e.g., `` `ids are strictly increasing and positive` ``).
- Package structure: `io.github.dornol.idkit` with sub-packages `flake`, `uuidv7`, `ulid`, and `nanoid`.
