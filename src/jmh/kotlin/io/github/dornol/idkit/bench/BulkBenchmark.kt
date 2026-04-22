package io.github.dornol.idkit.bench

import io.github.dornol.idkit.flake.SnowflakeIdGenerator
import io.github.dornol.idkit.ulid.UlidIdGenerator
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.TimeUnit

/**
 * Compares `List(N) { gen.nextId() }` (loop) vs `gen.nextIds(N)` (batch).
 *
 * For generators backed by `@Synchronized` (Snowflake/Flake/ULID), `nextIds` holds the monitor
 * once across the whole batch and should outperform the loop by the saved
 * acquire/release pairs. Expected speedup grows with [batchSize].
 *
 * Run with `./gradlew jmh -Pjmh.includes=BulkBenchmark`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class BulkBenchmark {

    @Param("10", "100", "1000")
    var batchSize: Int = 0

    lateinit var snowflake: SnowflakeIdGenerator
    lateinit var ulid: UlidIdGenerator

    @Setup
    fun setUp() {
        snowflake = SnowflakeIdGenerator(workerId = 1, datacenterId = 1)
        ulid = UlidIdGenerator()
    }

    @Benchmark
    fun snowflakeLoop(): List<Long> = List(batchSize) { snowflake.nextId() }

    @Benchmark
    fun snowflakeBatch(): List<Long> = snowflake.nextIds(batchSize)

    @Benchmark
    fun ulidLoop(): List<String> = List(batchSize) { ulid.nextId() }

    @Benchmark
    fun ulidBatch(): List<String> = ulid.nextIds(batchSize)
}
