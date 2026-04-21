package io.github.dornol.idkit.flake

import io.github.dornol.idkit.IdGenerator
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Custom Flake/Snowflake ID Generator
 *
 * ID 구조 (총 64bit)
 *  - 1 bit  : sign bit (unused)
 *  - X bits : timestamp delta (epoch 로부터의 경과, divisor 단위)
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
 *    [ClockMovedBackwardsException] 을 던진다. 과거의 "pin to last" 전략은 sequence 오버플로우와
 *    결합 시 실제 시계가 따라잡을 때까지 수분/수시간 busy-spin 할 위험이 있어 명시적 실패가 안전하다.
 *  - **타임스탬프 소진**: [timestampBits] 로 표현 가능한 범위를 초과하면 [IllegalStateException]
 *    이 발생한다. 시계는 앞으로만 흐르므로 이 상태는 **복구 불가능**하며, 호출자는 더 넓은
 *    [timestampBits] 또는 더 최근의 [epochStart] 로 재구성해야 한다.
 *  - **delta 계산 정밀도**: timestamp 필드는 `(now - epoch) / divisor` 로 계산된다.
 *    이전 버전은 `now/divisor - epoch/divisor` 방식이라 `divisor > 1` 이면서 `epoch` 가
 *    divisor 의 배수가 아닐 때 ±1 오차가 났는데, 이 버전에서 정밀 계산으로 교정되었다.
 *
 * 상속 관련 주의: 클래스는 `open` 이지만 [nextId] 는 `@Synchronized` 가 적용된 `final override`
 * 이므로 서브클래스에서 재정의할 수 없다. 서브클래스는 [SnowflakeIdGenerator] 처럼
 * 기본 비트 레이아웃을 미리 바인딩하는 얇은 래퍼 용도로만 사용하도록 설계되었다.
 * 테스트에서 가짜 시계가 필요하면 [currentEpochMillis] 를 override 하라.
 */
open class FlakeIdGenerator(
    val timestampBits: Int = 41,
    val datacenterIdBits: Int = 5,
    val workerIdBits: Int = 5,
    val timestampDivisor: Long = 1L,
    val epochStart: Instant = Instant.EPOCH,
    val datacenterId: Int,
    val workerId: Int,
) : IdGenerator<Long> {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 첫 번째 비트(부호 bit)는 사용하지 않음 */
        private const val UNUSED_BITS = 1
    }

    /** worker/datacenter 최대 값 */
    val maxWorkerId: Int = (1 shl workerIdBits) - 1
    val maxDatacenterId: Int = (1 shl datacenterIdBits) - 1

    /** epoch 기준 ms 값 (delta 계산 시 raw millis 로 사용). */
    private val epochStartMillis: Long = epochStart.toEpochMilli()

    /** sequence bit 및 관련 값들 (init 에서 계산) */
    val sequenceBits: Int
    val maxSequence: Long

    /** 비트 shift 값들 */
    private val timestampLeftShift: Int
    private val datacenterIdLeftShift: Int
    private val workerIdLeftShift: Int

    /** timestamp 필드의 최대 표현 값 */
    private val maxTimestamp: Long

    /** 동일 timestamp 슬라이스 내 sequence 증가 카운터 */
    private var sequenceCounter = 0L

    /** 마지막으로 생성된 timestamp 슬라이스 (epoch 로부터 divisor 단위) */
    private var lastGeneratedTimestamp = -1L

    init {
        require(timestampBits > 0) { "timestampBits must be greater than 0, but was $timestampBits" }
        require(timestampDivisor > 0) { "timestampDivisor must be greater than 0, but was $timestampDivisor" }
        require(datacenterIdBits in 1..5) {
            "datacenterIdBits must be between 1 and 5 (max 32 datacenters), but was $datacenterIdBits"
        }
        // Upper bound 31: (1 shl workerIdBits) must fit in positive Int range.
        require(workerIdBits in 1..31) {
            "workerIdBits must be between 1 and 31, but was $workerIdBits"
        }

        val totalBits = UNUSED_BITS + timestampBits + datacenterIdBits + workerIdBits
        require(totalBits <= 63) {
            "Total bits (unused=$UNUSED_BITS + timestampBits=$timestampBits + " +
                    "datacenterIdBits=$datacenterIdBits + workerIdBits=$workerIdBits = $totalBits) " +
                    "cannot exceed 63 (need at least 1 bit for sequence)"
        }

        require(workerId in 0..maxWorkerId) {
            "workerId must be between 0 and $maxWorkerId, but was $workerId"
        }
        require(datacenterId in 0..maxDatacenterId) {
            "datacenterId must be between 0 and $maxDatacenterId, but was $datacenterId"
        }

        sequenceBits = 64 - totalBits
        maxSequence = (1L shl sequenceBits) - 1

        timestampLeftShift = datacenterIdBits + workerIdBits + sequenceBits
        datacenterIdLeftShift = workerIdBits + sequenceBits
        workerIdLeftShift = sequenceBits

        maxTimestamp = (1L shl timestampBits) - 1

        if (log.isDebugEnabled) {
            log.debug(
                "Initialized FlakeIdGenerator: timestampBits={}, workerIdBits={}, sequenceBits={}, maxSequence={}",
                timestampBits, workerIdBits, sequenceBits, maxSequence
            )
        }
    }

    /**
     * ID 생성 (동기화).
     *
     *  - timestamp 슬라이스가 이전과 같으면 sequence 를 증가시킨다.
     *  - sequence 가 overflow 되면 다음 시간 슬라이스까지 대기한다.
     *  - 시계 역행 감지 시 [ClockMovedBackwardsException] 을 던진다.
     *
     * 모든 검증은 내부 상태를 변경하기 **전에** 수행하므로 예외 발생 시 generator 인스턴스는
     * 오염 없이 재호출 가능하다. 단 [IllegalStateException] (timestamp 비트 초과) 은 시계가
     * 앞으로만 흐르기 때문에 실질적으로 복구 불가능하다.
     *
     * @throws ClockMovedBackwardsException 시스템 시계가 이전 관측값보다 작은 값을 반환했을 때.
     * @throws IllegalStateException timestamp 델타가 [timestampBits] 로 표현 가능한 최대값을 초과했을 때.
     */
    @Synchronized
    final override fun nextId(): Long {
        var timestamp = computeSlice(currentEpochMillis())

        if (timestamp < lastGeneratedTimestamp) {
            throw ClockMovedBackwardsException(
                driftAmount = lastGeneratedTimestamp - timestamp,
                timestampDivisor = timestampDivisor,
            )
        }

        val nextSequence: Long = if (timestamp == lastGeneratedTimestamp) {
            val candidate = (sequenceCounter + 1) and maxSequence
            if (candidate == 0L) {
                // sequence overflow → 다음 슬라이스까지 대기 후 해당 슬라이스의 첫 id 로 시작
                timestamp = waitForNextSlice(timestamp)
                0L
            } else {
                candidate
            }
        } else {
            0L
        }

        check(timestamp in 0..maxTimestamp) {
            "Timestamp overflow: delta $timestamp exceeds $timestampBits-bit maximum ($maxTimestamp)"
        }

        // 모든 검증 통과 → 내부 상태 커밋
        lastGeneratedTimestamp = timestamp
        sequenceCounter = nextSequence

        return (timestamp shl timestampLeftShift) or
                (datacenterId.toLong() shl datacenterIdLeftShift) or
                (workerId.toLong() shl workerIdLeftShift) or
                nextSequence
    }

    /**
     * 현재 wall-clock epoch 밀리초를 반환한다.
     *
     * 테스트에서 가짜 시계를 주입할 수 있도록 `protected open` 으로 노출된다.
     * 프로덕션 코드에서는 override 하지 말 것.
     *
     * @since 2.0.0
     */
    protected open fun currentEpochMillis(): Long = System.currentTimeMillis()

    /** wall-clock millis 를 epoch 기준 divisor-단위 슬라이스로 변환 (정밀). */
    private fun computeSlice(nowMillis: Long): Long =
        (nowMillis - epochStartMillis) / timestampDivisor

    /** `currentSlice` 보다 큰 다음 슬라이스가 될 때까지 busy-spin 한 뒤 새 슬라이스를 반환. */
    private fun waitForNextSlice(currentSlice: Long): Long {
        var slice = computeSlice(currentEpochMillis())
        while (slice <= currentSlice) {
            Thread.onSpinWait()
            slice = computeSlice(currentEpochMillis())
        }
        return slice
    }
}
