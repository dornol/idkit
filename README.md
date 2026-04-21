# idkit

A small, fast collection of thread-safe ID generators for Kotlin/JVM.

Provided generators:
- **Snowflake** (`Long`, 64 bits) — strictly increasing ids using Twitter's 41/5/5/12 bit layout.
- **Flake** (`Long`, 64 bits) — Snowflake-derived generator with a customizable bit layout, epoch, and timestamp resolution.
- **UUID v7** (`java.util.UUID`) — RFC 9562 §6.2 Method 2 implementation with intra-millisecond monotonicity.
- **ULID** (`String`, 26 chars) — Crockford Base32 encoded, lexicographically sortable, monotonic within a millisecond.
- **NanoID** (`String`, 21 chars by default) — compact, URL-safe, cryptographically random. Not time-ordered — fills the "opaque public id" slot.

## Project info

- Language / runtime: Kotlin on JVM (JDK 11)
- Kotlin: 2.3.10, Gradle Kotlin DSL
- Tests: JUnit 5
- Coordinates: `io.github.dornol:idkit:2.1.0`

> **Upgrading from 1.x?** 2.0.0 contains multiple breaking changes.
> Please read the 2.0.0 section of [CHANGELOG.md](CHANGELOG.md) first.

## Installation

Fetch the artifact from Maven Central.

Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.dornol:idkit:2.1.0")
}
```

Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.dornol:idkit:2.1.0'
}
```

Maven:
```xml
<dependency>
  <groupId>io.github.dornol</groupId>
  <artifactId>idkit</artifactId>
  <version>2.1.0</version>
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

### 3) ULID (`String`)

Generates 26-character [ULIDs](https://github.com/ulid/spec) encoded in Crockford's Base32.

```kotlin
import io.github.dornol.idkit.ulid.UlidIdGenerator

fun main() {
    val gen = UlidIdGenerator()
    val ulid: String = gen.nextId()
    println("ulid = $ulid") // e.g. 01HV8B2YJ4M2N3X4Y5Z6ABCDEF
}
```

Layout:
- First 10 chars: 48-bit Unix-epoch-ms timestamp
- Last 16 chars: 80-bit randomness

Guarantees:
- **Monotonic within a millisecond**: the 80-bit randomness is incremented by 1 for the second and subsequent ULIDs emitted in the same ms, so the strings compare lexicographically in generation order.
- **Clock regression**: if the system clock moves backwards, the previously held timestamp is reused and the randomness continues to increment, preserving monotonicity.
- **Overflow**: exhausting the 80-bit randomness within a single ms (~1.2 × 10²⁴ ids) throws `IllegalStateException`. Practically unreachable.
- Thread-safe via `@Synchronized`.

### 4) NanoID (`String`)

Generates compact, URL-safe, **random** (non-time-ordered) strings. Good for public identifiers — short URLs, session tokens, invite codes — where the id should leak no timing information and should be hard to guess.

```kotlin
import io.github.dornol.idkit.nanoid.NanoIdGenerator

fun main() {
    val gen = NanoIdGenerator()               // 21-char URL-safe id
    val id: String = gen.nextId()             // e.g. "V1StGXR8_Z5jdHi6B-myT"
    println(id)

    // Custom size / alphabet
    val short = NanoIdGenerator(size = 10)
    val digits = NanoIdGenerator(size = 6, alphabet = "0123456789")
}
```

Defaults:
- Size: 21 chars
- Alphabet: 64 URL-safe chars (`A-Z`, `a-z`, `0-9`, `_`, `-`)
- Collision profile: ~2¹²² possible ids (≈ UUID v4 level)
- Random source: `java.security.SecureRandom` (thread-safe)

Notes:
- **Not time-ordered.** If you need lexicographic order by time, use ULID or UUID v7 instead.
- Duplicate characters in a custom `alphabet` bias the output; pass a deduplicated string.

### 5) UUID v7 (`java.util.UUID`)

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

## Parsers

Each time-ordered generator has a matching parser that recovers the embedded metadata — useful for log correlation, incident triage, and debugging.

### Flake / Snowflake

```kotlin
import io.github.dornol.idkit.flake.FlakeIdParser
import io.github.dornol.idkit.flake.SnowflakeIdGenerator

val gen = SnowflakeIdGenerator(workerId = 7, datacenterId = 13)
val id = gen.nextId()

// Convenience: mirror an existing generator's layout
val parser = FlakeIdParser.of(gen)
val parts = parser.decompose(id)
// FlakeComponents(timestamp=Instant, datacenterId=13, workerId=7, sequence=…)

parser.timestampOf(id)   // Instant
parser.workerOf(id)      // 7
parser.datacenterOf(id)  // 13
parser.sequenceOf(id)    // sequence counter within the slice
```

For cross-service parsing — where the generator lives elsewhere — construct the parser with the same layout instead of calling `.of(...)`.

### ULID

```kotlin
import io.github.dornol.idkit.ulid.UlidParser

val ulid = "01HV8B2YJ4M2N3X4Y5Z6ABCDEF"
UlidParser.timestampOf(ulid)    // Instant
UlidParser.toBytes(ulid)        // 16-byte big-endian binary form
UlidParser.fromBytes(bytes16)   // re-encode binary back to the 26-char string
UlidParser.isValid(ulid)        // cheap pre-check; never throws
```

### UUID v7

```kotlin
import io.github.dornol.idkit.uuidv7.UuidV7Parser

UuidV7Parser.timestampOf(uuid)     // Instant — throws if the UUID is not a v7
UuidV7Parser.rawTimestampOf(uuid)  // Long — no version check, takes the top 48 bits as-is
```

NanoID deliberately has no parser: it is not time-ordered and carries no recoverable metadata.

## Common interface

All generators implement `IdGenerator<T>`.

```kotlin
interface IdGenerator<T> {
    fun nextId(): T
}
```

## Behavior and caveats

### Thread safety
- **Snowflake / Flake / ULID**: `nextId()` is `@Synchronized`.
- **UUID v7**: uses an internal `AtomicLong` with CAS.
- **NanoID**: relies on a thread-safe `SecureRandom` (serialized per JDK contract).

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
- **UUID v7 / ULID**: keep the previously observed timestamp and increment the counter/randomness to preserve monotonicity.

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
- `src/test/kotlin/io/github/dornol/idkit/ulid/UlidIdGeneratorTest.kt`
- `src/test/kotlin/io/github/dornol/idkit/nanoid/NanoIdGeneratorTest.kt`

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
- ULID has a per-ms randomness budget of 2⁸⁰ (≈ 1.2 × 10²⁴), which is unreachable in practice.
- NanoID generation cost is dominated by `SecureRandom.nextInt()`; for very high-volume workloads, profile before concluding it is a bottleneck.

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
