package io.github.dornol.idkit

/**
 * Optional listener for significant, **edge** events during id generation.
 *
 * All methods default to no-op, so implementers override only the events they care about.
 *
 * ### Threading contract
 *
 * Callbacks run synchronously on the generating thread. For Flake / Snowflake / ULID the
 * thread still holds the generator's `@Synchronized` monitor when the callback fires —
 * **any blocking, I/O, or lock acquisition in the listener blocks every concurrent caller
 * of `nextId()` on that generator instance.** For UUID v7 the callback fires outside the
 * CAS region but still on the generating thread. Keep listener bodies to simple counter
 * increments or atomic field updates.
 *
 * ### What this does NOT report
 *
 * There is intentionally **no per-id counter** — a `nextId()` counter here would fire
 * millions of times per second on a hot path, contradict zero-overhead defaults, and
 * usually duplicates downstream request/insert counters you already have. Only the
 * edge events below are reported; they are rare and operationally actionable.
 *
 * ### Wiring to a metrics backend
 *
 * idkit does not pull in Micrometer, OpenTelemetry, or any metrics facade. Bring your own:
 *
 * ```kotlin
 * val meterRegistry: MeterRegistry = ...
 * val listener = object : IdGeneratorListener {
 *     private val regressions = meterRegistry.counter("idkit.clock.regression")
 *     private val overflows  = meterRegistry.counter("idkit.sequence.overflow")
 *     override fun onClockRegression(driftMillis: Long) = regressions.increment()
 *     override fun onSequenceOverflow() = overflows.increment()
 * }
 * val gen = SnowflakeIdGenerator(workerId = 1, datacenterId = 2, listener = listener)
 * ```
 *
 * @since 2.3.0
 */
interface IdGeneratorListener {

    /**
     * The wall clock was observed to regress relative to a previously emitted id.
     *
     *  - **Flake/Snowflake**: fired immediately before [io.github.dornol.idkit.flake.ClockMovedBackwardsException]
     *    is thrown.
     *  - **ULID / UUID v7**: fired when the previously observed timestamp is re-used because
     *    the current wall-clock reading is *strictly smaller* (same-ms re-entry is not a
     *    regression and is not reported).
     *
     * [driftMillis] is always real wall-clock milliseconds — Flake's `timestampDivisor` is
     * factored out before the callback, so callers across all generators observe a uniform
     * "how far back did the clock jump" value.
     *
     * A single regression event may fire on every subsequent `nextId()` call until the wall
     * clock catches back up. Use a rate-limited counter / gauge if you need one-shot alerts.
     */
    fun onClockRegression(driftMillis: Long) {}

    /**
     * Flake/Snowflake: the sequence field for the current timestamp slice was exhausted and
     * the generator is busy-waiting for the next slice. On a default Snowflake (12 sequence
     * bits) this fires when throughput exceeds ~4,096 ids per ms on a single generator.
     *
     * Sustained firing indicates the generator is bottlenecked on sequence bits, not CPU —
     * consider sharding (more workerIds) or a coarser `timestampDivisor`.
     */
    fun onSequenceOverflow() {}

    /**
     * UUID v7: the 12-bit monotonic counter overflowed within a single millisecond, so the
     * embedded timestamp was advanced ("borrowed") by 1 ms ahead of the wall clock.
     *
     * A single occurrence is harmless. Sustained firing indicates id generation rate exceeds
     * ~4,096 ids/ms on this generator instance; the embedded timestamp will drift ahead of
     * the wall clock until load drops. External observers may see UUIDs whose timestamp
     * component lies slightly in the future.
     */
    fun onCounterBorrow() {}

    companion object {
        /** No-op listener used as the default when the caller does not supply one. */
        val NOOP: IdGeneratorListener = object : IdGeneratorListener {}
    }
}
