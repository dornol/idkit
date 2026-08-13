package io.github.dornol.idkit.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdbcLeaseStatusTest {
    @Test
    fun `status exposes held and remaining ttl`() {
        val status = JdbcLeaseStatus(2, 3, "node", "fingerprint", 4L, 1_500, observedAtMillis = 1_000)

        assertTrue(status.isHeld)
        assertEquals(500, status.remainingTtlMillis)
    }

    @Test
    fun `expired and empty statuses are not held`() {
        assertFalse(JdbcLeaseStatus(0, 0, "node", "fingerprint", 4L, 999, observedAtMillis = 1_000).isHeld)
        assertFalse(JdbcLeaseStatus(0, 0, null, null, 0L, null, observedAtMillis = 1_000).isHeld)
    }
}
