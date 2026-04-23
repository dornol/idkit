package io.github.dornol.idkit.testutil

import io.github.dornol.idkit.IdGeneratorListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Listener that records every callback for inspection from tests.
 *
 *  - [clockRegressions], [sequenceOverflows], [counterBorrows] count their respective events.
 *  - [lastDriftMillis] holds the most recent drift reported via [onClockRegression]; `-1L`
 *    means no regression has been observed yet.
 *
 * All counters are [AtomicLong] so concurrent generators can record without locking.
 */
internal class RecordingListener : IdGeneratorListener {
    val clockRegressions: AtomicLong = AtomicLong(0L)
    val sequenceOverflows: AtomicLong = AtomicLong(0L)
    val counterBorrows: AtomicLong = AtomicLong(0L)
    @Volatile var lastDriftMillis: Long = -1L

    override fun onClockRegression(driftMillis: Long) {
        clockRegressions.incrementAndGet()
        lastDriftMillis = driftMillis
    }

    override fun onSequenceOverflow() {
        sequenceOverflows.incrementAndGet()
    }

    override fun onCounterBorrow() {
        counterBorrows.incrementAndGet()
    }
}
