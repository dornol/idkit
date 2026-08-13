package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FencingTokenValidatorTest {
    @Test
    fun `accepts only strictly newer tokens per resource`() {
        val validator = InMemoryFencingTokenValidator()

        assertTrue(validator.accept("orders", 3))
        assertFalse(validator.accept("orders", 3))
        assertFalse(validator.accept("orders", 2))
        assertTrue(validator.accept("orders", 4))
        assertTrue(validator.accept("payments", 1))
        assertEquals(4L, validator.current("orders"))
    }

    @Test
    fun `requireNewer exposes stale token details`() {
        val validator = InMemoryFencingTokenValidator()
        validator.requireNewer("orders", 7)

        val error = assertThrows<StaleFencingTokenException> {
            validator.requireNewer("orders", 6)
        }
        assertEquals("orders", error.resource)
        assertEquals(6L, error.fencingToken)
        assertEquals(7L, error.currentToken)
    }

    @Test
    fun `concurrent tokens leave the maximum token accepted`() {
        val validator = InMemoryFencingTokenValidator()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1L..100L).map { token ->
                executor.submit<Boolean> { validator.accept("orders", token) }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(100L, validator.current("orders"))
        } finally {
            executor.shutdownNow()
        }
    }
}
