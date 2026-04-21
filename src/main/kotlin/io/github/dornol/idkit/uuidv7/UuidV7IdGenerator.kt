package io.github.dornol.idkit.uuidv7

import io.github.dornol.idkit.IdGenerator
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * UUID v7 생성기 (RFC 9562 §6.2 **Method 2 – Fixed-Length Dedicated Counter Bits** 준수).
 *
 *  - UUID v7 은 48bit Unix epoch milliseconds 를 상위에 배치하고, version/variant 표식과
 *    랜덤 값을 결합한 시간순 정렬(sortable) 포맷이다.
 *  - 본 구현은 **동일 밀리초 내 단조 증가(monotonicity)** 를 보장하기 위해 상위 12bit 랜덤 영역
 *    (`rand_a`) 을 전용 카운터로 사용한다.
 *  - 타임스탬프(52bit) 와 카운터(12bit) 를 단일 [AtomicLong] 에 패킹하여 CAS 로 원자적으로 갱신하므로,
 *    멀티스레드 환경에서도 모든 UUID 가 `mostSignificantBits` 기준 **엄격 증가**를 유지한다.
 *  - 동일 ms 내에서 카운터가 12bit (4096) 를 넘어가면 타임스탬프를 1ms 앞당기고 카운터를 0 으로
 *    재설정한다 (RFC 9562 의 "borrow from the clock" 방식). 실제 시계가 따라잡으면 자연스럽게
 *    정상 값으로 복귀한다.
 *  - **드리프트 상한 없음**: 단일 generator 에서 지속적으로 > 4,096/ms (약 4M/sec) 속도로 호출하면
 *    borrow 가 누적되어 타임스탬프가 실제 wall clock 보다 앞서게 된다. 대부분의 운영 환경에서는
 *    문제되지 않지만, 극단적 부하에서는 외부 관찰자에게 "미래 시각의 UUID" 가 관측될 수 있다.
 *
 * ### 비트 레이아웃
 *  - `mostSigBits` (상위 64bit)
 *    - 0..47  : Unix epoch milliseconds (48bit)
 *    - 48..51 : version = 0b0111 (7)
 *    - 52..63 : monotonic counter (12bit, `rand_a` 위치)
 *  - `leastSigBits` (하위 64bit)
 *    - 62..63 : variant = 0b10 (IETF RFC 4122)
 *    - 0..61  : 랜덤 62bit (`rand_b`)
 *
 * ### 이전 버전 대비 변경점
 *  - `rand_a` 12bit 이 완전 랜덤 → **monotonic counter** 로 의미 변경.
 *  - 동일 ms 내 생성된 두 UUID 간 `mostSignificantBits` 비교가 **단조 증가** 를 보장.
 *  - 충돌 확률은 기존과 동일 수준 유지 (`rand_b` 62bit 무작위성 보존).
 */
class UuidV7IdGenerator : IdGenerator<UUID> {

    /**
     * `(timestamp:52bit | counter:12bit)` 로 패킹된 상태.
     *
     * 단일 [AtomicLong] 에 CAS 로 갱신하므로 타임스탬프와 카운터가 원자적으로 이동한다.
     */
    private val state = AtomicLong(0L)

    /**
     * 새 UUID v7 값을 생성한다.
     *
     * 1. 시계 관찰값 `now` 와 저장된 `(ts, counter)` 를 비교해 새 상태를 계산 → CAS 로 커밋.
     *    - `now > ts`           : 새 ms 슬라이스. `(now, 0)`.
     *    - `now <= ts, counter < MAX` : 동일/역행 ms. `(ts, counter + 1)`.
     *    - `now <= ts, counter == MAX` : 오버플로우. `(ts + 1, 0)` 로 시계 빌림.
     * 2. 상위 48bit 에 timestamp, 48..51 에 version(7), 52..63 에 counter 를 배치.
     * 3. `leastSigBits` 는 variant(0b10) 2bit + 랜덤 62bit 로 채운다.
     */
    override fun nextId(): UUID {
        val packed = nextState()
        val timestamp = packed ushr COUNTER_BITS
        val counter = packed and COUNTER_MASK

        // 48bit 범위 초과 시 (서기 ~10889년) 상위 비트 절단하여 레이아웃을 지킨다.
        val timePart = (timestamp and TIMESTAMP_MASK) shl 16
        val versionPart = 0x7L shl 12
        val mostSigBits = timePart or versionPart or counter

        // variant 2bit 을 덮어쓰므로 rand_b 는 하위 62bit 만 채우면 된다.
        // `nextLong() and RAND_B_MASK` 가 `nextLong(0, 1L shl 62)` 보다 저렴 (bounded rejection 없음).
        val variant = 0x2L shl 62
        val lowRand = ThreadLocalRandom.current().nextLong() and RAND_B_MASK
        val leastSigBits = variant or lowRand

        return UUID(mostSigBits, leastSigBits)
    }

    /**
     * `(timestamp | counter)` 를 원자적으로 갱신하고 새 패킹 값을 반환한다.
     *
     * 경쟁 스레드가 동시에 CAS 를 시도하면 실패한 쪽이 재시도하며, 성공한 순서대로 카운터가 증가한다.
     */
    private fun nextState(): Long {
        while (true) {
            val prev = state.get()
            val prevTs = prev ushr COUNTER_BITS
            val prevCounter = prev and COUNTER_MASK
            val now = System.currentTimeMillis()

            val newTs: Long
            val newCounter: Long
            if (now > prevTs) {
                newTs = now
                newCounter = 0L
            } else if (prevCounter == COUNTER_MASK) {
                // 동일 ms 에서 12bit 카운터 소진 → 1ms 앞으로 빌림
                newTs = prevTs + 1
                newCounter = 0L
            } else {
                // 동일 ms 또는 시계 역행 → prev ts 유지, 카운터만 증가
                newTs = prevTs
                newCounter = prevCounter + 1
            }

            val newState = (newTs shl COUNTER_BITS) or newCounter
            if (state.compareAndSet(prev, newState)) {
                return newState
            }
        }
    }

    private companion object {
        private const val COUNTER_BITS = 12
        private const val COUNTER_MASK = 0xFFFL                 // (1L shl 12) - 1
        private const val TIMESTAMP_MASK = 0x0000FFFFFFFFFFFFL  // 하위 48bit
        private const val RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL     // 하위 62bit (variant 2bit 제외)
    }
}
