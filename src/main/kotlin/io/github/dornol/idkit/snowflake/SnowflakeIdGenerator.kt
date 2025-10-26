package io.github.dornol.idkit.snowflake

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Snowflake ID Generator
 *
 * 이 클래스는 Twitter Snowflake 알고리즘을 기반으로 고유 ID를 생성합니다.
 * 생성된 ID는 64비트 Long 형태이며, timestamp + dataCenterId + workerId + sequence 로 구성됩니다.
 *
 * @param workerId 노드(worker) ID, 0..31
 * @param dataCenterId 데이터센터 ID, 0..31
 * @param epochStart Snowflake epoch 시작 시점, 기본값 2025-01-01T00:00(Asia/Seoul)
 *
 * @see <a href="https://github.com/twitter/snowflake/tree/snowflake-2010">Twitter Snowflake</a>
 */
class SnowflakeIdGenerator(
    private val workerId: Long,
    private val dataCenterId: Long,
    epochStart: ZonedDateTime = LocalDateTime.of(2025, 1, 1, 0, 0)
        .atZone(ZoneId.of("Asia/Seoul")),
) {

    companion object {
        /** Data center ID 비트 수 */
        const val DATA_CENTER_ID_BITS: Int = 5

        /** Worker ID 비트 수 */
        const val WORKER_ID_BITS: Int = 5

        /** Sequence 비트 수 */
        const val SEQUENCE_BITS: Int = 12

        /** 최대 Data center ID */
        const val MAX_DATA_CENTER_ID = (1L shl DATA_CENTER_ID_BITS) - 1

        /** 최대 Worker ID */
        const val MAX_WORKER_ID = (1L shl WORKER_ID_BITS) - 1

        /** 최대 Sequence 값 */
        const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1

        /** Timestamp를 왼쪽으로 shift 하는 비트 수 */
        const val TIMESTAMP_LEFT_SHIFT = DATA_CENTER_ID_BITS + WORKER_ID_BITS + SEQUENCE_BITS

        /** Data center ID를 왼쪽으로 shift 하는 비트 수 */
        const val DATA_CENTER_ID_LEFT_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS

        /** Worker ID를 왼쪽으로 shift 하는 비트 수 */
        const val WORKER_ID_LEFT_SHIFT = SEQUENCE_BITS
    }

    /** Epoch 시작 시점(밀리초 단위) */
    private val epochStartMillis: Long = epochStart.toInstant().toEpochMilli()

    /** Sequence counter */
    private var sequenceCounter = 0L

    /** 마지막 생성 timestamp */
    private var lastGeneratedTimestamp = -1L

    init {
        require(workerId in 0..MAX_WORKER_ID) { "Worker Id must be between 0 and $MAX_WORKER_ID" }
        require(dataCenterId in 0..MAX_DATA_CENTER_ID) { "Data center Id must be between 0 and $MAX_DATA_CENTER_ID" }
    }

    /**
     * 다음 고유 ID 생성
     *
     * Snowflake 알고리즘에 따라 timestamp, dataCenterId, workerId, sequence 조합으로 ID 생성
     *
     * @return 생성된 고유 ID (Long)
     */
    @Synchronized
    fun nextId(): Long {
        var timestamp = currentTimestamp()

        if (timestamp < lastGeneratedTimestamp) {
            timestamp = lastGeneratedTimestamp
        }

        if (timestamp == lastGeneratedTimestamp) {
            sequenceCounter = (sequenceCounter + 1) and MAX_SEQUENCE
            if (sequenceCounter == 0L) {
                timestamp = waitNextMillis(lastGeneratedTimestamp)
            }
        } else {
            sequenceCounter = 0L
        }

        lastGeneratedTimestamp = timestamp

        return ((timestamp - epochStartMillis) shl TIMESTAMP_LEFT_SHIFT) or
                (dataCenterId shl DATA_CENTER_ID_LEFT_SHIFT) or
                (workerId shl WORKER_ID_LEFT_SHIFT) or
                sequenceCounter
    }

    /** 현재 timestamp 밀리초 단위로 반환 */
    private fun currentTimestamp(): Long = System.currentTimeMillis()

    /**
     * 마지막 timestamp 이후로 이동
     *
     * 같은 millisecond에서 sequence overflow 시 다음 millisecond까지 대기
     */
    private fun waitNextMillis(lastTs: Long): Long {
        var ts = currentTimestamp()
        while (ts <= lastTs) {
            ts = currentTimestamp()
        }
        return ts
    }

}