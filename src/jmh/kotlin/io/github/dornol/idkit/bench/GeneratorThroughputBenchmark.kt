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
import java.util.concurrent.TimeUnit

/**
 * Single-threaded throughput of each generator family's `nextId()`.
 *
 * Run with `./gradlew jmh -Pjmh.includes=GeneratorThroughputBenchmark`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class GeneratorThroughputBenchmark {

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
    fun snowflakeNextId(): Long = snowflake.nextId()

    @Benchmark
    fun flakeNextId(): Long = flake.nextId()

    @Benchmark
    fun uuidV7NextId() = uuidV7.nextId()

    @Benchmark
    fun ulidNextId(): String = ulid.nextId()

    @Benchmark
    fun nanoidNextId(): String = nanoid.nextId()
}
