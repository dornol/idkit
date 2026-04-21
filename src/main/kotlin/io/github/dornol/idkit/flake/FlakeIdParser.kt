package io.github.dornol.idkit.flake

import java.time.Instant

/**
 * Decomposes a Flake/Snowflake [Long] id back into its `(timestamp, datacenterId, workerId,
 * sequence)` components.
 *
 * The parser must be constructed with the same bit layout, [timestampDivisor], and
 * [epochStart] as the generator that produced the id — otherwise the decomposition will be
 * garbage. Use [of] to build a parser that mirrors an existing [FlakeIdGenerator].
 *
 * Typical uses: correlating ids with log timestamps, attributing ids to the worker that
 * produced them during incident triage, or auditing sequence densities per ms.
 *
 * @since 2.1.0
 */
class FlakeIdParser(
    val timestampBits: Int = 41,
    val datacenterIdBits: Int = 5,
    val workerIdBits: Int = 5,
    val timestampDivisor: Long = 1L,
    val epochStart: Instant = Instant.EPOCH,
) {

    private val sequenceBits: Int
    private val maxSequence: Long
    private val maxWorkerIdL: Long
    private val maxDatacenterIdL: Long
    private val timestampLeftShift: Int
    private val datacenterIdLeftShift: Int
    private val workerIdLeftShift: Int
    private val epochStartMillis: Long = epochStart.toEpochMilli()

    init {
        require(timestampBits > 0) { "timestampBits must be greater than 0, but was $timestampBits" }
        require(timestampDivisor > 0) { "timestampDivisor must be greater than 0, but was $timestampDivisor" }
        require(datacenterIdBits in 1..5) {
            "datacenterIdBits must be between 1 and 5, but was $datacenterIdBits"
        }
        require(workerIdBits in 1..31) {
            "workerIdBits must be between 1 and 31, but was $workerIdBits"
        }

        val unusedBits = 1
        val totalBits = unusedBits + timestampBits + datacenterIdBits + workerIdBits
        require(totalBits <= 63) {
            "Total bits (unused=$unusedBits + timestampBits=$timestampBits + " +
                    "datacenterIdBits=$datacenterIdBits + workerIdBits=$workerIdBits = $totalBits) " +
                    "cannot exceed 63 (need at least 1 bit for sequence)"
        }

        sequenceBits = 64 - totalBits
        maxSequence = (1L shl sequenceBits) - 1
        maxWorkerIdL = (1L shl workerIdBits) - 1
        maxDatacenterIdL = (1L shl datacenterIdBits) - 1
        timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
        datacenterIdLeftShift = workerIdBits + sequenceBits
        workerIdLeftShift = sequenceBits
    }

    /** Returns the [Instant] at which this id was generated. */
    fun timestampOf(id: Long): Instant {
        val delta = id ushr timestampLeftShift
        return Instant.ofEpochMilli(delta * timestampDivisor + epochStartMillis)
    }

    /** Returns the datacenter id embedded in this id. */
    fun datacenterOf(id: Long): Int =
        ((id ushr datacenterIdLeftShift) and maxDatacenterIdL).toInt()

    /** Returns the worker id embedded in this id. */
    fun workerOf(id: Long): Int =
        ((id ushr workerIdLeftShift) and maxWorkerIdL).toInt()

    /** Returns the sequence counter value for this id (within its millisecond slice). */
    fun sequenceOf(id: Long): Long = id and maxSequence

    /** Decomposes [id] into all four components in a single call. */
    fun decompose(id: Long): FlakeComponents = FlakeComponents(
        timestamp = timestampOf(id),
        datacenterId = datacenterOf(id),
        workerId = workerOf(id),
        sequence = sequenceOf(id),
    )

    companion object {
        /**
         * Constructs a parser that mirrors [generator]'s bit layout, divisor, and epoch.
         * This is the recommended factory when the generator lives in the same process as
         * the parsing code.
         */
        fun of(generator: FlakeIdGenerator): FlakeIdParser = FlakeIdParser(
            timestampBits = generator.timestampBits,
            datacenterIdBits = generator.datacenterIdBits,
            workerIdBits = generator.workerIdBits,
            timestampDivisor = generator.timestampDivisor,
            epochStart = generator.epochStart,
        )
    }
}

/**
 * All four components of a Flake/Snowflake id, produced by [FlakeIdParser.decompose].
 *
 * @since 2.1.0
 */
data class FlakeComponents(
    val timestamp: Instant,
    val datacenterId: Int,
    val workerId: Int,
    val sequence: Long,
)
