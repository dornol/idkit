package io.github.dornol.idkit.ulid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UlidParserTest {

    @Test
    fun `timestampOf round-trips a freshly generated ulid`() {
        val gen = UlidIdGenerator()
        val before = System.currentTimeMillis()
        val ulid = gen.nextId()
        val after = System.currentTimeMillis()

        val ts = UlidParser.timestampOf(ulid).toEpochMilli()
        assertTrue(ts in before..after, "decoded ts=$ts not in [$before..$after]")
    }

    @Test
    fun `toBytes and fromBytes round-trip`() {
        val gen = UlidIdGenerator()
        repeat(100) {
            val ulid = gen.nextId()
            val bytes = UlidParser.toBytes(ulid)
            assertEquals(16, bytes.size)
            val reencoded = UlidParser.fromBytes(bytes)
            assertEquals(ulid, reencoded, "ULID round-trip should preserve the original string")
        }
    }

    @Test
    fun `toBytes rejects too-short input`() {
        assertThrows<IllegalArgumentException> { UlidParser.toBytes("TOO-SHORT") }
        assertThrows<IllegalArgumentException> { UlidParser.toBytes("") }
        assertThrows<IllegalArgumentException> {
            UlidParser.toBytes("01HV8B2YJ4M2N3X4Y5Z6ABCDE") // 25 chars
        }
    }

    @Test
    fun `toBytes rejects too-long input`() {
        assertThrows<IllegalArgumentException> {
            UlidParser.toBytes("01HV8B2YJ4M2N3X4Y5Z6ABCDEFF") // 27 chars
        }
    }

    @Test
    fun `toBytes rejects each Crockford-excluded character individually`() {
        // Crockford Base32 explicitly excludes I, L, O, U (visually ambiguous).
        val validPrefix = "01HV8B2YJ4M2N3X4Y5Z6ABCDE" // 25 valid chars
        for (badChar in listOf('I', 'L', 'O', 'U')) {
            val input = validPrefix + badChar
            assertEquals(26, input.length)
            assertThrows<IllegalArgumentException>(
                "expected rejection for trailing '$badChar'",
            ) { UlidParser.toBytes(input) }
        }
    }

    @Test
    fun `toBytes rejects lowercase input (idkit's Crockford decoder is case-sensitive)`() {
        // The ULID spec describes the Crockford alphabet as case-insensitive on decode, but
        // idkit's DECODE_TABLE only registers uppercase entries. Pin the current behavior so
        // a future flip to case-insensitive is caught.
        val gen = UlidIdGenerator()
        val upper = gen.nextId()
        val lower = upper.lowercase()
        assertNotEquals(upper, lower)
        assertThrows<IllegalArgumentException>("lowercase ULID must be rejected today") {
            UlidParser.toBytes(lower)
        }
    }

    @Test
    fun `toBytes rejects timestamp overflow (first char beyond 7)`() {
        // ULID's first Base32 symbol encodes the top 5 bits of the 48-bit timestamp. A symbol
        // above '7' (i.e. value >= 8) would push the top bit beyond the 48-bit budget.
        assertThrows<IllegalArgumentException> {
            UlidParser.toBytes("8ZZZZZZZZZZZZZZZZZZZZZZZZZ")
        }
    }

    @Test
    fun `fromBytes rejects non-16-byte input`() {
        assertThrows<IllegalArgumentException> { UlidParser.fromBytes(ByteArray(15)) }
        assertThrows<IllegalArgumentException> { UlidParser.fromBytes(ByteArray(17)) }
        assertThrows<IllegalArgumentException> { UlidParser.fromBytes(ByteArray(0)) }
    }

    @Test
    fun `isValid accepts generated ulids`() {
        val gen = UlidIdGenerator()
        repeat(100) {
            assertTrue(UlidParser.isValid(gen.nextId()))
        }
    }

    @Test
    fun `isValid rejects malformed strings covering each failure mode`() {
        assertFalse(UlidParser.isValid(""), "empty string")
        assertFalse(UlidParser.isValid("01HV8B2YJ4M2N3X4Y5Z6ABCDE"), "25 chars (too short)")
        assertFalse(UlidParser.isValid("01HV8B2YJ4M2N3X4Y5Z6ABCDEFF"), "27 chars (too long)")
        for (badChar in listOf('I', 'L', 'O', 'U')) {
            val input = "01HV8B2YJ4M2N3X4Y5Z6ABCDE$badChar"
            assertFalse(UlidParser.isValid(input), "trailing '$badChar' must be rejected")
        }
        assertFalse(UlidParser.isValid("8ZZZZZZZZZZZZZZZZZZZZZZZZZ"), "timestamp overflow")
        assertFalse(UlidParser.isValid("01HV8B2YJ4M2N3X4Y5Z6 BCDEF"), "space is not in the alphabet")
        // Non-ASCII char (U+00FF) at position 25. The DECODE_TABLE lookup must guard
        // out-of-range code points — not throw ArrayIndexOutOfBoundsException.
        val nonAscii = "01HV8B2YJ4M2N3X4Y5Z6ABCDE" + 'ÿ'
        assertEquals(26, nonAscii.length)
        assertFalse(UlidParser.isValid(nonAscii))
    }

    @Test
    fun `isValid and toBytes agree on every failure mode (no drift)`() {
        // Table-driven symmetry check: inputs that isValid rejects must also throw from
        // toBytes; inputs that isValid accepts must parse successfully. Prevents the two
        // decoder paths from silently drifting apart.
        val invalids = listOf(
            "",
            "01HV8B2YJ4M2N3X4Y5Z6ABCDE",   // 25 chars
            "01HV8B2YJ4M2N3X4Y5Z6ABCDEFF", // 27 chars
            "01HV8B2YJ4M2N3X4Y5Z6ABCDEI",  // illegal 'I'
            "01HV8B2YJ4M2N3X4Y5Z6ABCDEL",  // illegal 'L'
            "01HV8B2YJ4M2N3X4Y5Z6ABCDEO",  // illegal 'O'
            "01HV8B2YJ4M2N3X4Y5Z6ABCDEU",  // illegal 'U'
            "8ZZZZZZZZZZZZZZZZZZZZZZZZZ",  // ts overflow
        )
        for (bad in invalids) {
            assertFalse(UlidParser.isValid(bad), "isValid must reject '$bad'")
            assertThrows<IllegalArgumentException>("toBytes must reject '$bad'") {
                UlidParser.toBytes(bad)
            }
        }

        val gen = UlidIdGenerator()
        repeat(50) {
            val valid = gen.nextId()
            assertTrue(UlidParser.isValid(valid))
            UlidParser.toBytes(valid) // must not throw
        }
    }

    @Test
    fun `timestampOf matches fixed vectors`() {
        // ts = 0 (Unix epoch) encodes to 10 leading '0's.
        assertEquals(0L, UlidParser.timestampOf("0000000000ABCDEFGHJKMNPQRS").toEpochMilli())
        // ts = 1 differs only in the last char of the timestamp prefix.
        assertEquals(1L, UlidParser.timestampOf("0000000001ABCDEFGHJKMNPQRS").toEpochMilli())
        // Maximum representable 48-bit timestamp: "7ZZZZZZZZZ" is (2^48 - 1).
        assertEquals(
            (1L shl 48) - 1L,
            UlidParser.timestampOf("7ZZZZZZZZZABCDEFGHJKMNPQRS").toEpochMilli(),
        )
        // "01ARZ3NDEK" decodes to 1_469_922_850_259 (2016-07-30T23:54:10.259Z). This pins
        // the decoder against arithmetic drift.
        assertEquals(
            1_469_922_850_259L,
            UlidParser.timestampOf("01ARZ3NDEKTSV4RRFFQ69G5FAV").toEpochMilli(),
        )
    }

    @Test
    fun `toBytes produces a zero-timestamp byte prefix for the zero-timestamp vector`() {
        // Pin the Crockford Base32 decoder byte-by-byte against a known input with ts = 0.
        val ulid = "0000000000ABCDEFGHJKMNPQRS"
        val bytes = UlidParser.toBytes(ulid)
        assertEquals(16, bytes.size)
        for (i in 0..5) {
            assertEquals(0.toByte(), bytes[i], "timestamp byte $i must be zero")
        }
        // Re-encoding the bytes must yield the same ULID (symmetric codec).
        assertEquals(ulid, UlidParser.fromBytes(bytes))
    }

    @Test
    fun `decoded bytes have correct big-endian timestamp structure`() {
        val gen = UlidIdGenerator()
        val ulid = gen.nextId()
        val bytes = UlidParser.toBytes(ulid)

        // First 6 bytes = timestamp (big-endian)
        var ts = 0L
        for (i in 0..5) ts = (ts shl 8) or (bytes[i].toLong() and 0xFFL)
        assertEquals(UlidParser.timestampOf(ulid).toEpochMilli(), ts)

        // Last 10 bytes = randomness. At least one bit should be set (overwhelmingly likely).
        var anyRandomBit = 0L
        for (i in 6..15) anyRandomBit = anyRandomBit or (bytes[i].toLong() and 0xFFL)
        assertNotEquals(0L, anyRandomBit, "randomness bytes were all zero; generator is suspicious")
    }
}
