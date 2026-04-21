package io.github.dornol.idkit.flake

/**
 * Thrown from [FlakeIdGenerator.nextId] when the system clock has moved backwards and the
 * generator cannot safely produce an id without risking duplicates or a busy-spin.
 *
 * Common causes:
 *  - A large NTP correction shifting the clock into the past
 *  - Manual time changes at the OS level
 *  - Clock-drift correction on a virtualized host
 *
 * Handling strategies:
 *  - Catch, back off briefly, and retry; or surface as an operational alert.
 *  - The generator's internal state is not mutated before this exception is thrown, so the
 *    same instance can continue to be used once the wall clock recovers.
 *
 * Extends [IllegalStateException] so existing code that catches `IllegalStateException`
 * broadly remains compatible.
 *
 * @property driftAmount the observed backward drift, in units of [FlakeIdGenerator.timestampDivisor].
 * @property timestampDivisor the generator's divisor, provided for unit interpretation.
 *
 * @since 2.0.0
 */
class ClockMovedBackwardsException(
    val driftAmount: Long,
    val timestampDivisor: Long,
) : IllegalStateException(
    "Clock moved backwards by $driftAmount timestamp-unit(s) (divisor=$timestampDivisor). Refusing to generate id."
)
