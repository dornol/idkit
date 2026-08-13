package io.github.dornol.idkit.worker

/** A reserved worker/datacenter identity held by a distributed process. */
interface WorkerIdLease : AutoCloseable {
    val workerId: Int
    val datacenterId: Int

    /** Monotonically increasing ownership token when the backing store supports fencing. */
    val fencingToken: Long
        get() = 0L

    /** False after lease renewal failed or the lease was closed. */
    val isValid: Boolean
        get() = true

    /** Releases the reservation. Implementations should make this idempotent. */
    override fun close()
}

/** Storage-neutral contract for distributed worker-id reservation. */
interface WorkerIdLeaseStore {
    /** Atomically reserves an identity, or returns null when it is already reserved. */
    fun tryAcquire(workerId: Int, datacenterId: Int, owner: String, ttlMillis: Long): WorkerIdLease?
}

/** Small helper that validates lease configuration before creating a generator. */
object WorkerIdLeases {
    fun acquire(
        store: WorkerIdLeaseStore,
        workerId: Int,
        datacenterId: Int = 0,
        owner: String,
        ttlMillis: Long = 30_000L,
    ): WorkerIdLease {
        require(workerId >= 0) { "workerId must be >= 0, but was $workerId" }
        require(datacenterId >= 0) { "datacenterId must be >= 0, but was $datacenterId" }
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(ttlMillis > 0) { "ttlMillis must be > 0, but was $ttlMillis" }
        return store.tryAcquire(workerId, datacenterId, owner, ttlMillis)
            ?: throw IllegalStateException(
                "Worker identity is already reserved: datacenterId=$datacenterId, workerId=$workerId"
            )
    }
}
