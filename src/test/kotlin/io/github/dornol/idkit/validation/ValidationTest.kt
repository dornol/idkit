package io.github.dornol.idkit.validation

import io.github.dornol.idkit.ulid.UlidIdGenerator
import io.github.dornol.idkit.uuidv7.UuidV7IdGenerator
import jakarta.validation.Validation
import jakarta.validation.Validator
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
    data class UlidCharSeqHolder(@field:ValidUlid val value: CharSequence?)

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
    fun `ValidUlid rejects wrong length`() {
        val violations = validator.validate(UlidHolder("01ARZ3NDEKTSV4RRFFQ69G5FA"))  // 25 chars
        assertEquals(1, violations.size)
        assertEquals("must be a valid ULID", violations.first().message)
    }

    @Test
    fun `ValidUlid rejects illegal Crockford Base32 characters`() {
        // 'I', 'L', 'O', 'U' are excluded from Crockford Base32.
        val violations = validator.validate(UlidHolder("01ARZ3NDEKTSV4RRFFQ69G5FAI"))
        assertEquals(1, violations.size)
    }

    @Test
    fun `ValidUlid rejects timestamp overflow (first char beyond 7)`() {
        // ULID's 48-bit timestamp caps the first Base32 symbol at 7.
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
    fun `ValidUuidV7 rejects a well-formed but non-v7 UUID string`() {
        val v4 = UUID.randomUUID().toString()
        val violations = validator.validate(UuidStringHolder(v4))
        assertEquals(1, violations.size)
    }
}
