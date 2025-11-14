package io.github.dornol.idkit.flake

import java.time.Instant

/**
 * Snowflake ID Generator
 *
 * 이 클래스는 Twitter Snowflake 알고리즘을 기반으로 고유 ID를 생성합니다.
 * 생성된 ID는 64비트 Long 형태이며, timestamp + dataCenterId + workerId + sequence 로 구성됩니다.
 *
 * @param workerId 노드(worker) ID, 0..31
 * @param dataCenterId 데이터센터 ID, 0..31
 * @param epochStart Snowflake epoch 시작 시점, 기본값 1970-01-01T00:00:00Z
 *
 * @see <a href="https://github.com/twitter/snowflake/tree/snowflake-2010">Twitter Snowflake</a>
 */
class SnowflakeIdGenerator(
    workerId: Long,
    dataCenterId: Long,
    epochStart: Instant = Instant.EPOCH,
) : FlakeIdGenerator(
    timestampBits = 41,
    datacenterIdBits = 5,
    workerIdBits = 5,
    timestampDivisor = 1L,
    workerId = workerId,
    datacenterId = dataCenterId,
    epochStart = epochStart
)