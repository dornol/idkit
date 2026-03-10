package io.github.dornol.idkit.flake

import io.github.dornol.idkit.IdGenerator
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Custom Flake/Snowflake ID Generator
 *
 * ID 구조 (총 64bit)
 *  - 1 bit  : sign bit (unused)
 *  - X bits : timestamp
 *  - Y bits : datacenterId
 *  - Z bits : workerId
 *  - R bits : sequence (동일 ms 에서 증가하는 카운터)
 *
 * timestampDivisor 를 이용해 ms 단위가 아닌 큰 단위로 timestamp 범위를 줄일 수 있음.
 */
open class FlakeIdGenerator(
    val timestampBits: Int = 41,
    val datacenterIdBits: Int = 5,
    val workerIdBits: Int = 5,
    val timestampDivisor: Long = 1L,
    val epochStart: Instant = Instant.EPOCH,
    val datacenterId: Long,
    val workerId: Long,
) : IdGenerator<Long> {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 첫 번째 비트(부호 bit)는 사용하지 않음 */
        private const val UNUSE_BITS = 1
    }

    /** worker/datacenter 최대 값 */
    val maxWorkerId = (1L shl workerIdBits) - 1
    val maxDatacenterId = (1L shl datacenterIdBits) - 1

    /** sequence bit 및 관련 값들 */
    val sequenceBits: Int
    val maxSequence: Long

    /** 비트 shift 값들 */
    private val timestampLeftShift: Int
    private val datacenterIdLeftShift: Int
    private val workerIdLeftShift: Int

    /** timestamp 최대 델타값 */
    private val maxTimestampDelta: Long

    init {
        // 비트 총합 제한
        require(UNUSE_BITS + timestampBits + datacenterIdBits + workerIdBits <= 63) {
            "Total bits (timestampBits=$timestampBits + datacenterIdBits=$datacenterIdBits + workerIdBits=$workerIdBits + unuseBits=$UNUSE_BITS = ${UNUSE_BITS + timestampBits + datacenterIdBits + workerIdBits}) cannot exceed 63"
        }

        require(timestampDivisor > 0) { "timestampDivisor must be greater than 0" }
        require(datacenterIdBits in 1..5) { "datacenterIdBits must be between 1 and 5, but was $datacenterIdBits" }
        require(workerIdBits > 0) { "workerIdBits must be greater than 0, but was $workerIdBits" }

        require(workerId in 0..maxWorkerId) {
            "workerId must be between 0 and $maxWorkerId"
        }
        require(datacenterId in 0..maxDatacenterId) {
            "datacenterId must be between 0 and $maxDatacenterId"
        }

        // sequence bit 수 계산
        sequenceBits = 64 - UNUSE_BITS - timestampBits - datacenterIdBits - workerIdBits
        maxSequence = (1L shl sequenceBits) - 1

        // bit shift 계산
        timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
        datacenterIdLeftShift = workerIdBits + sequenceBits
        workerIdLeftShift = sequenceBits

        // timestamp 오버플로우 검증용 최대값
        maxTimestampDelta = (1L shl timestampBits) - 1

        if (log.isDebugEnabled) {
            log.debug(
                "Initialized FlakeIdGenerator: timestampBits={}, workerIdBits={}, sequenceBits={}, maxSequence={}",
                timestampBits, workerIdBits, sequenceBits, maxSequence
            )
        }
    }

    /** epoch 시작 값을 divisor 로 스케일 축소한 값 */
    private val epochStartValue: Long = epochStart.toEpochMilli() / timestampDivisor

    /** 동일 timestamp 내 sequence 증가값 */
    private var sequenceCounter = 0L

    /** 마지막 timestamp */
    private var lastGeneratedTimestamp = -1L

    /**
     * ID 생성 (동기화)
     *
     * timestamp 가 같으면 sequence 증가
     * sequence 가 overflow → 다음 timestamp 까지 대기
     */
    @Synchronized
    override fun nextId(): Long {
        var timestamp = currentTimestamp()

        // 시간 역전 발생 시 → last timestamp 로 고정
        // (원본 snowflake 는 에러 또는 wait)
        if (timestamp < lastGeneratedTimestamp) {
            timestamp = lastGeneratedTimestamp
        }

        // 동일한 timestamp 인 경우 → sequence 증가
        if (timestamp == lastGeneratedTimestamp) {
            sequenceCounter = (sequenceCounter + 1) and maxSequence

            // sequence overflow → 다음 millisecond 대기
            if (sequenceCounter == 0L) {
                timestamp = waitNextMillis(lastGeneratedTimestamp)
            }
        } else {
            sequenceCounter = 0L
        }

        lastGeneratedTimestamp = timestamp

        val timestampDelta = timestamp - epochStartValue
        check(timestampDelta in 0..maxTimestampDelta) {
            "Timestamp overflow: delta $timestampDelta exceeds $timestampBits-bit maximum ($maxTimestampDelta)"
        }

        return (timestampDelta shl timestampLeftShift) or
                (datacenterId shl datacenterIdLeftShift) or
                (workerId shl workerIdLeftShift) or
                sequenceCounter
    }

    /** 현재 timestamp 가져오기 */
    private fun currentTimestamp(): Long = System.currentTimeMillis() / timestampDivisor

    /** 다음 timestamp 까지 busy-spin 하며 대기 */
    private fun waitNextMillis(lastTs: Long): Long {
        var ts = currentTimestamp()
        while (ts <= lastTs) {
            Thread.onSpinWait()
            ts = currentTimestamp()
        }
        return ts
    }

}
