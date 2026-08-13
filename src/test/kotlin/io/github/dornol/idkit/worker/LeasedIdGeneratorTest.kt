package io.github.dornol.idkit.worker

import io.github.dornol.idkit.IdGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LeasedIdGeneratorTest {
    @Test
    fun `delegates while lease is valid and fails closed after invalidation`() {
        val lease = TestLease()
        val generator = LeasedIdGenerator(object : IdGenerator<Long> {
            override fun nextId(): Long = 42L
        }, lease)

        assertEquals(42L, generator.nextId())
        lease.valid = false
        assertThrows<IllegalStateException> { generator.nextId() }
    }

    @Test
    fun `batch generation checks lease for every id`() {
        val lease = TestLease()
        var calls = 0
        val generator = LeasedIdGenerator(object : IdGenerator<Long> {
            override fun nextId(): Long {
                calls++
                if (calls == 2) lease.valid = false
                return calls.toLong()
            }
        }, lease)

        assertThrows<IllegalStateException> { generator.nextIds(3) }
        assertEquals(2, calls)
    }

    private class TestLease : WorkerIdLease {
        override val workerId: Int = 0
        override val datacenterId: Int = 0
        var valid = true
        override val isValid: Boolean get() = valid
        override fun close() { valid = false }
    }
}
