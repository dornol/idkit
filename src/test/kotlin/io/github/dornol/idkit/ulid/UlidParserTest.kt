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
    fun `toBytes rejects malformed input`() {
        assertThrows<IllegalArgumentException> { UlidParser.toBytes("TOO-SHORT") }
        assertThrows<IllegalArgumentException> {
            // Invalid char 'I' is excluded from Crockford Base32
            UlidParser.toBytes("01HIIIIIIII2N3X4Y5Z6ABCDEF")
        }
        assertThrows<IllegalArgumentException> {
            // First char '8' encodes 0b01000 — top bit of the 48-bit timestamp padding is set
            UlidParser.toBytes("8ZZZZZZZZZZZZZZZZZZZZZZZZZ")
        }
    }

    @Test
    fun `fromBytes rejects non-16-byte input`() {
        assertThrows<IllegalArgumentException> { UlidParser.fromBytes(ByteArray(15)) }
        assertThrows<IllegalArgumentException> { UlidParser.fromBytes(ByteArray(17)) }
    }

    @Test
    fun `isValid accepts generated ulids and rejects malformed strings`() {
        val gen = UlidIdGenerator()
        repeat(100) {
            assertTrue(UlidParser.isValid(gen.nextId()))
        }
        assertFalse(UlidParser.isValid(""))
        assertFalse(UlidParser.isValid("01HV8B2YJ4M2N3X4Y5Z6ABCDE"))  // 25 chars
        assertFalse(UlidParser.isValid("01HV8B2YJ4M2N3X4Y5Z6ABCDEFF")) // 27 chars
        assertFalse(UlidParser.isValid("01HIIIIIII2N3X4Y5Z6ABCDEFF"))  // 'I' not allowed
        assertFalse(UlidParser.isValid("8ZZZZZZZZZZZZZZZZZZZZZZZZZ"))  // timestamp overflow
    }

    @Test
    fun `timestampOf matches fixed vectors`() {
        // ts = 0 (Unix epoch) encodes to 10 leading '0's.
        assertEquals(0L, UlidParser.timestampOf("0000000000ABCDEFGHJKMNPQRS").toEpochMilli())
        // ts = 1 differs only in the last char of the timestamp prefix.
        assertEquals(1L, UlidParser.timestampOf("0000000001ABCDEFGHJKMNPQRS").toEpochMilli())
        // "01ARZ3NDEK" decodes to 1_469_922_850_259 (2016-07-30T23:54:10.259Z). This pins
        // the decoder against arithmetic drift.
        assertEquals(
            1_469_922_850_259L,
            UlidParser.timestampOf("01ARZ3NDEKTSV4RRFFQ69G5FAV").toEpochMilli(),
        )
    }

    @Test
    fun `decoded bytes have correct structure`() {
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
