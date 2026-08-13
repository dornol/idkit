package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FencedOperationTest {
    @Test
    fun `requireApplied raises on stale result`() {
        val executor = object : FencedOperationExecutor {
            override fun execute(resource: String, fencingToken: Long, operation: () -> Unit) =
                FencedOperationResult.REJECTED_STALE
        }

        assertThrows<StaleFencingTokenException> {
            executor.requireApplied("orders", 1) { error("must not run") }
        }
    }

    @Test
    fun `applied operation runs once`() {
        var calls = 0
        val executor = object : FencedOperationExecutor {
            override fun execute(resource: String, fencingToken: Long, operation: () -> Unit): FencedOperationResult {
                operation()
                return FencedOperationResult.APPLIED
            }
        }

        executor.requireApplied("orders", 1) { calls++ }

        assertEquals(1, calls)
    }
}
