package io.github.dornol.idkit

/**
 * Contract for id generators that emit values of type [T].
 *
 * Implementations must be thread-safe: [nextId] and [nextIds] may be called concurrently from
 * any number of threads. Concrete generators document their exact concurrency strategy
 * (e.g., `@Synchronized`, CAS, or per-thread state).
 */
interface IdGenerator<T> {

    /** Generates a single id. */
    fun nextId(): T

    /**
     * Generates [count] ids in a single call.
     *
     * The default implementation simply calls [nextId] [count] times. Generators backed by a
     * serialized section (for example Flake/Snowflake or ULID, which both use `@Synchronized`)
     * may override this to hold their internal monitor once for the whole batch, amortizing the
     * lock-acquire cost across the batch.
     *
     * @param count number of ids to generate; must be `>= 0`. When `0`, returns an empty list.
     * @return a fixed-size list of freshly generated ids in emission order.
     * @throws IllegalArgumentException if [count] is negative.
     * @since 2.3.0
     */
    fun nextIds(count: Int): List<T> {
        require(count >= 0) { "count must be >= 0, but was $count" }
        if (count == 0) return emptyList()
        return List(count) { nextId() }
    }

}
