package io.github.dornol.idkit.uuidv7

import java.time.Instant
import java.util.UUID

/**
 * Utilities for inspecting UUID v7 values.
 *
 * @since 2.1.0
 */
object UuidV7Parser {

    /**
     * Returns the [Instant] embedded in the first 48 bits of [uuid]'s `mostSigBits`.
     *
     * @throws IllegalArgumentException if [uuid] is not a version 7 UUID. This is a strict
     *   check; if you intend to accept other UUID versions, use [rawTimestampOf] instead.
     */
    fun timestampOf(uuid: UUID): Instant {
        require(uuid.version() == 7) {
            "Not a UUID v7: version=${uuid.version()}"
        }
        return Instant.ofEpochMilli(rawTimestampOf(uuid))
    }

    /**
     * Returns the raw millisecond value in the first 48 bits of [uuid]'s `mostSigBits`,
     * without validating [uuid]'s version. Useful for bulk parsing where the caller has
     * already filtered on version or deliberately wants the top-48 value regardless.
     */
    fun rawTimestampOf(uuid: UUID): Long = uuid.mostSignificantBits ushr 16
}
