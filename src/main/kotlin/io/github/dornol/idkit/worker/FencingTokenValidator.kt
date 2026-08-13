package io.github.dornol.idkit.worker

/**
 * Accepts only strictly newer fencing tokens for each logical downstream resource.
 *
 * The downstream operation must call [requireNewer] immediately before applying its side effect,
 * or implement the same monotonic comparison atomically in its own storage.
 */
interface FencingTokenValidator {
    fun current(resource: String): Long?

    /** Atomically records [fencingToken] when it is newer than the last accepted token. */
    fun accept(resource: String, fencingToken: Long): Boolean

    fun requireNewer(resource: String, fencingToken: Long) {
        require(resource.isNotBlank()) { "resource must not be blank" }
        require(fencingToken > 0) { "fencingToken must be > 0" }
        if (!accept(resource, fencingToken)) {
            throw StaleFencingTokenException(resource, fencingToken, current(resource))
        }
    }
}

/** Raised when a worker presents a fencing token that has already been superseded. */
class StaleFencingTokenException(
    val resource: String,
    val fencingToken: Long,
    val currentToken: Long?,
) : IllegalStateException(
    "Stale fencing token for resource '$resource': received=$fencingToken, current=$currentToken",
)

/** In-memory validator for one JVM; use a shared durable implementation across processes. */
class InMemoryFencingTokenValidator : FencingTokenValidator {
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun current(resource: String): Long? {
        require(resource.isNotBlank()) { "resource must not be blank" }
        return tokens[resource]
    }

    override fun accept(resource: String, fencingToken: Long): Boolean {
        require(resource.isNotBlank()) { "resource must not be blank" }
        require(fencingToken > 0) { "fencingToken must be > 0" }
        var accepted = false
        tokens.compute(resource) { _, current ->
            if (current == null || fencingToken > current) {
                accepted = true
                fencingToken
            } else {
                current
            }
        }
        return accepted
    }

    fun clear(resource: String) {
        require(resource.isNotBlank()) { "resource must not be blank" }
        tokens.remove(resource)
    }
}
