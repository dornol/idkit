package io.github.dornol.idkit.ulid

import java.time.Instant

/**
 * Utilities for inspecting and converting ULID strings.
 *
 * All operations are stateless; the singleton [object] form is appropriate here.
 *
 * @since 2.1.0
 */
object UlidParser {

    /**
     * Returns the [Instant] embedded in the first 10 characters of [ulid].
     *
     * @throws IllegalArgumentException if [ulid] is malformed.
     */
    fun timestampOf(ulid: String): Instant =
        Instant.ofEpochMilli(decodeTimestamp(ulid))

    /**
     * Converts [ulid] into its canonical 16-byte binary representation (big-endian):
     *  - bytes 0..5  — 48-bit timestamp
     *  - bytes 6..15 — 80-bit randomness
     *
     * @throws IllegalArgumentException if [ulid] is malformed.
     */
    fun toBytes(ulid: String): ByteArray = withDecodedUlid(ulid) { ts, hi, lo ->
        packToBytes(ts, hi, lo)
    }

    /**
     * Encodes the 16-byte binary form of a ULID back into its 26-char string form.
     *
     * @throws IllegalArgumentException if [bytes] is not exactly 16 bytes.
     */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == 16) { "ULID binary form must be 16 bytes, got ${bytes.size}" }
        val ts = ((bytes[0].toLong() and 0xFFL) shl 40) or
                ((bytes[1].toLong() and 0xFFL) shl 32) or
                ((bytes[2].toLong() and 0xFFL) shl 24) or
                ((bytes[3].toLong() and 0xFFL) shl 16) or
                ((bytes[4].toLong() and 0xFFL) shl 8) or
                (bytes[5].toLong() and 0xFFL)
        val randomHi = ((bytes[6].toLong() and 0xFFL) shl 8) or
                (bytes[7].toLong() and 0xFFL)
        var randomLo = 0L
        for (i in 8..15) {
            randomLo = (randomLo shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        return encodeUlid(ts, randomHi, randomLo)
    }

    /**
     * Returns `true` if [ulid] is a syntactically valid ULID string — 26 characters, all from
     * the Crockford Base32 alphabet, and with a timestamp that fits in 48 bits.
     *
     * Never throws; intended as a cheap pre-check.
     */
    fun isValid(ulid: String): Boolean = isValidUlid(ulid)

    private fun packToBytes(ts: Long, randomHi: Long, randomLo: Long): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = (ts ushr 40).toByte()
        bytes[1] = (ts ushr 32).toByte()
        bytes[2] = (ts ushr 24).toByte()
        bytes[3] = (ts ushr 16).toByte()
        bytes[4] = (ts ushr 8).toByte()
        bytes[5] = ts.toByte()
        bytes[6] = (randomHi ushr 8).toByte()
        bytes[7] = randomHi.toByte()
        for (i in 0..7) {
            bytes[8 + i] = (randomLo ushr ((7 - i) * 8)).toByte()
        }
        return bytes
    }
}
