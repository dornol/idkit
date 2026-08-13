package io.github.dornol.idkit.worker

import io.github.dornol.idkit.IdGenerator

/**
 * Guards an [IdGenerator] with a distributed [WorkerIdLease].
 *
 * Once the lease expires or its heartbeat fails, all subsequent calls fail closed instead of
 * continuing to emit IDs with an identity that may have been reassigned to another process.
 */
class LeasedIdGenerator<T>(
    private val delegate: IdGenerator<T>,
    private val lease: WorkerIdLease,
) : IdGenerator<T> {

    override fun nextId(): T {
        requireValidLease()
        return delegate.nextId()
    }

    override fun nextIds(count: Int): List<T> {
        require(count >= 0) { "count must be >= 0, but was $count" }
        if (count == 0) return emptyList()
        return List(count) { nextId() }
    }

    private fun requireValidLease() {
        check(lease.isValid) {
            "Worker identity lease is no longer valid; refusing to generate an ID"
        }
    }
}
