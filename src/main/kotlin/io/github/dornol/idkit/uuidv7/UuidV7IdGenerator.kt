package io.github.dornol.idkit.uuidv7

import io.github.dornol.idkit.IdGenerator
import java.time.Instant
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * UUID v7 생성기.
 *
 * - UUID v7은 밀리초 단위의 Unix 시간(48비트)을 상위 비트에 배치하고,
 *   나머지 비트에 버전/변이(variant) 표식과 랜덤 값을 결합하는 시간순 정렬(UUID sortable) 포맷입니다.
 * - 이 구현은 [java.util.UUID]의 비트 레이아웃 규칙을 따르며, 멀티스레드 환경에서 시간 역행을 방지하기 위해
 *   마지막으로 사용한 타임스탬프를 CAS로 관리합니다.
 *
 * 참고: 사양 초안(draft-ietf-uuidrev-rfc4122bis 및 관련 v7 설명)에 맞춰 다음과 같이 비트를 구성합니다.
 * - mostSigBits(상위 64비트)
 *   - 0..47  : Unix epoch milliseconds (48비트)
 *   - 48..51 : version(4비트) = 0b0111 (7)
 *   - 52..63 : 랜덤 12비트
 * - leastSigBits(하위 64비트)
 *   - 62..63 : variant(2비트) = 0b10 (IETF RFC 4122 variant)
 *   - 0..61  : 랜덤 62비트
 */
class UuidV7IdGenerator : IdGenerator<UUID> {

    /**
     * 마지막으로 사용된 타임스탬프(ms).
     *
     * - 멀티스레드 환경에서 같은 밀리초 안에 여러 스레드가 동시에 호출해도
     *   시간값이 역행하지 않도록 CAS(compare-and-set)로 업데이트합니다.
     */
    private val lastTimestamp = AtomicLong(0L)

    /**
     * 새 UUID v7 값을 생성합니다.
     *
     * 절차 요약:
     * 1) 단조 증가(동순 이상)하는 타임스탬프(ms)를 확보합니다.
     * 2) 상위 48비트에 시간을 채우고 그 다음 4비트에 버전 7을 표기합니다.
     * 3) 상위부의 남은 12비트와 하위부 62비트는 ThreadLocalRandom으로 채웁니다.
     * 4) 하위부의 최상위 2비트는 variant(0b10)로 설정합니다.
     */
    override fun nextId(): UUID {
        val timestamp = getTimestamp()

        // 상위 48비트: timestamp (ms)
        // 0x0000FFFFFFFFFFFFL 마스크로 하위 48비트만 유지한 뒤 16비트 왼쪽으로 이동하여
        // 상위부 비트 0..47 위치로 올립니다.
        val timePart = (timestamp and 0x0000FFFFFFFFFFFFL) shl 16 // 48비트 << 16

        // version 7(0b0111)을 비트 48..51 위치에 둡니다.
        val versionPart = (0x7L shl 12)

        // 상위부의 남은 12비트(비트 52..63)는 랜덤으로 채웁니다.
        val highRand = ThreadLocalRandom.current().nextLong(0, 1L shl 12) // 12비트 랜덤
        val mostSigBits = timePart or versionPart or highRand

        // variant는 하위부 최상위 2비트(비트 62..63)를 0b10로 설정합니다.
        val variant = 0x2L shl 62 // variant 10xx xxxx xxxx xxxx

        // 하위부의 나머지 62비트는 랜덤으로 채웁니다.
        val lowRand = ThreadLocalRandom.current().nextLong(0, 1L shl 62)
        val leastSigBits = variant or lowRand

        return UUID(mostSigBits, leastSigBits)
    }

    /**
     * 단조 증가(동순 이상)하는 타임스탬프(ms)를 반환합니다.
     *
     * - 시스템 시간이 역행하거나 같은 밀리초 안에서 경쟁이 있어도
     *   마지막에 사용한 값보다 작아지지 않도록 보장합니다.
     */
    private fun getTimestamp(): Long {
        while (true) {
            val now = Instant.now().toEpochMilli()
            val last = lastTimestamp.get()
            val timestamp = max(now, last)

            if (lastTimestamp.compareAndSet(last, timestamp)) {
                return timestamp
            }
            // CAS 실패 → 다른 스레드가 갱신했으므로 재시도
        }
    }
}