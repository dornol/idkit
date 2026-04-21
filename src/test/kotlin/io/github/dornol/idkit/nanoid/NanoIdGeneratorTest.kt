package io.github.dornol.idkit.nanoid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NanoIdGeneratorTest {

    @Test
    fun `default id is 21 characters long`() {
        val gen = NanoIdGenerator()
        repeat(1_000) {
            assertEquals(21, gen.nextId().length)
        }
    }

    @Test
    fun `default ids use only the URL-safe alphabet`() {
        val gen = NanoIdGenerator()
        val allowed = NanoIdGenerator.DEFAULT_ALPHABET.toSet()
        repeat(10_000) {
            val id = gen.nextId()
            id.forEach { c ->
                assertTrue(c in allowed, "Char '$c' in '$id' is not URL-safe")
            }
        }
    }

    @Test
    fun `custom size controls output length`() {
        val gen = NanoIdGenerator(size = 10)
        repeat(100) {
            assertEquals(10, gen.nextId().length)
        }
    }

    @Test
    fun `custom alphabet restricts the output characters`() {
        val gen = NanoIdGenerator(size = 200, alphabet = "AB")
        val id = gen.nextId()
        assertEquals(200, id.length)
        id.forEach { c ->
            assertTrue(c == 'A' || c == 'B', "unexpected char '$c' in '$id'")
        }
    }

    @Test
    fun `constructor rejects non-positive size`() {
        assertThrows<IllegalArgumentException> { NanoIdGenerator(size = 0) }
        assertThrows<IllegalArgumentException> { NanoIdGenerator(size = -1) }
    }

    @Test
    fun `constructor rejects too-short alphabet`() {
        assertThrows<IllegalArgumentException> { NanoIdGenerator(alphabet = "") }
        assertThrows<IllegalArgumentException> { NanoIdGenerator(alphabet = "A") }
    }

    @Test
    fun `large batch is collision-free`() {
        val gen = NanoIdGenerator()
        val count = 100_000
        val ids = (0 until count).map { gen.nextId() }.toHashSet()
        assertEquals(count, ids.size, "duplicate detected in $count default-size ids")
    }

    @Test
    fun `concurrent generation yields unique ids`() {
        val gen = NanoIdGenerator()
        val threads = 8
        val perThread = 10_000
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val set = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>(threads * perThread))

        repeat(threads) {
            pool.submit {
                try {
                    gate.await()
                    repeat(perThread) {
                        val id = gen.nextId()
                        assertTrue(set.add(id), "Duplicate: '$id'")
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        gate.countDown()
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(finished, "workers did not finish in time")
        assertEquals(threads * perThread, set.size)
    }

    @Test
    fun `binary alphabet produces approximately uniform distribution`() {
        // Sanity check that SecureRandom.nextInt(2) is unbiased enough over 10k samples.
        // With p = 0.5, stddev ≈ 50; the [4500, 5500] window is ~10 stddev away from the
        // mean, so the test is effectively never flaky.
        val gen = NanoIdGenerator(size = 1, alphabet = "01")
        var zeros = 0
        var ones = 0
        repeat(10_000) {
            when (gen.nextId()) {
                "0" -> zeros++
                "1" -> ones++
            }
        }
        assertTrue(zeros in 4_500..5_500, "zeros=$zeros outside [4500..5500]")
        assertTrue(ones in 4_500..5_500, "ones=$ones outside [4500..5500]")
        assertEquals(10_000, zeros + ones)
    }

    @Test
    fun `DEFAULT_ALPHABET has 64 unique URL-safe characters`() {
        val alphabet = NanoIdGenerator.DEFAULT_ALPHABET
        assertEquals(64, alphabet.length)
        assertEquals(64, alphabet.toSet().size, "DEFAULT_ALPHABET contains duplicates")
        val expected = ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('_', '-')
        assertEquals(expected, alphabet.toSet())
    }
}
