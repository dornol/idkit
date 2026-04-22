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
     * Returns the top 48 bits of [uuid]'s `mostSigBits`, without validating the version
     * field. Intended for interop scenarios where the stream carries UUIDs produced against
     * older or non-standard time-ordered profiles (pre-RFC-9562 v7 drafts, UUID v6, or
     * custom time-ordered UUIDs) whose timestamp layout matches v7 but whose version field
     * does not. Use [timestampOf] for strict v7 input.
     */
    fun rawTimestampOf(uuid: UUID): Long = uuid.mostSignificantBits ushr 16

    /**
     * Returns `true` iff [uuid] has `version() == 7`. Cheap guard mirror of
     * [io.github.dornol.idkit.ulid.UlidParser.isValid] for call sites that want a boolean check
     * instead of `require()`-based validation.
     *
     * @since 2.3.0
     */
    fun isValid(uuid: UUID): Boolean = uuid.version() == 7

    /**
     * Returns `true` iff [text] parses via [UUID.fromString] and the resulting UUID is v7.
     * Does not throw; returns `false` on malformed input.
     *
     * @since 2.3.0
     */
    fun isValid(text: CharSequence): Boolean {
        val parsed = try {
            UUID.fromString(text.toString())
        } catch (_: IllegalArgumentException) {
            return false
        }
        return parsed.version() == 7
    }
}
