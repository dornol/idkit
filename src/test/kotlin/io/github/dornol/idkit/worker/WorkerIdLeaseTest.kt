package io.github.dornol.idkit.worker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkerIdLeaseTest {
    @Test
    fun `acquire validates and delegates to the storage-neutral store`() {
        val lease = FakeLease(workerId = 3, datacenterId = 2)
        val store = object : WorkerIdLeaseStore {
            override fun tryAcquire(
                workerId: Int,
                datacenterId: Int,
                owner: String,
                ttlMillis: Long,
            ): WorkerIdLease {
                assertEquals(3, workerId)
                assertEquals(2, datacenterId)
                assertEquals("node-a", owner)
                assertEquals(10_000L, ttlMillis)
                return lease
            }
        }

        assertSame(lease, WorkerIdLeases.acquire(store, 3, 2, "node-a", 10_000L))
    }

    @Test
    fun `acquire reports collision when store cannot reserve identity`() {
        val store = object : WorkerIdLeaseStore {
            override fun tryAcquire(
                workerId: Int,
                datacenterId: Int,
                owner: String,
                ttlMillis: Long,
            ): WorkerIdLease? = null
        }

        val ex = assertThrows<IllegalStateException> {
            WorkerIdLeases.acquire(store, 1, 2, "node-a")
        }
        assertEquals(true, ex.message?.contains("already reserved"))
    }

    @Test
    fun `acquire rejects invalid lease settings`() {
        val store = object : WorkerIdLeaseStore {
            override fun tryAcquire(
                workerId: Int,
                datacenterId: Int,
                owner: String,
                ttlMillis: Long,
            ): WorkerIdLease? = null
        }
        assertThrows<IllegalArgumentException> { WorkerIdLeases.acquire(store, -1, 0, "node") }
        assertThrows<IllegalArgumentException> { WorkerIdLeases.acquire(store, 0, -1, "node") }
        assertThrows<IllegalArgumentException> { WorkerIdLeases.acquire(store, 0, 0, " ") }
        assertThrows<IllegalArgumentException> { WorkerIdLeases.acquire(store, 0, 0, "node", 0) }
    }

    private class FakeLease(
        override val workerId: Int,
        override val datacenterId: Int,
    ) : WorkerIdLease {
        override fun close() = Unit
    }
}
