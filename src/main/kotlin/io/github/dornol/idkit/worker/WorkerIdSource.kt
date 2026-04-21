package io.github.dornol.idkit.worker

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Helpers for deriving a `workerId` (and optionally a `datacenterId`) from runtime context,
 * so that each node in a distributed deployment gets a distinct id without hand-rolled
 * coordination.
 *
 * The core functions are pure — [hash] and [parseOrdinal] take a string and a bit width and
 * return an id. The remaining entry points read from the environment ([fromHostname],
 * [fromPodOrdinal], [fromEnv], [fromNetworkInterface]) and delegate to the pure helpers, so
 * tests can exercise the same logic deterministically by passing hostnames/env maps directly.
 *
 * All returned values are in `[0, 2^bits)` and safely fit a [FlakeIdGenerator] / Snowflake
 * `workerId: Int` parameter.
 *
 * @since 2.2.0
 */
object WorkerIdSource {

    /**
     * Maps [value] deterministically to `[0, 2^bits)` using [String.hashCode] masked to [bits].
     * Stable across JVM restarts (the JLS specifies `String.hashCode`).
     */
    fun hash(value: String, bits: Int = 10): Int {
        require(bits in 1..31) { "bits must be between 1 and 31, was $bits" }
        return value.hashCode() and ((1 shl bits) - 1)
    }

    /**
     * Parses the trailing ordinal of a Kubernetes StatefulSet pod hostname
     * (e.g. `"api-server-3"` → `3`) and masks it to [bits]. Returns `null` if [hostname]
     * does not end with `-<digits>`, letting the caller fall back to another strategy.
     */
    fun parseOrdinal(hostname: String, bits: Int = 10): Int? {
        require(bits in 1..31) { "bits must be between 1 and 31, was $bits" }
        val match = ORDINAL_SUFFIX.find(hostname) ?: return null
        val ordinal = match.groupValues[1].toIntOrNull() ?: return null
        return ordinal and ((1 shl bits) - 1)
    }

    /** Convenience: [hash] of the local hostname. */
    fun fromHostname(bits: Int = 10): Int = hash(localHostname(), bits)

    /**
     * Convenience: [parseOrdinal] of the local hostname. Returns `null` when the current
     * hostname does not fit the StatefulSet pattern.
     */
    fun fromPodOrdinal(bits: Int = 10): Int? = parseOrdinal(localHostname(), bits)

    /**
     * Reads [name] from [env] and parses it as an `Int`.
     *
     * @param env defaults to the live process environment; tests can pass a stubbed map.
     * @throws IllegalStateException if the variable is unset or not a valid integer.
     */
    fun fromEnv(name: String = "WORKER_ID", env: Map<String, String> = System.getenv()): Int {
        val raw = env[name] ?: error("Env var '$name' is not set")
        return raw.toIntOrNull() ?: error("Env var '$name'='$raw' is not a valid Int")
    }

    /**
     * Derives a worker id from the MAC address of the first non-loopback, non-virtual
     * network interface.
     *
     * @throws IllegalStateException when no such interface is available (common in some
     *   container runtimes — prefer [fromPodOrdinal] or [fromEnv] in that case).
     */
    fun fromNetworkInterface(bits: Int = 10): Int {
        require(bits in 1..31) { "bits must be between 1 and 31, was $bits" }
        val mac = firstNonLoopbackMac()
            ?: error("No non-loopback network interface with a hardware address is available")
        var hash = 0
        for (b in mac) hash = hash * 31 + (b.toInt() and 0xFF)
        return hash and ((1 shl bits) - 1)
    }

    private val ORDINAL_SUFFIX = Regex("-(\\d+)$")

    private fun localHostname(): String {
        System.getenv("HOSTNAME")?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: error("Cannot resolve local hostname")
    }

    private fun firstNonLoopbackMac(): ByteArray? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isLoopback && !iface.isVirtual) {
                val mac = iface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) return mac
            }
        }
        return null
    }
}
