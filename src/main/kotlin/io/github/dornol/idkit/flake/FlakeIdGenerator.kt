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
 *  - R bits : sequence (동일 timestamp 슬라이스 내 증가 카운터)
 *
 * [timestampDivisor] 로 ms 보다 큰 단위로 timestamp 해상도를 낮출 수 있다.
 *
 * ### 동작 특성
 *  - **스레드 안전**: [nextId] 는 `@Synchronized` 로 직렬화된다.
 *  - **시퀀스 오버플로우**: 동일 timestamp 내 sequence 소진 시 다음 시간 슬라이스까지
 *    [Thread.onSpinWait] 로 busy-wait 한다 (최대 한 슬라이스 단위로 제한).
 *  - **시계 역행**: [System.currentTimeMillis] 가 이전에 관측된 값보다 작으면
 *    [ClockMovedBackwardsException] 을 던진다. 과거처럼 "pin to last" 전략을 유지하면
 *    시퀀스가 함께 고갈될 때 실제 시계가 따라잡을 때까지 수분/수시간 busy-spin 할 위험이 있어
 *    명시적 실패가 안전하다.
 *  - **타임스탬프 소진**: [timestampBits] 로 표현 가능한 범위를 초과하면
 *    [IllegalStateException] 이 발생한다. 시계는 앞으로만 흐르므로 이 상태는 **복구 불가능**하며
 *    같은 인스턴스는 이후에도 계속 실패한다. 호출자는 더 넓은 [timestampBits] 또는 더 최근의
 *    [epochStart] 로 재구성해야 한다.
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
        private const val UNUSED_BITS = 1
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
        require(UNUSED_BITS + timestampBits + datacenterIdBits + workerIdBits <= 63) {
            "Total bits (timestampBits=$timestampBits + datacenterIdBits=$datacenterIdBits + workerIdBits=$workerIdBits + unusedBits=$UNUSED_BITS = ${UNUSED_BITS + timestampBits + datacenterIdBits + workerIdBits}) cannot exceed 63"
        }

        require(timestampDivisor > 0) { "timestampDivisor must be greater than 0" }
        require(datacenterIdBits in 1..5) { "datacenterIdBits must be between 1 and 5 (max 32 datacenters), but was $datacenterIdBits" }
        require(workerIdBits > 0) { "workerIdBits must be greater than 0, but was $workerIdBits" }

        require(workerId in 0..maxWorkerId) {
            "workerId must be between 0 and $maxWorkerId"
        }
        require(datacenterId in 0..maxDatacenterId) {
            "datacenterId must be between 0 and $maxDatacenterId"
        }

        // sequence bit 수 계산
        sequenceBits = 64 - UNUSED_BITS - timestampBits - datacenterIdBits - workerIdBits
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
     * ID 생성 (동기화).
     *
     *  - timestamp 가 이전과 같으면 sequence 를 증가시킨다.
     *  - sequence 가 overflow 되면 다음 시간 슬라이스까지 대기한다.
     *  - 시계 역행 감지 시 [ClockMovedBackwardsException] 을 던진다.
     *
     * 모든 검증은 내부 상태(`lastGeneratedTimestamp`, `sequenceCounter`) 를 변경하기 **전에** 수행하므로,
     * 예외 발생 시 generator 인스턴스는 오염 없이 재호출 가능하다. 단, [IllegalStateException]
     * (timestamp 비트 초과) 은 시계가 앞으로만 흐르기 때문에 실질적으로 복구 불가능하다.
     *
     * @throws ClockMovedBackwardsException 시스템 시계가 이전 관측값보다 작은 값을 반환했을 때.
     * @throws IllegalStateException timestamp 델타가 [timestampBits] 로 표현 가능한 최대값을 초과했을 때.
     */
    @Synchronized
    override fun nextId(): Long {
        var timestamp = currentTimestamp()

        // 시계 역행 감지 → 명시적 실패. (과거의 "pin to last" 는 sequence overflow 결합 시 CPU 100% 스핀 유발)
        if (timestamp < lastGeneratedTimestamp) {
            throw ClockMovedBackwardsException(
                driftAmount = lastGeneratedTimestamp - timestamp,
                timestampDivisor = timestampDivisor,
            )
        }

        // 다음 sequence 값 계산 (아직 커밋하지 않음)
        val nextSequence: Long = if (timestamp == lastGeneratedTimestamp) {
            val candidate = (sequenceCounter + 1) and maxSequence
            if (candidate == 0L) {
                // sequence overflow → 다음 시간 슬라이스까지 대기 후 새 슬라이스의 첫 id 로 시작
                timestamp = waitNextMillis(lastGeneratedTimestamp)
                0L
            } else {
                candidate
            }
        } else {
            0L
        }

        val timestampDelta = timestamp - epochStartValue
        check(timestampDelta in 0..maxTimestampDelta) {
            "Timestamp overflow: delta $timestampDelta exceeds $timestampBits-bit maximum ($maxTimestampDelta)"
        }

        // 모든 검증 통과 → 내부 상태 커밋
        lastGeneratedTimestamp = timestamp
        sequenceCounter = nextSequence

        return (timestampDelta shl timestampLeftShift) or
                (datacenterId shl datacenterIdLeftShift) or
                (workerId shl workerIdLeftShift) or
                nextSequence
    }

    /**
     * 현재 timestamp 를 [timestampDivisor] 단위로 반환한다.
     *
     * 테스트에서 가짜 시계를 주입할 수 있도록 `protected open` 으로 노출된다.
     * 프로덕션 코드에서 override 하지 않도록 주의한다.
     */
    protected open fun currentTimestamp(): Long = System.currentTimeMillis() / timestampDivisor

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
