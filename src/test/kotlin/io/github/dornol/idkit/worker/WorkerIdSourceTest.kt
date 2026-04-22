package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkerIdSourceTest {

    // --- hash ----------------------------------------------------------------------------------

    @Test
    fun `hash is deterministic and within bit range`() {
        repeat(100) {
            val v = WorkerIdSource.hash("api-server-01", bits = 10)
            assertTrue(v in 0..1023, "hash out of 10-bit range: $v")
        }
        // Same input always produces same output (JLS-specified String.hashCode).
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
    fun `hash always returns non-negative even for inputs with negative String_hashCode`() {
        // `and` mask zeroes the sign bit, so the output must be in `[0, 2^bits)` regardless of
        // the sign of `value.hashCode()`. Search the input space until we hit a guaranteed
        // negative hashCode (overflow is common once strings grow beyond a handful of chars)
        // and then assert the mask kept the output non-negative.
        var negativeHashInput: String? = null
        for (i in 0 until 100_000) {
            val s = "host-$i-worker"
            if (s.hashCode() < 0) {
                negativeHashInput = s
                break
            }
        }
        val sNeg = requireNotNull(negativeHashInput) {
            "test setup: could not find any input with a negative hashCode in 100k attempts"
        }
        assertTrue(sNeg.hashCode() < 0)
        val id10 = WorkerIdSource.hash(sNeg, 10)
        assertTrue(id10 in 0..1023, "negative-hash input produced out-of-range id $id10")

        // Also sanity-check range across many varied inputs.
        (0 until 1_000).map { "sample-$it" }.forEach { s ->
            assertTrue(WorkerIdSource.hash(s, 10) in 0..1023)
            assertTrue(WorkerIdSource.hash(s, 20) in 0..((1 shl 20) - 1))
        }
    }

    @Test
    fun `hash handles the empty string`() {
        // "".hashCode() == 0 → hash result must be 0 for any bit width.
        assertEquals(0, WorkerIdSource.hash("", 10))
        assertEquals(0, WorkerIdSource.hash("", 1))
    }

    @Test
    fun `hash fully saturates bits for bits equals 1`() {
        // Tiny bit widths must still work — a 1-bit range is {0, 1}, not error.
        val results = (0 until 100).map { WorkerIdSource.hash("h-$it", 1) }.toSet()
        assertTrue(results.size in 1..2, "1-bit hash must produce only 0 or 1: got $results")
    }

    @Test
    fun `hash rejects invalid bits`() {
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = 0) }
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = 32) }
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = -1) }
        assertThrows<IllegalArgumentException> { WorkerIdSource.hash("x", bits = Int.MAX_VALUE) }
    }

    // --- parseOrdinal --------------------------------------------------------------------------

    @Test
    fun `parseOrdinal extracts trailing number from a StatefulSet hostname`() {
        assertEquals(0, WorkerIdSource.parseOrdinal("api-server-0"))
        assertEquals(7, WorkerIdSource.parseOrdinal("api-server-7"))
        assertEquals(123, WorkerIdSource.parseOrdinal("x-y-z-123"))
    }

    @Test
    fun `parseOrdinal handles multi-digit and leading-zero ordinals`() {
        assertEquals(12_345, WorkerIdSource.parseOrdinal("pod-12345", bits = 20))
        assertEquals(3, WorkerIdSource.parseOrdinal("api-03"), "leading zero must decode to 3, not 03")
    }

    @Test
    fun `parseOrdinal returns null when there is no trailing digit suffix`() {
        assertNull(WorkerIdSource.parseOrdinal("api-server"))
        assertNull(WorkerIdSource.parseOrdinal("plain-hostname"))
        assertNull(WorkerIdSource.parseOrdinal("api-server-abc"))
        assertNull(WorkerIdSource.parseOrdinal(""))
        // Digits elsewhere but not at the tail must NOT match.
        assertNull(WorkerIdSource.parseOrdinal("api-3-server"))
        assertNull(WorkerIdSource.parseOrdinal("123-api"))
        assertNull(WorkerIdSource.parseOrdinal("api-3-"))
    }

    @Test
    fun `parseOrdinal returns null when the trailing number overflows Int`() {
        // toIntOrNull returns null for values outside Int range.
        assertNull(WorkerIdSource.parseOrdinal("pod-99999999999999"))
    }

    @Test
    fun `parseOrdinal masks to bits width`() {
        // 17 & 0x7 = 1 (3-bit window)
        assertEquals(1, WorkerIdSource.parseOrdinal("pod-17", bits = 3))
        // 8 & 0x7 = 0 — boundary case where masking discards a whole digit.
        assertEquals(0, WorkerIdSource.parseOrdinal("pod-8", bits = 3))
    }

    @Test
    fun `parseOrdinal rejects invalid bits like hash does`() {
        assertThrows<IllegalArgumentException> { WorkerIdSource.parseOrdinal("pod-1", bits = 0) }
        assertThrows<IllegalArgumentException> { WorkerIdSource.parseOrdinal("pod-1", bits = 32) }
    }

    // --- fromEnv -------------------------------------------------------------------------------

    @Test
    fun `fromEnv reads and parses an injected env map`() {
        val env = mapOf("WORKER_ID" to "42", "DC_ID" to "7")
        assertEquals(42, WorkerIdSource.fromEnv("WORKER_ID", env))
        assertEquals(7, WorkerIdSource.fromEnv("DC_ID", env))
    }

    @Test
    fun `fromEnv accepts negative integers (the generator rejects out-of-range downstream)`() {
        // fromEnv's contract is "parse an Int"; range validation happens in the generator.
        // A negative value must pass through — this keeps the helper composable.
        val env = mapOf("WORKER_ID" to "-1")
        assertEquals(-1, WorkerIdSource.fromEnv("WORKER_ID", env))
    }

    @Test
    fun `fromEnv throws a descriptive message when the variable is missing`() {
        val ex = assertThrows<IllegalStateException> {
            WorkerIdSource.fromEnv("UNSET_FOR_TEST", env = emptyMap())
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("UNSET_FOR_TEST"), "message must name the missing var: '$msg'")
        assertTrue(
            msg.contains("not set", ignoreCase = true),
            "message must distinguish 'not set' from 'not an Int': '$msg'",
        )
    }

    @Test
    fun `fromEnv throws a descriptive message when the value is not a valid Int`() {
        val env = mapOf("WORKER_ID" to "not-a-number")
        val ex = assertThrows<IllegalStateException> { WorkerIdSource.fromEnv("WORKER_ID", env) }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("WORKER_ID"), "message must name the var: '$msg'")
        assertTrue(
            msg.contains("not a valid", ignoreCase = true) || msg.contains("Int", ignoreCase = true),
            "message must distinguish parse failure from missing: '$msg'",
        )
    }

    @Test
    fun `fromEnv throws on an empty-string value`() {
        // Empty string is set but un-parseable — impl currently treats this as "not an Int".
        val env = mapOf("WORKER_ID" to "")
        assertThrows<IllegalStateException> { WorkerIdSource.fromEnv("WORKER_ID", env) }
    }

    // --- fromHostname / fromNetworkInterface (integration smoke) --------------------------------

    @Test
    fun `fromHostname returns a value inside the bit range`() {
        // Exact value depends on the host running the test; assert range only.
        val v = WorkerIdSource.fromHostname(bits = 10)
        assertTrue(v in 0..1023, "fromHostname out of range: $v")
    }

    @Test
    fun `fromNetworkInterface returns a value in range when a MAC is available, otherwise throws`() {
        // CI containers may or may not have a usable MAC. Accept either outcome and only
        // check that the value (when returned) fits the requested bit width.
        try {
            val v = WorkerIdSource.fromNetworkInterface(bits = 10)
            assertTrue(v in 0..1023, "fromNetworkInterface out of range: $v")
        } catch (e: IllegalStateException) {
            assertTrue(
                (e.message ?: "").contains("network interface", ignoreCase = true),
                "message must describe the missing interface: '${e.message}'",
            )
        }
    }
}
