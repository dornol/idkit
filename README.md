# idkit

A small, fast collection of thread-safe ID generators for Kotlin/JVM.

Provided generators:
- **Snowflake** (`Long`, 64 bits) — strictly increasing ids using Twitter's 41/5/5/12 bit layout.
- **Flake** (`Long`, 64 bits) — Snowflake-derived generator with a customizable bit layout, epoch, and timestamp resolution.
- **UUID v7** (`java.util.UUID`) — RFC 9562 §6.2 Method 2 implementation with intra-millisecond monotonicity.

## Project info

- Language / runtime: Kotlin on JVM (JDK 11)
- Kotlin: 2.3.10, Gradle Kotlin DSL
- Tests: JUnit 5
- Coordinates: `io.github.dornol:idkit:2.0.1`

> **Upgrading from 1.x?** 2.0.0 contains multiple breaking changes.
> Please read the 2.0.0 section of [CHANGELOG.md](CHANGELOG.md) first.

## Installation

Fetch the artifact from Maven Central.

Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.dornol:idkit:2.0.1")
}
```

Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.dornol:idkit:2.0.1'
}
```

Maven:
```xml
<dependency>
  <groupId>io.github.dornol</groupId>
  <artifactId>idkit</artifactId>
  <version>2.0.1</version>
</dependency>
```

## Quick start

Build and test locally:
```bash
# Windows
./gradlew.bat build
# macOS / Linux
./gradlew build

# Tests only
./gradlew.bat test  # Windows
./gradlew test      # macOS / Linux
```

## Usage

### 1) Snowflake (`Long`)

Thread-safe; ids produced by the same instance are strictly increasing.

```kotlin
import io.github.dornol.idkit.flake.SnowflakeIdGenerator

fun main() {
    // workerId and datacenterId are Int in the range 0..31
    val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)

    val id: Long = gen.nextId()
    println("snowflake = $id")
}
```

Snowflake bit layout (fixed):
- `timestamp(41) | datacenterId(5) | workerId(5) | sequence(12)`
- Rolls over to the next millisecond when more than 4096 ids are requested within a single ms.
- Default epoch is UNIX epoch (`1970-01-01T00:00:00Z`). A custom epoch is supported:

```kotlin
import java.time.LocalDateTime
import java.time.ZoneId

val customEpoch = LocalDateTime.of(2020, 1, 1, 0, 0)
    .atZone(ZoneId.of("UTC")).toInstant()

val gen = SnowflakeIdGenerator(
    workerId = 0,
    datacenterId = 0,
    epochStart = customEpoch,
)
```

### 2) Flake (`Long`, customizable)

`FlakeIdGenerator` lets you tune the bit layout and timestamp resolution.

```kotlin
import io.github.dornol.idkit.flake.FlakeIdGenerator
import java.time.Instant

val gen = FlakeIdGenerator(
    timestampBits = 41,        // timestamp bits (>0)
    datacenterIdBits = 5,      // datacenter bits (1..5)
    workerIdBits = 5,          // worker bits (1..31)
    timestampDivisor = 1L,     // divide ms by this (e.g. 10 → 10 ms granularity)
    epochStart = Instant.EPOCH,
    datacenterId = 1,          // Int
    workerId = 1,              // Int
)
val id: Long = gen.nextId()
```

Constraints:
- `unused(1) + timestampBits + datacenterIdBits + workerIdBits <= 63`, with at least 1 bit left for sequence.
- A larger `timestampDivisor` widens the representable range at the cost of coarser resolution.
- The timestamp field stores `(now - epoch) / divisor` with full precision (fixed in 2.0.0).

### 3) UUID v7 (`java.util.UUID`)

Generates RFC 9562 UUID v7 values with `version = 7` and `variant = 0b10`.

```kotlin
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import java.util.UUID

fun main() {
    val gen = UuidV7IdGenerator()
    val u: UUID = gen.nextId()
    println("uuid7 = $u")
}
```

Monotonicity (since 2.0.0):
- The 12-bit `rand_a` region is repurposed as a **dedicated monotonic counter** (RFC 9562 Method 2).
- `(timestamp:52 | counter:12)` is packed into a single `AtomicLong` and updated atomically via CAS.
- When the counter overflows within the same millisecond, the timestamp is borrowed 1 ms forward and the counter resets to 0. Once the real clock catches up, the stored timestamp realigns naturally.
- As a result, UUIDs produced by the same generator are **strictly increasing** when compared by `mostSignificantBits` — friendly to database index locality.

## Common interface

All generators implement `IdGenerator<T>`.

```kotlin
interface IdGenerator<T> {
    fun nextId(): T
}
```

## Behavior and caveats

### Thread safety
- **Snowflake / Flake**: `nextId()` is `@Synchronized`.
- **UUID v7**: uses an internal `AtomicLong` with CAS.

### Clock regression (`System.currentTimeMillis()` returns a value smaller than the last observation)
- **Snowflake / Flake**: throw `ClockMovedBackwardsException` (extends `IllegalStateException`). The internal state is not mutated before the throw, so the same instance is usable once the clock recovers.
  ```kotlin
  try {
      val id = gen.nextId()
  } catch (e: ClockMovedBackwardsException) {
      // e.driftAmount tells you how far the clock moved back — back off and retry,
      // or alert ops.
  }
  ```
- **UUID v7**: keeps the previously observed timestamp and increments the counter to preserve monotonicity.

### Timestamp exhaustion
The `timestampBits` field of Flake/Snowflake has a finite range. Once exceeded, `IllegalStateException` is raised, and because wall-clock time only moves forward the state is **non-recoverable**. Reconstruct the generator with a wider `timestampBits` or a more recent `epochStart`.

### Configuration limits
- `timestampBits > 0`
- `datacenterIdBits in 1..5`
- `workerIdBits in 1..31`
- `unused(1) + timestamp + datacenter + worker <= 63` (at least 1 bit reserved for sequence)

## Tests

JUnit 5 test files:
- `src/test/kotlin/io/github/dornol/idkit/flake/SnowflakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/flake/FlakeIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/uuidv7/UuidV7IdGeneratorTest.kt`

Run:
```bash
./gradlew.bat test  # Windows
./gradlew test      # macOS / Linux
```

## Performance tips

- Reuse generator instances (one singleton per process) to amortize synchronization and atomic-op cost.
- Keep the system clock in good NTP sync.
- Snowflake has a per-ms sequence ceiling of 4096.
- UUID v7 has a per-ms counter ceiling of 4096 and borrows from the clock when exceeded; sustained overload will push the embedded timestamp ahead of the wall clock.

## Logging

This library uses the SLF4J API. Without a binding it falls back to the NOP logger. Add `slf4j-simple`, `logback-classic`, or similar if you want output.

## Publishing (maintainer notes)

Configured to publish to the Central Publishing Portal via the Vanniktech Maven Publish plugin. Set the following keys in `~/.gradle/gradle.properties`:

```
mavenCentralUsername=YOUR_CENTRAL_TOKEN
mavenCentralPassword=YOUR_CENTRAL_SECRET
signing.keyId=...
signing.password=...
signing.secretKeyRingFile=/path/to/secring.gpg
```

Publish:
```bash
./gradlew publish
```

GitHub Actions publishes automatically on pushes of `*.*.*` tags.

## License

MIT License — see the `LICENSE` file in the repo root.

## Changelog

Detailed history is in [CHANGELOG.md](CHANGELOG.md).
