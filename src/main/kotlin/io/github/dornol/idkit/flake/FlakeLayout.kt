package io.github.dornol.idkit.flake

/**
 * Validates the shared bit-layout contract used by both [FlakeIdGenerator] and
 * [FlakeIdParser]. Pulled into a single helper so the two classes cannot drift: the
 * generator's init block and the parser's init block must accept and reject exactly the
 * same layouts, otherwise the parser would silently refuse valid ids produced by a future
 * version of the generator (or vice versa).
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation.
 */
internal fun validateFlakeLayout(
    timestampBits: Int,
    datacenterIdBits: Int,
    workerIdBits: Int,
    timestampDivisor: Long,
) {
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
}

/** The most significant bit of the 64-bit id is reserved and unused. */
internal const val UNUSED_BITS: Int = 1
