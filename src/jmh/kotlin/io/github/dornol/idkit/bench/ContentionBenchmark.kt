package io.github.dornol.idkit.bench

import io.github.dornol.idkit.flake.FlakeIdGenerator
import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.nanoid.NanoIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import java.util.concurrent.TimeUnit

/**
 * Multi-threaded contention characteristics. Uses 8 worker threads to stress the generators'
 * concurrency strategies:
 *  - Flake/Snowflake/ULID: `@Synchronized` on a shared monitor
 *  - UUID v7: lock-free CAS on a single `AtomicLong`
 *  - NanoID: per-thread `SecureRandom` (no shared state)
 *
 * Run with `./gradlew jmh -Pjmh.includes=ContentionBenchmark`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(8)
open class ContentionBenchmark {

    lateinit var snowflake: SnowflakeIdGenerator
    lateinit var flake: FlakeIdGenerator
    lateinit var uuidV7: UuidV7IdGenerator
    lateinit var ulid: UlidIdGenerator
    lateinit var nanoid: NanoIdGenerator

    @Setup
    fun setUp() {
        snowflake = SnowflakeIdGenerator(workerId = 1, datacenterId = 1)
        flake = FlakeIdGenerator(datacenterId = 1, workerId = 1)
        uuidV7 = UuidV7IdGenerator()
        ulid = UlidIdGenerator()
        nanoid = NanoIdGenerator()
    }

    @Benchmark
    fun snowflakeContended(): Long = snowflake.nextId()

    @Benchmark
    fun flakeContended(): Long = flake.nextId()

    @Benchmark
    fun uuidV7Contended() = uuidV7.nextId()

    @Benchmark
    fun ulidContended(): String = ulid.nextId()

    @Benchmark
    fun nanoidContended(): String = nanoid.nextId()
}
