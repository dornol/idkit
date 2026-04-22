package io.github.dornol.idkit.validation

import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.NotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationTest {

    private lateinit var validator: Validator

    @BeforeAll
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
    }

    // --- ULID ----------------------------------------------------------------------------------

    data class UlidHolder(@field:ValidUlid val value: String?)
    data class UlidVarHolder(@field:ValidUlid var value: String?)
    data class UlidCharSeqHolder(@field:ValidUlid val value: CharSequence?)
    data class UlidRequired(@field:ValidUlid @field:NotNull val value: String?)

    @Test
    fun `ValidUlid accepts a freshly generated ULID`() {
        val ulid = UlidIdGenerator().nextId()
        val violations = validator.validate(UlidHolder(ulid))
        assertTrue(violations.isEmpty()) {
            "expected no violations, got: ${violations.map { it.message }}"
        }
    }

    @Test
    fun `ValidUlid accepts null (null-check is NotNull's job)`() {
        val violations = validator.validate(UlidHolder(null))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `ValidUlid works on a var property just like a val property`() {
        val good = UlidVarHolder(UlidIdGenerator().nextId())
        assertTrue(validator.validate(good).isEmpty())

        val bad = UlidVarHolder("too-short")
        assertEquals(1, validator.validate(bad).size)

        // Mutating the property and re-validating must reflect the change.
        bad.value = UlidIdGenerator().nextId()
        assertTrue(validator.validate(bad).isEmpty())
    }

    @Test
    fun `ValidUlid plus NotNull composes (NotNull fires on null, both valid on a good ULID)`() {
        val viaNull = validator.validate(UlidRequired(null))
        // NotNull must fire; ValidUlid is skipped on null.
        assertEquals(1, viaNull.size)
        assertEquals(
            "jakarta.validation.constraints.NotNull",
            viaNull.first().constraintDescriptor.annotation.annotationClass.java.name,
        )

        assertTrue(validator.validate(UlidRequired(UlidIdGenerator().nextId())).isEmpty())
    }

    @Test
    fun `ValidUlid rejects wrong length`() {
        val violations = validator.validate(UlidHolder("01ARZ3NDEKTSV4RRFFQ69G5FA"))  // 25 chars
        assertEquals(1, violations.size)
        assertEquals("must be a valid ULID", violations.first().message)
    }

    @Test
    fun `ValidUlid rejects each Crockford-excluded character individually`() {
        val prefix = "01ARZ3NDEKTSV4RRFFQ69G5FA" // 25 valid chars
        for (badChar in listOf('I', 'L', 'O', 'U')) {
            val input = prefix + badChar
            val violations = validator.validate(UlidHolder(input))
            assertEquals(1, violations.size, "trailing '$badChar' must be rejected (got $violations)")
        }
    }

    @Test
    fun `ValidUlid rejects lowercase input (idkit is case-sensitive)`() {
        val lower = UlidIdGenerator().nextId().lowercase()
        val violations = validator.validate(UlidHolder(lower))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUlid rejects timestamp overflow (first char beyond 7)`() {
        val violations = validator.validate(UlidHolder("81ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUlid works against CharSequence properties (e_g_ StringBuilder)`() {
        val ulid: CharSequence = StringBuilder(UlidIdGenerator().nextId())
        val violations = validator.validate(UlidCharSeqHolder(ulid))
        assertTrue(violations.isEmpty())
    }

    // --- UUID v7 -------------------------------------------------------------------------------

    data class UuidHolder(@field:ValidUuidV7 val value: UUID?)
    data class UuidStringHolder(@field:ValidUuidV7 val value: String?)

    @Test
    fun `ValidUuidV7 accepts a freshly generated v7 UUID`() {
        val uuid = UuidV7IdGenerator().nextId()
        val violations = validator.validate(UuidHolder(uuid))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `ValidUuidV7 rejects a random v4 UUID`() {
        val violations = validator.validate(UuidHolder(UUID.randomUUID()))
        assertEquals(1, violations.size)
        assertEquals("must be a valid UUID v7", violations.first().message)
    }

    @Test
    fun `ValidUuidV7 rejects the nil UUID - version zero`() {
        val nil = UUID(0L, 0L)
        assertEquals(0, nil.version())
        val violations = validator.validate(UuidHolder(nil))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUuidV7 rejects every non-v7 version`() {
        for (version in listOf(1, 3, 4, 5, 6, 8)) {
            val msb = ((version.toLong() and 0xFL) shl 12) or 0x100L
            val crafted = UUID(msb, 0L)
            assertEquals(version, crafted.version())
            val violations = validator.validate(UuidHolder(crafted))
            assertEquals(1, violations.size, "version $version must be rejected")
        }
    }

    @Test
    fun `ValidUuidV7 accepts null`() {
        assertTrue(validator.validate(UuidHolder(null)).isEmpty())
        assertTrue(validator.validate(UuidStringHolder(null)).isEmpty())
    }

    @Test
    fun `ValidUuidV7 accepts a textual v7 UUID`() {
        val text = UuidV7IdGenerator().nextId().toString()
        val violations = validator.validate(UuidStringHolder(text))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `ValidUuidV7 rejects a malformed string`() {
        val violations = validator.validate(UuidStringHolder("not-a-uuid"))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUuidV7 rejects a well-formed but non-v7 UUID string (v4)`() {
        val v4 = UUID.randomUUID().toString()
        val violations = validator.validate(UuidStringHolder(v4))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUuidV7 rejects the all-zero UUID string`() {
        val violations = validator.validate(UuidStringHolder("00000000-0000-0000-0000-000000000000"))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUuidV7 rejects an empty string`() {
        val violations = validator.validate(UuidStringHolder(""))
        assertEquals(1, violations.size)
    }
}
