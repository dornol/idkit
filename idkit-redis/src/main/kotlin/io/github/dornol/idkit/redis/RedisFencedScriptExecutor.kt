package io.github.dornol.idkit.redis

import io.github.dornol.idkit.worker.FencedOperationExecutor
import io.github.dornol.idkit.worker.FencedOperationResult
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands

/** Executes a Redis Lua side effect only when its fencing token is newer, atomically. */
class RedisFencedScriptExecutor(
    private val commands: RedisCommands<String, String>,
    private val keyPrefix: String = "idkit:fencing",
) {
    fun execute(
        resource: String,
        fencingToken: Long,
        operationScript: String,
        operationKeys: Array<String> = emptyArray(),
        operationArgs: Array<String> = emptyArray(),
    ): FencedOperationResult {
        require(resource.isNotBlank()) { "resource must not be blank" }
        require(fencingToken > 0) { "fencingToken must be > 0" }
        require(operationScript.isNotBlank()) { "operationScript must not be blank" }
        val script = "local current = redis.call('get', KEYS[1]); " +
                "if current and tonumber(ARGV[1]) <= tonumber(current) then return 0 end; " +
                operationScript + "; redis.call('set', KEYS[1], ARGV[1]); return 1"
        val result = commands.eval<Long>(
            script,
            ScriptOutputType.INTEGER,
            arrayOf(key(resource), *operationKeys),
            fencingToken.toString(),
            *operationArgs,
        )
        return if (result == 0L) FencedOperationResult.REJECTED_STALE else FencedOperationResult.APPLIED
    }

    private fun key(resource: String) = "$keyPrefix:$resource"
}
