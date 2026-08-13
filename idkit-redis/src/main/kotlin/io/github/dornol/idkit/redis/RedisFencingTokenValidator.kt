package io.github.dornol.idkit.redis

import io.github.dornol.idkit.worker.FencingTokenValidator
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands

/** Durable fencing validator backed by an atomic Redis Lua compare-and-set. */
class RedisFencingTokenValidator(
    private val commands: RedisCommands<String, String>,
    private val keyPrefix: String = "idkit:fencing",
) : FencingTokenValidator {
    override fun current(resource: String): Long? {
        requireResource(resource)
        return commands.get(key(resource))?.toLongOrNull()
    }

    override fun accept(resource: String, fencingToken: Long): Boolean {
        requireResource(resource)
        require(fencingToken > 0) { "fencingToken must be > 0" }
        return commands.eval<Long>(
            ACCEPT_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(key(resource)),
            fencingToken.toString(),
        ) == 1L
    }

    private fun key(resource: String) = "$keyPrefix:$resource"
    private fun requireResource(resource: String) { require(resource.isNotBlank()) { "resource must not be blank" } }

    private companion object {
        private const val ACCEPT_SCRIPT =
            "local current = redis.call('get', KEYS[1]); " +
                    "if (not current) or (tonumber(ARGV[1]) > tonumber(current)) then " +
                    "redis.call('set', KEYS[1], ARGV[1]); return 1 else return 0 end"
    }
}
