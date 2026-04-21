package io.github.dornol.idkit.flake

/**
 * 시스템 시계가 역행하여 [FlakeIdGenerator.nextId] 가 안전하게 ID 를 생성할 수 없을 때 발생한다.
 *
 * 발생 원인 예시:
 *  - NTP 대규모 동기화로 시계가 과거로 이동
 *  - 운영 중 수동 시간 변경
 *  - 가상화 환경의 clock drift 보정
 *
 * 처리 전략:
 *  - 호출자는 catch 후 짧은 지연 뒤 재시도하거나 운영 경보로 다룬다.
 *  - 예외가 던져진 시점에 generator 내부 상태는 변경되지 않으므로 같은 인스턴스를 계속 사용할 수 있다.
 *
 * [IllegalStateException] 을 상속하므로 기존에 `IllegalStateException` 을 포괄적으로 catch 하는
 * 호출부와도 호환된다.
 *
 * @property driftAmount 감지된 역행 폭 ([FlakeIdGenerator.timestampDivisor] 단위).
 * @property timestampDivisor 생성기에 설정된 divisor (단위 해석용).
 */
class ClockMovedBackwardsException(
    val driftAmount: Long,
    val timestampDivisor: Long,
) : IllegalStateException(
    "Clock moved backwards by $driftAmount timestamp-unit(s) (divisor=$timestampDivisor). Refusing to generate id."
)
