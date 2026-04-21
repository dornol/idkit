# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `io.github.dornol.idkit.worker.WorkerIdSource` — helpers for deriving a `workerId` (and optionally a `datacenterId`) from runtime context. Pure functions (`hash`, `parseOrdinal`, `fromEnv(..., env = ...)`) plus convenience wrappers that read from the live environment (`fromHostname`, `fromPodOrdinal`, `fromNetworkInterface`). Separates "where the input comes from" from "how it's mapped to an id", so the same logic can be exercised deterministically in tests.
- `io.github.dornol.idkit.testing.TestClock` — a mutable `AtomicLong`-backed clock for deterministic testing. Ships with factory functions (`testSnowflakeIdGenerator`, `testFlakeIdGenerator`, `testUlidIdGenerator`, `testUuidV7IdGenerator`) that install the clock into each generator family's `currentEpochMillis()` seam.
- `deterministicUlidIdGenerator(clock)` — a ULID generator that is byte-identically reproducible across test runs (deterministic clock + zero-seeded randomness + spec's increment-by-one path).
- `UuidV7IdGenerator` now exposes a `protected open fun currentEpochMillis()` test seam, matching the seam on `FlakeIdGenerator` and `UlidIdGenerator`. `nextId()` is now `final override` so the synchronization guarantee cannot be bypassed by a subclass.

### Changed

- `SnowflakeIdGenerator` is now `open class` so the testing-utility factory can return a subclass that overrides `currentEpochMillis()`. `UuidV7IdGenerator` likewise became `open class`. Both changes are source- and binary-compatible.

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

[2.1.0]: https://github.com/dornol/idkit/releases/tag/2.1.0
[2.0.1]: https://github.com/dornol/idkit/releases/tag/2.0.1
[2.0.0]: https://github.com/dornol/idkit/releases/tag/2.0.0
[1.2.1]: https://github.com/dornol/idkit/releases/tag/1.2.1
[1.2.0]: https://github.com/dornol/idkit/releases/tag/1.2.0
[1.1.0]: https://github.com/dornol/idkit/releases/tag/1.1.0
