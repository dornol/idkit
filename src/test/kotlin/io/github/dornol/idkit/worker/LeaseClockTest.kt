package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeaseClockTest {
    @Test
    fun `fixed clock returns deterministic time`() {
        val clock = LeaseClock { 1234L }

        assertEquals(1234L, clock.millis())
    }
}
