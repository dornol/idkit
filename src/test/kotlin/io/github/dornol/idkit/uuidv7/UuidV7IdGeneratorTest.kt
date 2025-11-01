package io.github.dornol.idkit.uuidv7

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UuidV7IdGeneratorTest {

    private lateinit var generator: UuidV7IdGenerator

    @BeforeEach
    fun setUp() {
        generator = UuidV7IdGenerator()
    }

    @Test
    fun `should generate UUID version 7`() {
        val uuid = generator.nextId()
        assertEquals(7, uuid.version())
    }

    @Test
    fun `should generate sequential UUIDs`() {
        val uuid1 = generator.nextId()
        Thread.sleep(3)
        val uuid2 = generator.nextId()
        assertTrue(uuid2.toString() > uuid1.toString(), "UUID2 should be greater than UUID1")
    }
}