package io.github.dornol.idkit.uuidv7

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UuidV7ParserTest {

    // --- timestampOf (strict v7) ---------------------------------------------------------------

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
    fun `timestampOf decodes a crafted UUID with known top-48 bits`() {
        // Craft a UUID whose top 48 bits encode a specific epoch-ms.
        val targetMs = 1_234_567_890_123L
        val msb = (targetMs shl 16) or (0x7L shl 12) or 0x0ABL // version = 7, counter = 0xAB
        val lsb = (0x2L shl 62) or 0x0123456789ABCDEFL        // variant = 0b10
        val crafted = UUID(msb, lsb)

        assertEquals(7, crafted.version(), "precondition: crafted UUID must be v7")
        assertEquals(targetMs, UuidV7Parser.timestampOf(crafted).toEpochMilli())
    }

    @Test
    fun `timestampOf rejects every non-v7 version with a descriptive message`() {
        // One test per versioned UUID shape. rand_b filled with non-v7 bits doesn't matter for
        // the version check; only the 4 version bits (48..51 of msb) are read.
        for (version in listOf(1, 3, 4, 5, 6, 8)) {
            val msb = (0L shl 16) or ((version.toLong() and 0xFL) shl 12) or 0x123L
            val lsb = (0x2L shl 62) or 0x42L
            val u = UUID(msb, lsb)
            assertEquals(version, u.version(), "precondition: setup version for $version")

            val ex = assertThrows<IllegalArgumentException> { UuidV7Parser.timestampOf(u) }
            assertTrue(
                "version=$version" in (ex.message ?: ""),
                "exception message should carry the actual version: got '${ex.message}'",
            )
        }
    }

    // --- rawTimestampOf (no version check) -----------------------------------------------------

    @Test
    fun `rawTimestampOf returns the top 48 bits regardless of version`() {
        val targetMs = 0x0000_ABCD_1234_5678L // 48-bit value
        val msb = (targetMs shl 16) or (0x4L shl 12) or 0xAAAL // v4 on purpose, with rand_a junk
        val u = UUID(msb, 0L)
        assertEquals(4, u.version())
        assertEquals(targetMs, UuidV7Parser.rawTimestampOf(u))
    }

    @Test
    fun `extracted timestamp matches embedded bits for generator output`() {
        val gen = UuidV7IdGenerator()
        val uuid = gen.nextId()
        val msb = uuid.mostSignificantBits
        val expected = msb ushr 16
        assertEquals(expected, UuidV7Parser.rawTimestampOf(uuid))
        assertEquals(expected, UuidV7Parser.timestampOf(uuid).toEpochMilli())
    }

    // --- isValid(UUID) -------------------------------------------------------------------------

    @Test
    fun `isValid UUID accepts freshly generated v7`() {
        val gen = UuidV7IdGenerator()
        repeat(100) { assertTrue(UuidV7Parser.isValid(gen.nextId())) }
    }

    @Test
    fun `isValid UUID rejects non-v7 versions`() {
        for (version in listOf(1, 3, 4, 5, 6, 8)) {
            val msb = ((version.toLong() and 0xFL) shl 12) or 0x100L
            val u = UUID(msb, 0L)
            assertEquals(version, u.version())
            assertFalse(UuidV7Parser.isValid(u), "version $version must be rejected")
        }
    }

    @Test
    fun `isValid UUID rejects nil UUID - version zero`() {
        // new UUID(0, 0) has version() == 0; must be rejected.
        val nil = UUID(0L, 0L)
        assertEquals(0, nil.version())
        assertFalse(UuidV7Parser.isValid(nil))
    }

    // --- isValid(CharSequence) -----------------------------------------------------------------

    @Test
    fun `isValid text accepts textual v7 UUID`() {
        val gen = UuidV7IdGenerator()
        repeat(100) {
            val text: CharSequence = gen.nextId().toString()
            assertTrue(UuidV7Parser.isValid(text), "expected true for $text")
        }
    }

    @Test
    fun `isValid text accepts CharSequence that is not a String`() {
        val gen = UuidV7IdGenerator()
        val sb: CharSequence = StringBuilder(gen.nextId().toString())
        assertTrue(UuidV7Parser.isValid(sb))
    }

    @Test
    fun `isValid text rejects malformed strings`() {
        assertFalse(UuidV7Parser.isValid(""))
        assertFalse(UuidV7Parser.isValid("not-a-uuid"))
        assertFalse(UuidV7Parser.isValid("0123456789ABCDEF"))
        assertFalse(UuidV7Parser.isValid("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"))
    }

    @Test
    fun `isValid text rejects well-formed but non-v7`() {
        val v4 = UUID.randomUUID().toString()
        assertEquals(4, UUID.fromString(v4).version())
        assertFalse(UuidV7Parser.isValid(v4))

        // A valid UUID of version 0 (nil) in textual form.
        assertFalse(UuidV7Parser.isValid("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `isValid is consistent with timestampOf outcome`() {
        val gen = UuidV7IdGenerator()
        repeat(100) {
            val u = gen.nextId()
            // If isValid returns true, timestampOf must not throw.
            if (UuidV7Parser.isValid(u)) {
                UuidV7Parser.timestampOf(u) // must not throw
            }
        }
        // And the rejection side: UUIDs that isValid rejects must be rejected by timestampOf.
        val v4 = UUID.randomUUID()
        assertFalse(UuidV7Parser.isValid(v4))
        assertThrows<IllegalArgumentException> { UuidV7Parser.timestampOf(v4) }
    }
}
