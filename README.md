# idkit

A small, fast collection of thread-safe ID generators for Kotlin/JVM.

Provided generators:
- **Snowflake** (`Long`, 64 bits) — strictly increasing ids using Twitter's 41/5/5/12 bit layout.
- **Flake** (`Long`, 64 bits) — Snowflake-derived generator with a customizable bit layout, epoch, and timestamp resolution.
- **UUID v7** (`java.util.UUID`) — RFC 9562 §6.2 Method 2 implementation with intra-millisecond monotonicity.
- **ULID** (`String`, 26 chars) — Crockford Base32 encoded, lexicographically sortable, monotonic within a millisecond.
- **NanoID** (`String`, 21 chars by default) — compact, URL-safe, cryptographically random. Not time-ordered — fills the "opaque public id" slot.
- **JDBC / Redis worker leases** (optional modules) — automatic worker ID reservation with TTL,
  heartbeat, recovery, namespaces, and fencing tokens.

## Project info

- Language / runtime: Kotlin on JVM (Java 11 bytecode target)
- Build JDK: 17 or newer; CI verifies JDK 17, 21, and 25
- Kotlin: 2.3.21, Gradle Kotlin DSL
- Tests: JUnit 5
- Coordinates: `io.github.dornol:idkit:3.2.1`

> **Upgrading from 2.x?** 3.0.0 changes the default clock-regression response
> for Snowflake/Flake (see the `[3.0.0]` section in [CHANGELOG.md](CHANGELOG.md)).

> **Upgrading from 3.2.0?** 3.2.1 fixes JDBC auto-configuration with Spring Boot Docker Compose
> DataSource discovery. Existing 3.2.0 lease configuration remains source-compatible.

> **Upgrading from 3.1.0?** 3.2.0 adds automatic lease recovery, recovery backoff/jitter, lease
> namespaces, startup jitter, and stronger failure handling. Existing 3.1.0 lease integrations
> remain source-compatible.

> **Upgrading from 3.0.0?** 3.1.0 adds optional JDBC/Redis lease modules, fencing tokens, and
> operational callbacks. Existing JDBC lease tables are upgraded automatically when initialized;
> grant the application permission to add the `fencing_token` column. Deploy the new Redis module
> alongside existing instances gradually, then use fencing-aware downstream operations before
> relying on stale-worker protection.
> **Upgrading from 1.x?** 2.0.0 contains multiple breaking changes — read
> the 2.0.0 section of [CHANGELOG.md](CHANGELOG.md) first.

## Installation

Fetch the artifact from Maven Central.

Gradle (Kotlin DSL):
```kotlin
dependencies {
    implementation("io.github.dornol:idkit:3.2.1")
}
```

Gradle (Groovy):
```groovy
dependencies {
    implementation 'io.github.dornol:idkit:3.2.1'
}
```

Java:

```java
import io.github.dornol.idkit.IdKitGenerators;
import io.github.dornol.idkit.IdGenerator;

IdGenerator<Long> generator = IdKitGenerators.flake(
    workerId,
    datacenterId,
    42, // timestamp bits
    4,  // datacenter bits
    6   // worker bits
);

long id = generator.nextId();
```

The same Java-friendly factory also exposes the built-in string and UUID generators:

```java
import java.util.UUID;

IdGenerator<String> ulids = IdKitGenerators.ulid();
IdGenerator<String> nanoIds = IdKitGenerators.nanoId();
IdGenerator<UUID> uuidV7 = IdKitGenerators.uuidV7();
```

When a distributed worker lease is already acquired, use the lease-aware factory so ID
generation fails closed after lease loss:

```java
IdGenerator<Long> generator = IdKitGenerators.flake(
    lease,
    42,
    4,
    6
);
```

Maven:
```xml
<dependency>
  <groupId>io.github.dornol</groupId>
  <artifactId>idkit</artifactId>
  <version>3.2.1</version>
</dependency>
```

### Spring Boot

For Spring Boot applications, use the backend-specific starter. Both starters may be present
at the same time; only the backend selected by `idkit.backend` is initialized.

JDBC:

```kotlin
dependencies {
    implementation("io.github.dornol:idkit-spring-boot-starter-jdbc:3.2.1")
}
```

Redis:

```kotlin
dependencies {
    implementation("io.github.dornol:idkit-spring-boot-starter-redis:3.2.1")
}
```

Configure one backend and inject `IdGenerator<Long>` into application services:

```yaml
idkit:
  backend: jdbc # jdbc or redis
  # Optional when multiple services share one lease backend.
  # lease-namespace: service1
  worker-count: 32
  # Optional. Restrict this instance to one worker slot instead of automatic allocation.
  # worker-id: 3
  datacenter-id: 0
  owner: ${HOSTNAME:local}
  lease-ttl: 30s
  backend-operation-timeout: 5s
  # Optional. Defaults to lease-ttl / 3 (10s with the defaults above).
  # heartbeat-interval: 5s
  # Optional random delay before startup acquisition, useful for large fleets.
  startup-jitter: 0s
  heartbeat-failure-threshold: 1
  acquisition-attempts: 3
  acquisition-retry-delay: 1s
  recovery:
    enabled: true
    retry-delay: 1s
    retry-jitter: 500ms
    max-retry-delay: 30s
  metrics:
    enabled: true
    prefix: idkit.lease
  health:
    enabled: true
  generator:
    type: snowflake # snowflake or flake
    timestamp-bits: 41
    datacenter-id-bits: 5
    worker-id-bits: 5
    timestamp-divisor: 1
    epoch: 1970-01-01T00:00:00Z
  jdbc:
    auto-initialize: false
    validate-schema: false
    dialect: POSTGRESQL
    table-name: idkit_worker_lease
    clock-skew-allowance: 1s
    # Optional when the application has multiple DataSource beans.
    # data-source-bean-name: idkitDataSource
```

For Redis, set `idkit.backend: redis` and configure `idkit.redis.uri` and
`idkit.redis.key-prefix`. For JDBC, setting `auto-initialize: true` creates the lease table and
worker rows at startup. It defaults to `false`, which is safer for production environments where
schema changes are managed separately; enable it explicitly for local development or when the
application has the required DDL permissions. If the application has multiple JDBC data sources,
set `idkit.jdbc.data-source-bean-name` to the bean dedicated to lease storage. The Redis starter
creates an IDKit-specific client and connection from `idkit.redis.uri` even when the application
already has its own Redis client, so the lease store can run on a separate Redis instance or
cluster. `snowflake`
keeps the standard 41/5/5 layout, while `flake` applies the configured timestamp, datacenter,
worker, and sequence bit layout across the 64-bit ID. When Spring Boot Actuator is present,
`idkitHealthIndicator` reports the lease state; when Micrometer is present, lease lifecycle
counters and the active-lease gauge are registered automatically. Both integrations can be
disabled with `idkit.health.enabled: false` or `idkit.metrics.enabled: false`.
Automatic recovery adds `idkit.lease.recovery.attempted`, `.succeeded`, `.failed`, and `.active`
metrics when enabled.

Set `idkit.jdbc.validate-schema: true` when migrations create the table. This checks the fencing
column and expected worker rows without executing DDL. For migration tooling, call
`JdbcWorkerIdLeaseStore.migrationSql(workerCount, datacenterId)` to obtain dialect-specific
statements for review and application by Flyway, Liquibase, or an equivalent process.

`heartbeat-failure-threshold` controls how many consecutive renewal failures are tolerated before
the lease is invalidated. It accepts `1` or `2`; the default `1` fails closed immediately. This
limit keeps the built-in heartbeat schedule from allowing the lease TTL to expire first.
`heartbeat-interval` can override the default `lease-ttl / 3`. It must be short enough that
`heartbeat-interval * heartbeat-failure-threshold < lease-ttl`; otherwise startup is rejected.
By default both backends renew at roughly one-third of the configured TTL; a successful renewal
resets the backend lease to the full TTL. If the scheduler is paused or the backend is unreachable until the
known local deadline, the lease is invalidated locally and ID generation stops.

`backend-operation-timeout` bounds Redis commands and JDBC lease statements. Configure the JDBC
`clock-skew-allowance` at least as large as the maximum clock difference between application
servers; it intentionally delays JDBC lease reuse to avoid overlapping owners. The heartbeat and
recovery loops use separate daemon executors so a blocked recovery acquisition cannot starve
heartbeats.

`acquisition-attempts` and `acquisition-retry-delay` apply only while the application is starting.
They allow a short database/Redis outage or a worker slot that is about to expire to recover without
immediately failing startup. Once a lease is lost after startup, ID generation remains fail-closed;
set `idkit.recovery.enabled: true` (the default) to periodically reacquire the lease and rebuild
the generator after the backend recovers. `idkit.recovery.retry-delay` controls that background
retry interval. During recovery, ID requests fail closed until a new lease is confirmed.
By default the starter leases the first available worker ID. Set `idkit.worker-id` to restrict
an instance to a specific worker slot; that same slot is used for recovery and startup fails if it
cannot be acquired after the configured retries. The value must be between `0` and
`worker-count - 1`.
When many instances share one backend, recovery retries use exponential backoff with jitter
(`retry-delay`, `retry-jitter`, and `max-retry-delay`) to avoid a synchronized reconnect storm.
`lease-namespace` isolates services sharing the same Redis key prefix or JDBC table; it does not
change the generated ID format, so it is appropriate when services have separate ID domains.
For large fleets, set `startup-jitter` (for example `5s`) to spread startup acquisition and
schema checks over time.
For Java callers, use `RecoveringLeasedIdGenerator.create(...)` with
`Supplier<WorkerIdLease>` and `Function<WorkerIdLease, IdGenerator<T>>` callbacks.
automatic reuse of the same worker identity is intentionally not performed.

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

The published library targets Java 11. Building with JDK 17 or newer is recommended;
Kotlin 2.3.21 also supports running the build on JDK 25.

Public API compatibility is checked automatically during `check` via Kotlin ABI validation.
When an intentional public API change is made, review it and update the reference dump with
`./gradlew updateKotlinAbi`.

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

UUID v7 fields can be decoded together:

```kotlin
val parts = UuidV7Parser.decompose(uuid)
parts.timestamp  // Instant
parts.counter    // 12-bit monotonic counter
parts.randomBits // 62-bit rand_b field
```

For Java callers, the common Snowflake configuration is also available through
`SnowflakeIdGenerator.create(workerId, datacenterId)`.

### Distributed worker identity

`WorkerIdLeaseStore` provides a storage-neutral contract for reserving worker/datacenter IDs.
Applications can implement it with Redis, a database, or a Kubernetes coordination service
without adding that infrastructure as a core idkit dependency. Use `WorkerIdLeases.acquire(...)`
to validate settings and fail fast on an identity collision.

For Lettuce-based Redis integration, add the optional module:

```kotlin
dependencies {
    implementation("io.github.dornol:idkit:3.2.1")
    implementation("io.github.dornol:idkit-redis:3.2.1")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
}
```

`RedisWorkerIdLeaseStore` reserves an identity once at startup and refreshes it in a background
heartbeat. It does not access Redis for each generated ID. If the heartbeat fails, wrap the local
generator in `LeasedIdGenerator`; subsequent calls then fail closed until the application obtains
a new lease.

Example:

```kotlin
val client = RedisClient.create("redis://localhost:6379")
val connection = client.connect()
val scheduler = Executors.newSingleThreadScheduledExecutor()
val leases = RedisWorkerIdLeaseStore(connection.sync(), scheduler)
val lease = leases.acquireAny(workerCount = 32, owner = hostname)

val generator = LeasedIdGenerator(
    SnowflakeIdGenerator(
        workerId = lease.workerId,
        datacenterId = lease.datacenterId,
    ),
    lease,
)

try {
    val id = generator.nextId()
} finally {
    lease.close()
    scheduler.shutdown()
    connection.close()
    client.shutdown()
}
```

If a heartbeat fails because the backend connection is temporarily unavailable, the last
confirmed local TTL remains usable; ID generation stops when that deadline expires. A definitive
ownership-loss response still follows the configured failure threshold. Once the lease is
invalidated, `LeasedIdGenerator` refuses subsequent ID requests. Applications should treat that
exception as a process health failure and obtain a new lease after recovery.

### Fencing downstream operations

Lease fencing tokens protect downstream resources from delayed work by an old owner. A downstream
adapter must compare tokens atomically; the included in-memory validator is suitable only within a
single JVM:

```kotlin
val validator = InMemoryFencingTokenValidator()
validator.requireNewer("orders-writer", lease.fencingToken)
// Apply the side effect only after the token is accepted.
```

For multiple processes, use the durable adapters. `JdbcFencingTokenValidator.initialize()` creates
its resource table, while `RedisFencingTokenValidator` uses an atomic Lua compare-and-set:

```kotlin
val validator = JdbcFencingTokenValidator(dataSource)
validator.initialize()
validator.requireNewer("orders-writer", lease.fencingToken)
```

Both adapters reject a lower or equal token, so a delayed worker cannot overwrite a newer owner.

Migration checklist for 3.1.0:

- Run `initialize()` during deployment with DDL permission, or pre-create the JDBC
  `fencing_token` column and validator table through your normal migration tool.
- Keep the same Redis `keyPrefix` across instances during a rolling deployment.
- Use `JdbcFencedOperationExecutor` or `RedisFencedScriptExecutor` for protected side effects;
  a standalone `requireNewer()` call followed by an unrelated write is not atomic.
- Treat `REJECTED_STALE` as a worker shutdown/recovery signal, not as a retry with the same token.

For true atomic protection, use `JdbcFencedOperationExecutor.executeWithConnection(...)` and run
the side effect with its supplied transaction connection. For Redis, use
`RedisFencedScriptExecutor` and perform the side effect inside the supplied Lua operation script
(do not add a Lua `return`);
ordinary code executed after a separate token check still has a race window.

For operations, `RedisWorkerIdLeaseStore.inspect(workerId, datacenterId)` returns the owner name,
non-reversible token fingerprint, held state, and remaining Redis TTL. Configure `RedisLeaseFailureListener` for a single
callback when heartbeat renewal fails or the lease is lost. Calling `close()` on the store releases
all leases it owns, and `MicrometerRedisLeaseMetrics` can expose lifecycle counters and the active
lease gauge when `micrometer-core` is present.

### JDBC worker leases

For environments that already have a relational database, use the optional `idkit-jdbc` module:

```kotlin
dependencies {
    implementation("io.github.dornol:idkit:3.2.1")
    implementation("io.github.dornol:idkit-jdbc:3.2.1")
    runtimeOnly("org.postgresql:postgresql:42.7.7") // or the JDBC driver for MySQL, MariaDB, SQL Server, or Oracle
}
```

The lease store uses a transaction and row lock. Call `initialize(workerCount, datacenterId)` once
to create the worker slots, or use `acquireAny(...)`, which initializes them automatically. The
JDBC connection is used only for acquisition, heartbeat, and release; ID generation remains local.
Built-in dialects are available for PostgreSQL, MySQL, MariaDB, Microsoft SQL Server, and Oracle:

```kotlin
JdbcLeaseDialect.POSTGRESQL
JdbcLeaseDialect.MYSQL       // MySQL
JdbcLeaseDialect.MARIADB    // MariaDB
JdbcLeaseDialect.MSSQL      // SQL Server row-lock hints
JdbcLeaseDialect.ORACLE     // Oracle MERGE and DDL handling
```

For operations, `JdbcWorkerIdLeaseStore.inspect(workerId, datacenterId)` returns a point-in-time
`JdbcLeaseStatus` with ownership, expiration, held state, and remaining TTL. Status snapshots expose
the owner name and a non-reversible token fingerprint, never the raw lease token. Configure
`JdbcLeaseFailureListener` to receive a single callback when a heartbeat fails or the lease row is
lost; the lease then becomes invalid and `LeasedIdGenerator` fails closed. Each acquired lease also
has a monotonically increasing `fencingToken` for rejecting stale workers in downstream systems.

Calling `JdbcWorkerIdLeaseStore.close()` releases every lease acquired from that store, which is
useful from an application shutdown hook. For Micrometer users, pass `MicrometerJdbcLeaseMetrics`
as the `metrics` option to expose acquired, failed, heartbeat, released, and active-lease meters.

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

## Worker ID auto-assignment

Running Snowflake/Flake in a distributed deployment requires a unique `workerId` per node. Rather than hand-wiring this into each pod, derive it from runtime context:

```kotlin
import io.github.dornol.idkit.worker.WorkerIdSource
import io.github.dornol.idkit.flake.SnowflakeIdGenerator

// Kubernetes StatefulSet: hostname like "api-server-3" → ordinal 3
val workerId = WorkerIdSource.fromPodOrdinal(bits = 5)
    ?: WorkerIdSource.fromEnv("WORKER_ID")

val gen = SnowflakeIdGenerator(
    workerId = workerId,
    datacenterId = WorkerIdSource.fromEnv("DC_ID", env = System.getenv()),
)
```

Available strategies:
- `hash(value, bits)` / `fromHostname(bits)` — stable `String.hashCode` of any identifier
- `parseOrdinal(hostname, bits)` / `fromPodOrdinal(bits)` — Kubernetes StatefulSet ordinal
- `fromEnv(name, env)` — explicit environment variable
- `fromNetworkInterface(bits)` — derived from the first non-loopback MAC address

The pure functions (`hash`, `parseOrdinal`, `fromEnv(..., env = ...)`) take their source as an argument, so deterministic tests can exercise the exact same logic without touching the JVM's environment.

## Clock injection

Every time-ordered generator accepts an optional `java.time.Clock`. Inject a fake clock for deterministic tests, a `Clock.fixed(...)` for snapshot tests, or a custom clock for offset/drift scenarios — no subclassing required.

```kotlin
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.testing.TestClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

// Fake clock that advances on demand
val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = clock)
val id1 = gen.nextId()
clock.advance(Duration.ofSeconds(5))
val id2 = gen.nextId()          // timestamp field is 5s ahead of id1

// Any java.time.Clock works — TestClock is just one option
val fixed: Clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
val snapshotGen = SnowflakeIdGenerator(workerId = 0, datacenterId = 0, clock = fixed)
```

The older `currentEpochMillis()` protected seam is still there for backward compatibility — existing tests that subclass the generator keep working.

## Testing

The `io.github.dornol.idkit.testing` package provides `TestClock` (now a `java.time.Clock` subtype) and matching generator factories.

```kotlin
import io.github.dornol.idkit.testing.TestClock
import io.github.dornol.idkit.testing.deterministicUlidIdGenerator
import io.github.dornol.idkit.testing.testSnowflakeIdGenerator
import java.time.Duration
import java.time.Instant

// Preferred (since 2.3.0): pass TestClock straight to the generator
val clock = TestClock(Instant.parse("2024-01-15T00:00:00Z"))
val snowflake = testSnowflakeIdGenerator(clock, workerId = 1, datacenterId = 2)
val id1 = snowflake.nextId()
clock.advance(Duration.ofSeconds(5))
val id2 = snowflake.nextId()        // timestamp portion is 5s ahead

// Byte-identical reproducible ULIDs (deterministic clock + zero randomness seed)
val ulid = deterministicUlidIdGenerator(clock)
val snapshot = List(5) { ulid.nextId() }   // always the same 5 strings
```

Companion factories: `testSnowflakeIdGenerator`, `testFlakeIdGenerator`, `testUlidIdGenerator`, `testUuidV7IdGenerator`, `deterministicUlidIdGenerator`.

## Edge-event listener

Install an optional `IdGeneratorListener` to observe rare, operationally-significant events. The default is `IdGeneratorListener.NOOP`, so generators with no listener pay zero cost.

idkit intentionally does **not** take a dependency on Micrometer / OpenTelemetry / any metrics facade. Wire your own in ~5 lines:

```kotlin
import io.github.dornol.idkit.IdGeneratorListener
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.micrometer.core.instrument.MeterRegistry

val listener = object : IdGeneratorListener {
    private val regressions = meterRegistry.counter("idkit.clock.regression")
    private val overflows   = meterRegistry.counter("idkit.sequence.overflow")
    override fun onClockRegression(driftMillis: Long) { regressions.increment() }
    override fun onSequenceOverflow() { overflows.increment() }
}
val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, listener = listener)
```

Events:
- `onClockRegression(driftMillis)` — wall clock moved backwards. Flake/Snowflake fire this whether the regression is absorbed under the tolerance budget or is large enough to trigger `ClockMovedBackwardsException`; ULID/UUID v7 fire on strict-backwards observations (same-ms re-entry is NOT reported). Filter by `driftMillis` if you only want fatal regressions.
- `onSequenceOverflow()` — Flake/Snowflake only. Sequence bits for the current timestamp slice are exhausted. In strict mode (`clockRegressionTolerance = Duration.ZERO`) the generator busy-waits for the next slice; in tolerant mode (default) it borrows one slice ahead. Sustained firing indicates throughput > 4,096 ids/ms on a default Snowflake.
- `onCounterBorrow()` — UUID v7 only. The 12-bit monotonic counter overflowed within one ms and the embedded timestamp was advanced 1 ms ahead of the wall clock.

There is intentionally **no `onIdGenerated` callback** — it would fire millions of times per second on the hot path. Counters for "ids generated" belong at the downstream request / insert layer.

## Common interface

All generators implement `IdGenerator<T>`.

```kotlin
interface IdGenerator<T> {
    fun nextId(): T
    fun nextIds(count: Int): List<T>   // since 2.3.0
}
```

`nextIds(count)` is useful for batch workloads (e.g., pre-allocating ids for a bulk SQL insert). For generators backed by `@Synchronized` (Snowflake/Flake/ULID), the overridden implementation holds the monitor once for the whole batch — noticeably cheaper than a `List(N) { gen.nextId() }` loop under contention. UUID v7 and NanoID fall back to the default implementation because their internal strategies (CAS, per-thread RNG) are not lock-based.

```kotlin
val ids: List<Long> = snowflakeGen.nextIds(1_000)
```

## Bean Validation (optional)

`jakarta.validation-api` is declared as a `compileOnly` dependency — the annotations are available if your runtime pulls in a Jakarta Validation engine (Spring Boot, Quarkus, Hibernate Validator), otherwise they add nothing to your classpath.

```kotlin
import io.github.dornol.idkit.validation.ValidUlid
import io.github.dornol.idkit.validation.ValidUuidV7
import java.util.UUID

data class CreateOrderRequest(
    @field:ValidUlid val orderId: String,
    @field:ValidUuidV7 val customerId: UUID,
    @field:ValidUuidV7 val correlationId: String?,   // also works on textual UUIDs
)
```

- `@ValidUlid` — checks the value is a 26-character Crockford Base32 encoded ULID.
- `@ValidUuidV7` — checks the value is a UUID (or UUID string) with `version() == 7`.
- `null` is accepted by both; compose with `@NotNull` if you need to reject it.

## Behavior and caveats

### Thread safety
- **Snowflake / Flake / ULID**: `nextId()` is `@Synchronized`.
- **UUID v7**: uses an internal `AtomicLong` with CAS.
- **NanoID**: relies on a thread-safe `SecureRandom` (serialized per JDK contract).

### Clock regression (`System.currentTimeMillis()` returns a value smaller than the last observation)
- **Snowflake / Flake** (since 3.0.0): absorb regressions up to `clockRegressionTolerance` (default `Duration.ofMillis(10)`) by pinning the internal timestamp to the last-emitted value. Regressions beyond the budget throw `ClockMovedBackwardsException` (extends `IllegalStateException`). The internal state is not mutated before a throw, so the same instance is usable once the clock recovers.
  ```kotlin
  // Default: small NTP slews / container jitter are absorbed; large steps still throw.
  val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)

  try {
      val id = gen.nextId()
  } catch (e: ClockMovedBackwardsException) {
      // e.driftAmount tells you how far the clock moved back — back off and retry,
      // or alert ops.
  }

  // Opt into the pre-3.0.0 fail-fast behaviour if you want every backwards tick surfaced:
  val strict = SnowflakeIdGenerator(
      workerId = 1,
      datacenterId = 2,
      clockRegressionTolerance = Duration.ZERO,
  )
  ```
- **UUID v7 / ULID**: keep the previously observed timestamp and increment the counter/randomness to preserve monotonicity. No drift cap — these generators never throw on regression.

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

## Benchmarks

JMH benchmarks live in `src/jmh/kotlin/` and are wired via the `me.champeau.jmh` Gradle plugin. They are not part of the published jar.

```bash
# Run all benchmarks
./gradlew jmh

# Run a single benchmark class / method
./gradlew jmh -Pjmh.includes=GeneratorThroughputBenchmark
./gradlew jmh -Pjmh.includes=BulkBenchmark.snowflakeBatch
```

Suites:
- `GeneratorThroughputBenchmark` — single-thread `nextId()` latency for all 5 generators.
- `ContentionBenchmark` — 8-thread throughput; highlights the difference between `@Synchronized` (Flake/Snowflake/ULID), lock-free CAS (UUID v7), and per-thread `SecureRandom` (NanoID).
- `BulkBenchmark` — `nextId()` loop vs `nextIds(batch)` at batch sizes 10 / 100 / 1000 for the synchronized generators.

## Logging

This library uses the SLF4J API. Without a binding it falls back to the NOP logger. Add `slf4j-simple`, `logback-classic`, or similar if you want output.

## Publishing (maintainer notes)

Configured to publish to the Central Publishing Portal via the Vanniktech Maven Publish plugin.
The release workflow uses JDK 21 because the bundled Dokka version is not compatible with JDK 25.
Set the following keys in `~/.gradle/gradle.properties`:

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
