package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkerIdSourceTest {

    @Test
    fun `hash is deterministic and within bit range`() {
        repeat(100) {
            val v = WorkerIdSource.hash("api-server-01", bits = 10)
            assertTrue(v in 0..1023, "hash out of 10-bit range: $v")
        }
        // Same input → same output across JVM runs (String.hashCode is JLS-specified).
        assertEquals(WorkerIdSource.hash("x", 10), WorkerIdSource.hash("x", 10))
    }

    @Test
    fun `hash distributes distinct inputs to likely-distinct ids`() {
        val v1 = WorkerIdSource.hash("pod-a", 16)
        val v2 = WorkerIdSource.hash("pod-b", 16)
        val v3 = WorkerIdSource.hash("pod-c", 16)
        // At 16 bits, three distinct hostnames colliding is extremely unlikely.
        assertNotEquals(v1, v2)
        assertNotEquals(v2, v3)
        assertNotEquals(v1, v3)
    }

    @Test
    fun `hash rejects invalid bits`() {
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = 0) }
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = 32) }
    }

    @Test
    fun `parseOrdinal extracts trailing number from a StatefulSet hostname`() {
        assertEquals(0, WorkerIdSource.parseOrdinal("api-server-0"))
        assertEquals(7, WorkerIdSource.parseOrdinal("api-server-7"))
        assertEquals(123, WorkerIdSource.parseOrdinal("x-y-z-123"))
    }

    @Test
    fun `parseOrdinal returns null for non-statefulset names`() {
        assertNull(WorkerIdSource.parseOrdinal("api-server"))
        assertNull(WorkerIdSource.parseOrdinal("plain-hostname"))
        assertNull(WorkerIdSource.parseOrdinal("api-server-abc"))
        assertNull(WorkerIdSource.parseOrdinal(""))
    }

    @Test
    fun `parseOrdinal masks to bits width`() {
        // 17 and 0x7 = 1 (3-bit window)
        assertEquals(1, WorkerIdSource.parseOrdinal("pod-17", bits = 3))
    }

    @Test
    fun `fromEnv reads and parses an injected env map`() {
        val env = mapOf("WORKER_ID" to "42", "DC_ID" to "7")
        assertEquals(42, WorkerIdSource.fromEnv("WORKER_ID", env))
        assertEquals(7, WorkerIdSource.fromEnv("DC_ID", env))
    }

    @Test
    fun `fromEnv throws when the variable is missing`() {
        val ex = assertThrows<IllegalStateException> {
            WorkerIdSource.fromEnv("UNSET_FOR_TEST", env = emptyMap())
        }
        assertTrue(ex.message!!.contains("UNSET_FOR_TEST"))
    }

    @Test
    fun `fromEnv throws when the value is not an integer`() {
        val env = mapOf("WORKER_ID" to "not-a-number")
        assertThrows<IllegalStateException> { WorkerIdSource.fromEnv("WORKER_ID", env) }
    }

    @Test
    fun `fromHostname returns a value inside the bit range`() {
        // Exact value depends on the host running the test; assert range only.
        val v = WorkerIdSource.fromHostname(bits = 10)
        assertTrue(v in 0..1023, "fromHostname out of range: $v")
    }
}
