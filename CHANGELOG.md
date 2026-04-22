# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Minor release prep. Adds batch id generation, Jakarta Bean Validation constraints, a JMH benchmark suite, a `java.time.Clock` seam unified across generators, and an edge-event listener interface. All changes are additive — existing callers and tests compile and run without modification.

### Added

- `IdGenerator.nextIds(count: Int): List<T>` — default interface method that generates `count` ids in one call. `FlakeIdGenerator` and `UlidIdGenerator` override it so their `@Synchronized` monitor is acquired once for the whole batch rather than once per id, which is measurably cheaper under contention for pre-allocation workloads (bulk SQL inserts, etc.). `UuidV7IdGenerator` and `NanoIdGenerator` use the default loop because their internal strategies (CAS, per-thread `SecureRandom`) are not lock-based and get no benefit from batching the sync region.
- `io.github.dornol.idkit.validation` — Jakarta Bean Validation constraints for ULID and UUID v7. `@ValidUlid` accepts `String`/`CharSequence` and delegates to `UlidParser.isValid`. `@ValidUuidV7` applies to both `java.util.UUID` and textual UUID strings (the string form parses then checks `version() == 7`). `null` is accepted by both — compose with `@NotNull` if you need to reject it. The `jakarta.validation-api` dependency is declared `compileOnly`, so consumers who do not wire a validation engine on their classpath incur no transitive cost.
- `UuidV7Parser.isValid(uuid: UUID)` / `isValid(text: CharSequence)` — boolean mirror of `UlidParser.isValid`, routed through by the new `@ValidUuidV7` validators so the v7 version-check lives in exactly one place.
- JMH benchmark suite under `src/jmh/kotlin/` (wired via the `me.champeau.jmh` Gradle plugin):
  - `GeneratorThroughputBenchmark` — single-thread `nextId()` latency across all 5 generators.
  - `ContentionBenchmark` — 8-thread throughput; surfaces the cost difference between `@Synchronized`, CAS, and per-thread state strategies.
  - `BulkBenchmark` — compares a `List(N) { gen.nextId() }` loop against `gen.nextIds(N)` at batch sizes 10/100/1000 for Snowflake and ULID.
  Run with `./gradlew jmh` (or `-Pjmh.includes=<pattern>` to scope). The benchmarks are excluded from the published jar.
- **`java.time.Clock` seam** — `FlakeIdGenerator`, `SnowflakeIdGenerator`, `UuidV7IdGenerator`, and `UlidIdGenerator` each gain an optional `clock: Clock = Clock.systemUTC()` constructor parameter. New code can inject a fake or fixed clock directly instead of subclassing and overriding `currentEpochMillis()`:
  ```kotlin
  val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, clock = Clock.fixed(...))
  ```
  The `protected open fun currentEpochMillis()` test seam is preserved for backward compatibility and now defaults to `clock.millis()`.
- `TestClock` now extends `java.time.Clock`, so it can be passed directly to any generator's `clock` parameter. Factory functions in `io.github.dornol.idkit.testing` (`testSnowflakeIdGenerator`, `testFlakeIdGenerator`, `testUlidIdGenerator`, `testUuidV7IdGenerator`) have been simplified internally to route through the new `clock` parameter instead of subclassing — external API is unchanged.
- **`IdGeneratorListener`** — optional callback hook for rare, operationally actionable events. Default is `IdGeneratorListener.NOOP`, so generators pay zero cost unless a listener is installed. Fires:
  - `onClockRegression(driftMillis: Long)` — Flake/Snowflake (just before throwing `ClockMovedBackwardsException`), ULID and UUID v7 (on strict-backwards clock observations; same-ms re-entry is NOT reported).
  - `onSequenceOverflow()` — Flake/Snowflake, when the sequence bits for the current timestamp slice are exhausted and the generator busy-waits for the next slice.
  - `onCounterBorrow()` — UUID v7, when the 12-bit monotonic counter overflows within one ms and the embedded timestamp is advanced ahead of the wall clock.
  There is intentionally no per-id counter — that kind of metric is better collected at the downstream request/insert layer. idkit does not take a dependency on Micrometer, OpenTelemetry, or any metrics facade; users wire their own (see README for a Micrometer example).

## [2.2.0] - 2026-04-21

Minor release. Adds runtime-context `workerId` helpers, a test-clock utility suite, and a clock seam on UUID v7 so it lines up with the other generators.

### Added

- `io.github.dornol.idkit.worker.WorkerIdSource` — helpers for deriving a `workerId` (and optionally a `datacenterId`) from runtime context. Pure functions (`hash`, `parseOrdinal`, `fromEnv(..., env = ...)`) plus convenience wrappers that read from the live environment (`fromHostname`, `fromPodOrdinal`, `fromNetworkInterface`). Separates "where the input comes from" from "how it's mapped to an id", so the same logic can be exercised deterministically in tests.
- `io.github.dornol.idkit.testing.TestClock` — a mutable `AtomicLong`-backed clock for deterministic testing. Exposes `set`/`advance`/`regress` in both `Duration` and `Long` forms. Ships with factory functions (`testSnowflakeIdGenerator`, `testFlakeIdGenerator`, `testUlidIdGenerator`, `testUuidV7IdGenerator`) that install the clock into each generator family's `currentEpochMillis()` seam.
- `deterministicUlidIdGenerator(clock)` — a ULID generator that is byte-identically reproducible across test runs (deterministic clock + zero-seeded randomness + spec's increment-by-one path). Useful for snapshot assertions.
- `UuidV7IdGenerator` now exposes a `protected open fun currentEpochMillis()` test seam, matching the seam on `FlakeIdGenerator` and `UlidIdGenerator`. `nextId()` is `final override` so the synchronization guarantee cannot be bypassed by a subclass.

### Changed

- `SnowflakeIdGenerator` is now `open class` so the testing-utility factory can return a subclass that overrides `currentEpochMillis()`. `UuidV7IdGenerator` likewise became `open class`. Both changes are source- and binary-compatible (visibility widening only).

## [2.1.0] - 2026-04-21

Minor release. Adds two new generator families (ULID, NanoID) and a parser trio for the time-ordered generators.

### Added

- `UlidIdGenerator` — produces 26-character [ULIDs](https://github.com/ulid/spec) encoded in Crockford's Base32 (48-bit timestamp + 80-bit randomness). Monotonic within a millisecond via the spec's randomness-increment profile, handles clock regression by holding the last observed timestamp, throws `IllegalStateException` on the practically unreachable 80-bit overflow. Thread-safe via `@Synchronized`; exposes `currentEpochMillis()` as a `protected open` test seam.
- `NanoIdGenerator` — compact, URL-safe, random string ids (21 chars / 64-char URL-safe alphabet by default, both configurable). Unlike the other generators, NanoID is **not time-ordered**; it fills the "opaque public identifier" slot (short URLs, session tokens, invite codes). Backed by a per-thread `java.security.SecureRandom` to avoid the shared-instance lock; collision profile matches UUID v4.
- `FlakeIdParser` — decomposes a Flake/Snowflake `Long` id into `FlakeComponents(timestamp, datacenterId, workerId, sequence)`. Ships with `FlakeIdParser.of(generator)` for same-process parsing; can also be constructed standalone with just the layout when parsing ids from another service.
- `UlidParser` — parses ULID strings: `timestampOf(String)`, `toBytes(String)` (16-byte big-endian), `fromBytes(ByteArray)`, `isValid(String)`.
- `UuidV7Parser` — parses UUID v7 values: `timestampOf(UUID)` (strict v7 check) and `rawTimestampOf(UUID)` (no version check, for interop with UUID v6 or pre-RFC-9562 v7 drafts).

### Changed

- ULID Base32 encode/decode logic extracted into package-internal top-level helpers in `UlidCodec.kt`. The generator and the parser share a single implementation — one source of truth for the bit layout.
- Flake/Snowflake bit-layout validation extracted into an internal `validateFlakeLayout(...)` helper so `FlakeIdGenerator` and `FlakeIdParser` cannot drift on which layouts they accept.
- Concurrent-test scaffolding extracted to `io.github.dornol.idkit.testutil.collectConcurrently` (test-only), eliminating six near-duplicate thread-pool + latch + concurrent-set blocks. As a side effect, worker exceptions now propagate to the caller's thread instead of being silently dropped.

## [2.0.1] - 2026-04-21

Patch release. Closes a narrow race in clock-regression handling discovered in a follow-up review. No API changes.

### Fixed

- `FlakeIdGenerator.waitForNextSlice` now throws `ClockMovedBackwardsException` when the wall clock is observed to regress during the busy-spin for the next time slice. Previously the top-level regression check in `nextId()` only covered the initial clock read; if NTP back-jumped after sequence overflow but before the first spin iteration, the loop would wait for the wall clock to catch up — potentially for minutes or hours, pinning one CPU core.

### Changed

- Documentation is now maintained in English. All KDoc, inline comments, and the README have been translated.
- POM metadata: added `inceptionYear` and an `issueManagement` entry pointing at GitHub Issues.

### Removed (internal)

- `LongHashMapBackedMutableMap` test helper. The comment claimed it avoided boxing, but the underlying `ConcurrentHashMap<Long, Boolean>` still boxed `Long` keys, so the class was a dead optimization. Tests now use `ConcurrentHashMap` directly.

## [2.0.0] - 2026-04-21

Large correctness and consistency release. Multiple breaking changes — consumers upgrading from 1.x should review the migration notes below.

### ⚠️ Breaking Changes

- **`SnowflakeIdGenerator` / `FlakeIdGenerator` parameter types**: `workerId` and `datacenterId` are now `Int` (were `Long`). Callers that pass `Long` literals or variables must switch to `Int`.
  - Before: `SnowflakeIdGenerator(workerId = 1L, dataCenterId = 2L)`
  - After:  `SnowflakeIdGenerator(workerId = 1, datacenterId = 2)`
- **`SnowflakeIdGenerator` parameter rename**: `dataCenterId` → `datacenterId` (matches `FlakeIdGenerator`). Kotlin named-argument callers must update.
- **`FlakeIdGenerator.maxWorkerId` / `maxDatacenterId`**: public `val` type narrowed from `Long` to `Int`.
- **`workerIdBits` upper bound**: now validated to be `1..31` (previously only `> 0`). Allows up to ~2.1B workers per shard — far beyond any realistic deployment.
- **`timestampBits > 0`**: now validated at construction. `timestampBits = 0` was previously accepted but failed at the first `nextId()` call.
- **Clock regression handling** (`FlakeIdGenerator` / `SnowflakeIdGenerator`): now throws `ClockMovedBackwardsException` (extends `IllegalStateException`) instead of silently pinning to the last observed timestamp. The legacy pin behavior, combined with a sequence overflow, could busy-spin for minutes or hours, consuming a full CPU core.
- **UUID v7 `rand_a` bit semantics**: the 12 `rand_a` bits now carry a monotonic counter (RFC 9562 §6.2 Method 2), not random data. Generated UUIDs are still spec-compliant v7 UUIDs, but callers that depended on `rand_a` being fully random must re-evaluate their assumptions.
- **Timestamp delta precision**: the `timestamp` field of generated IDs is now computed as `(now - epoch) / divisor` rather than `(now / divisor) - (epoch / divisor)`. When `timestampDivisor > 1` and `epoch` is not a multiple of the divisor, IDs will differ by up to ±1 unit in the timestamp field from those generated by 1.x.

### Added

- `io.github.dornol.idkit.flake.ClockMovedBackwardsException` — thrown on observed clock regression; carries `driftAmount` and `timestampDivisor` for programmatic handling.
- `FlakeIdGenerator.currentEpochMillis()` — `protected open` seam for injecting a fake clock in tests without reflection.
- UUID v7 monotonic counter guarantees strictly increasing `mostSignificantBits` even within a single millisecond.
- Test coverage for intra-ms monotonicity, global monotonicity under concurrency, clock regression handling, and timestamp-delta precision.

### Changed

- Internal refactor: `timestamp` and `timestampDelta` unified into a single delta-from-epoch value, eliminating an edge case where the same-slice check could diverge from the delta actually embedded in the ID.
- UUID v7 internals: `(timestamp:52 | counter:12)` packed into a single `AtomicLong`; updated atomically via CAS.
- Hot path micro-opt: UUID v7 `rand_b` now uses `ThreadLocalRandom.nextLong() and MASK` instead of the bounded `nextLong(0, 1L shl 62)` variant.
- Error messages on constructor validation now describe the constraint reason (e.g., "need at least 1 bit for sequence").
- CI now validates the Gradle wrapper jar on every push/PR.

### Fixed

- Constructor-validation failures no longer leave the generator in a half-initialized state. Internal mutable state (`lastGeneratedTimestamp`, `sequenceCounter`) is only committed once every check in `nextId()` passes, so a caught `ClockMovedBackwardsException` leaves the generator reusable once the wall clock recovers.
- `gradlew` is now marked executable in the repository (was `100644`, now `100755`).

### Migration

```kotlin
// 1.x
val gen = SnowflakeIdGenerator(workerId = 1L, dataCenterId = 2L)

// 2.0
val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2)

// Clock regression: catch the new exception
try {
    id = gen.nextId()
} catch (e: ClockMovedBackwardsException) {
    // back off and retry, or alert ops
}
```

## [1.2.1] - 2026-01

### Fixed

- Typo: internal constant `UNUSE_BITS` → `UNUSED_BITS`.
- Improved constructor-validation error messages.

## [1.2.0] - 2025-11

### Added

- `FlakeIdGenerator`: Snowflake-derived generator with configurable bit layout, epoch, and timestamp resolution.

## [1.1.0] - 2025-11

### Added

- `UuidV7IdGenerator` producing `java.util.UUID` with `version = 7` and `variant = 0b10`.

## [1.0.0] / Earlier

- Initial `SnowflakeIdGenerator` with Twitter Snowflake bit layout (41/5/5/12).
- Vanniktech Maven Publish plugin for Central Publishing Portal.

[2.2.0]: https://github.com/dornol/idkit/releases/tag/2.2.0
[2.1.0]: https://github.com/dornol/idkit/releases/tag/2.1.0
[2.0.1]: https://github.com/dornol/idkit/releases/tag/2.0.1
[2.0.0]: https://github.com/dornol/idkit/releases/tag/2.0.0
[1.2.1]: https://github.com/dornol/idkit/releases/tag/1.2.1
[1.2.0]: https://github.com/dornol/idkit/releases/tag/1.2.0
[1.1.0]: https://github.com/dornol/idkit/releases/tag/1.1.0
