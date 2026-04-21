package io.github.dornol.idkit.ulid

/**
 * Crockford's Base32 alphabet — excludes `I`, `L`, `O`, `U` to avoid visual ambiguity.
 * Shared by the generator, the parser, and the test suite as the single source of truth.
 */
internal const val CROCKFORD_BASE32_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private const val MASK_5_BITS = 0x1FL

/**
 * Reverse lookup for Crockford Base32: `DECODE_TABLE[c]` gives the 5-bit value for char `c`,
 * or `-1` if `c` is not a valid Crockford Base32 character. Sized at 128 so an ASCII-range
 * lookup is a single array indexing; any char outside that range is treated as invalid.
 */
private val DECODE_TABLE: IntArray = IntArray(128) { -1 }.also { table ->
    for (i in CROCKFORD_BASE32_ALPHABET.indices) {
        table[CROCKFORD_BASE32_ALPHABET[i].code] = i
    }
}

/**
 * Encodes `(timestamp, randomHi, randomLo)` into a 26-char Crockford Base32 ULID string.
 *
 * Bit layout (80-bit randomness emitted from LSB toward MSB):
 *  - chars 25..14 (12 chars) draw from the low 60 bits of [randomLo]
 *  - char 13 straddles: 4 bits from [randomLo] (bits 60..63) + 1 bit from [randomHi] (bit 0)
 *  - chars 12..10 (3 chars) draw from the remaining 15 bits of [randomHi] (bits 1..15)
 */
internal fun encodeUlid(timestamp: Long, randomHi: Long, randomLo: Long): String {
    val chars = CharArray(26)

    // chars 0..9: 48-bit timestamp.
    var ts = timestamp
    for (i in 9 downTo 0) {
        chars[i] = CROCKFORD_BASE32_ALPHABET[(ts and MASK_5_BITS).toInt()]
        ts = ts ushr 5
    }

    // chars 25..14: 12 chars from randomLo's low 60 bits. After the loop, `rl` holds only
    // the top 4 bits of the original randomLo (bits 60..63).
    var rl = randomLo
    for (i in 25 downTo 14) {
        chars[i] = CROCKFORD_BASE32_ALPHABET[(rl and MASK_5_BITS).toInt()]
        rl = rl ushr 5
    }

    // char 13: remaining 4 bits of randomLo + 1 low bit of randomHi. The `rl and 0xFL` mask
    // is defensive — `rl` should already fit in 4 bits after the loop above.
    var rh = randomHi
    val char13 = ((rh and 0x1L) shl 4) or (rl and 0xFL)
    chars[13] = CROCKFORD_BASE32_ALPHABET[char13.toInt()]
    rh = rh ushr 1

    // chars 12..10: 3 chars from randomHi's remaining 15 bits.
    for (i in 12 downTo 10) {
        chars[i] = CROCKFORD_BASE32_ALPHABET[(rh and MASK_5_BITS).toInt()]
        rh = rh ushr 5
    }

    return String(chars)
}

/**
 * Decodes the first 10 chars of [ulid] into the 48-bit timestamp value. Cheaper than
 * [decodeUlid] when only the timestamp is needed.
 *
 * @throws IllegalArgumentException if [ulid] is not 26 chars, contains invalid characters,
 *   or its first char encodes bits that overflow the 48-bit timestamp range.
 */
internal fun decodeTimestamp(ulid: String): Long {
    require(ulid.length == 26) { "ULID must be 26 chars, got ${ulid.length}" }
    // char[0] can encode 5 bits but only the low 3 are part of the timestamp; its top 2
    // bits must be zero for a valid 48-bit timestamp encoding.
    val c0 = decodeChar(ulid, 0)
    require(c0 < 8) { "Invalid ULID: first char '${ulid[0]}' implies timestamp > 48 bits" }

    var ts = c0.toLong()
    for (i in 1 until 10) {
        ts = (ts shl 5) or decodeChar(ulid, i).toLong()
    }
    return ts
}

/**
 * Fully decodes [ulid] into `(timestamp, randomHi, randomLo)`. Returns a three-element
 * `LongArray` to avoid an allocation per triple.
 *
 * @throws IllegalArgumentException on invalid length, invalid char, or timestamp overflow.
 */
internal fun decodeUlid(ulid: String): LongArray {
    val ts = decodeTimestamp(ulid) // reuses validation for chars 0..9

    // chars 10..25 = 16 chars = 80 bits of randomness. Decode in the mirror of encodeUlid.
    var randomHi = 0L
    for (i in 10..12) {
        randomHi = (randomHi shl 5) or decodeChar(ulid, i).toLong()
    }
    // randomHi now holds 15 bits. char 13 contributes its top bit to randomHi and its low
    // 4 bits to the top of randomLo.
    val c13 = decodeChar(ulid, 13).toLong()
    randomHi = (randomHi shl 1) or (c13 ushr 4)

    var randomLo = c13 and 0xFL
    for (i in 14..25) {
        randomLo = (randomLo shl 5) or decodeChar(ulid, i).toLong()
    }

    return longArrayOf(ts, randomHi, randomLo)
}

/** Returns `true` if [ulid] is a syntactically valid ULID (length, alphabet, timestamp range). */
internal fun isValidUlid(ulid: String): Boolean {
    if (ulid.length != 26) return false
    for (i in 0 until 26) {
        val c = ulid[i]
        if (c.code !in DECODE_TABLE.indices || DECODE_TABLE[c.code] < 0) return false
    }
    return DECODE_TABLE[ulid[0].code] < 8
}

private fun decodeChar(ulid: String, index: Int): Int {
    val c = ulid[index]
    if (c.code !in DECODE_TABLE.indices) {
        throw IllegalArgumentException("Invalid ULID char '$c' at position $index")
    }
    val value = DECODE_TABLE[c.code]
    require(value >= 0) { "Invalid ULID char '$c' at position $index" }
    return value
}
