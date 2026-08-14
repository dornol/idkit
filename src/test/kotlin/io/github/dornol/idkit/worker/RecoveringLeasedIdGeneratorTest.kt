package io.github.dornol.idkit.worker

import io.github.dornol.idkit.IdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecoveringLeasedIdGeneratorTest {
    @Test
    fun `fails closed during lease loss and resumes after reacquisition`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val first = TestLease(1)
        val replacement = TestLease(2)
        val recoveryAttempts = AtomicInteger()
        val recoverySuccesses = AtomicInteger()
        val generator = RecoveringLeasedIdGenerator(
            initialLease = first,
            initialGenerator = generatorFor(first),
            scheduler = scheduler,
            recoveryRetryDelayMillis = 20,
            acquire = { replacement },
            generatorFactory = ::generatorFor,
            metrics = object : LeaseRecoveryMetrics {
                override fun recoveryAttempted() { recoveryAttempts.incrementAndGet() }
                override fun recoverySucceeded() { recoverySuccesses.incrementAndGet() }
                override fun recoveryFailed() = Unit
                override fun recoveryActive(active: Boolean) = Unit
            },
        )

        try {
            assertEquals(1, generator.nextId())
            first.valid = false
            assertThrows<IllegalStateException> { generator.nextId() }
            eventually { generator.currentLease === replacement }
            assertEquals(2, generator.nextId())
            assertEquals(1, recoveryAttempts.get())
            assertEquals(1, recoverySuccesses.get())
        } finally {
            generator.close()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun `keeps retrying after acquisition failure`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val first = TestLease(1)
        val replacement = TestLease(2)
        val attempts = AtomicInteger()
        val generator = RecoveringLeasedIdGenerator(
            initialLease = first,
            initialGenerator = generatorFor(first),
            scheduler = scheduler,
            recoveryRetryDelayMillis = 20,
            acquire = {
                if (attempts.incrementAndGet() == 1) error("backend unavailable")
                replacement
            },
            generatorFactory = ::generatorFor,
        )

        try {
            first.valid = false
            eventually { generator.currentLease === replacement }
            assertEquals(2, generator.nextId())
            assertEquals(null, generator.lastRecoveryFailure)
        } finally {
            generator.close()
            scheduler.shutdownNow()
        }
    }

    private fun generatorFor(lease: WorkerIdLease): IdGenerator<Int> = object : IdGenerator<Int> {
        override fun nextId(): Int = lease.workerId
    }

    private fun eventually(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met before timeout")
    }

    private class TestLease(override val workerId: Int) : WorkerIdLease {
        override val datacenterId: Int = 0
        @Volatile var valid: Boolean = true
        override val isValid: Boolean get() = valid
        override fun close() { valid = false }
    }
}
