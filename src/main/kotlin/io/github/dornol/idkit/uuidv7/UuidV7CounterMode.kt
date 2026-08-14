package io.github.dornol.idkit.uuidv7

/** Controls how the UUIDv7 rand_a field is populated. */
enum class UuidV7CounterMode {
    /** Preserve strict per-generator ordering within a timestamp tick. */
    MONOTONIC,

    /** Use fresh random rand_a bits; ordering within one millisecond is not guaranteed. */
    RANDOM,
}
