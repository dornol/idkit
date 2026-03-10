package io.github.dornol.idkit.uuidv7

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        // timestamp 부분(상위 48비트)을 추출하여 비교
        val ts1 = uuid1.mostSignificantBits ushr 16
        val ts2 = uuid2.mostSignificantBits ushr 16
        assertTrue(ts2 >= ts1, "UUID2 timestamp should be >= UUID1 timestamp: ts1=$ts1, ts2=$ts2")
    }

    @Test
    fun `should have correct variant bits`() {
        repeat(100) {
            val uuid = generator.nextId()
            assertEquals(2, uuid.variant(), "UUID v7 variant must be 2 (IETF RFC 4122)")
        }
    }

    @Test
    fun `should have correct bit layout`() {
        val uuid = generator.nextId()
        val msb = uuid.mostSignificantBits

        // version 비트(48..51)는 0b0111 = 7
        val version = ((msb shr 12) and 0xFL).toInt()
        assertEquals(7, version, "Version bits should be 7")

        // timestamp는 상위 48비트에 위치
        val timestamp = msb ushr 16
        val now = System.currentTimeMillis()
        // timestamp가 현재 시간 근처(±5초)인지 확인
        assertTrue(timestamp in (now - 5000)..now, "Timestamp should be close to current time")

        // rand_a는 12비트(비트 52..63)
        val randA = msb and 0xFFFL
        assertTrue(randA in 0 until (1L shl 12), "rand_a should be within 12-bit range")

        // leastSignificantBits의 variant 확인
        val lsb = uuid.leastSignificantBits
        val variantBits = (lsb ushr 62) and 0x3L
        assertEquals(2L, variantBits, "Variant bits (top 2 bits of lsb) should be 0b10")
    }

    @Test
    fun `concurrent generation produces unique UUIDs`() {
        val threads = 8
        val perThread = 5000
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val set = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>(threads * perThread))

        repeat(threads) {
            pool.submit {
                try {
                    gate.await()
                    repeat(perThread) {
                        val uuid = generator.nextId()
                        assertTrue(set.add(uuid), "Duplicate UUID detected: $uuid")
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        gate.countDown()
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(finished, "Workers did not finish in time")
        assertEquals(threads * perThread, set.size)
    }

    @Test
    fun `sequential generation produces unique UUIDs`() {
        val count = 10_000
        val uuids = mutableSetOf<UUID>()
        repeat(count) {
            val uuid = generator.nextId()
            assertTrue(uuids.add(uuid), "Duplicate UUID detected at iteration $it: $uuid")
        }
        assertEquals(count, uuids.size)
    }
}
