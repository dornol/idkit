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
        // Extract the timestamp portion (high 48 bits) and compare
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

        // Version bits (48..51) are 0b0111 = 7
        val version = ((msb shr 12) and 0xFL).toInt()
        assertEquals(7, version, "Version bits should be 7")

        // timestamp lives in the high 48 bits
        val timestamp = msb ushr 16
        val now = System.currentTimeMillis()
        // timestamp should be near the current time (±5s)
        assertTrue(timestamp in (now - 5000)..now, "Timestamp should be close to current time")

        // rand_a is 12 bits (52..63)
        val randA = msb and 0xFFFL
        assertTrue(randA in 0 until (1L shl 12), "rand_a should be within 12-bit range")

        // Verify variant bits inside leastSignificantBits
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

    @Test
    fun `mostSigBits are strictly monotonic across tight loop`() {
        // A tight loop on a modern JVM generates thousands of UUIDs per ms.
        // RFC 9562 Method 2 requires intra-ms monotonicity via a dedicated counter,
        // so mostSigBits (which carries timestamp + version + counter) must strictly increase.
        val count = 100_000
        var prev = generator.nextId().mostSignificantBits
        repeat(count) { i ->
            val curr = generator.nextId().mostSignificantBits
            assertTrue(
                curr > prev,
                "mostSigBits must strictly increase at iteration $i: prev=$prev, curr=$curr"
            )
            prev = curr
        }
    }

    @Test
    fun `counter increments within same millisecond`() {
        // Burst-generate UUIDs; most should fall in the same ms window.
        // Group by timestamp portion and verify counters within each group are strictly increasing.
        val uuids = (0 until 20_000).map { generator.nextId() }
        val byTimestamp = uuids.groupBy { it.mostSignificantBits ushr 16 }

        // At least one group should have multiple UUIDs sharing the same ms
        // (otherwise the test is meaningless on this hardware).
        val sharedMsGroup = byTimestamp.values.firstOrNull { it.size > 1 }
        assertTrue(sharedMsGroup != null, "Expected at least one ms with multiple UUIDs")

        byTimestamp.values.filter { it.size > 1 }.forEach { group ->
            val counters = group.map { it.mostSignificantBits and 0xFFFL }
            for (i in 1 until counters.size) {
                assertTrue(
                    counters[i] > counters[i - 1],
                    "Counter must strictly increase within same ms: ${counters[i - 1]} -> ${counters[i]}"
                )
            }
        }
    }

    @Test
    fun `concurrent generation preserves global monotonicity of mostSigBits`() {
        // Under contention, CAS must still produce globally ordered mostSigBits (per-generator).
        val threads = 8
        val perThread = 10_000
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val allUuids = Collections.synchronizedList(ArrayList<UUID>(threads * perThread))

        repeat(threads) {
            pool.submit {
                try {
                    gate.await()
                    val local = ArrayList<UUID>(perThread)
                    repeat(perThread) { local += generator.nextId() }
                    allUuids.addAll(local)
                } finally {
                    done.countDown()
                }
            }
        }
        gate.countDown()
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(finished, "Workers did not finish in time")

        // After sorting by mostSigBits, there must be no duplicates — proving every
        // generator call produced a distinct (ts, counter) pair.
        val sorted = allUuids.map { it.mostSignificantBits }.sorted()
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i] > sorted[i - 1],
                "Duplicate mostSigBits detected at index $i: ${sorted[i - 1]} == ${sorted[i]}"
            )
        }
    }
}
