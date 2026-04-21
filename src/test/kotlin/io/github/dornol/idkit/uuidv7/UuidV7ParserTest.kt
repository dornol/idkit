package io.github.dornol.idkit.uuidv7

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UuidV7ParserTest {

    @Test
    fun `timestampOf round-trips a freshly generated UUID v7`() {
        val gen = UuidV7IdGenerator()
        val before = System.currentTimeMillis()
        val uuid = gen.nextId()
        val after = System.currentTimeMillis()

        val ts = UuidV7Parser.timestampOf(uuid).toEpochMilli()
        assertTrue(ts in before..after, "decoded ts=$ts not in [$before..$after]")
    }

    @Test
    fun `timestampOf rejects a non-v7 UUID`() {
        val v4 = UUID.randomUUID()
        assertEquals(4, v4.version(), "setup precondition: randomUUID must produce v4")
        assertThrows<IllegalArgumentException> { UuidV7Parser.timestampOf(v4) }
    }

    @Test
    fun `rawTimestampOf does not validate version`() {
        // rawTimestampOf should accept any UUID and return whatever is in the top 48 bits,
        // even if the UUID isn't a v7.
        val v4 = UUID.randomUUID()
        val raw = UuidV7Parser.rawTimestampOf(v4)
        assertTrue(raw >= 0, "raw value must be non-negative (top bit of the mostSigBits is used by the ts field)")
    }

    @Test
    fun `extracted timestamp matches embedded bits`() {
        val gen = UuidV7IdGenerator()
        val uuid = gen.nextId()
        val msb = uuid.mostSignificantBits
        val expected = msb ushr 16
        assertEquals(expected, UuidV7Parser.rawTimestampOf(uuid))
        assertEquals(expected, UuidV7Parser.timestampOf(uuid).toEpochMilli())
    }
}
