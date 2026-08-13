package io.github.dornol.idkit

/** Stateless parser contract for an id type and its decoded component type. */
interface IdParser<in T, out C> {
    fun isValid(value: T): Boolean
    fun decompose(value: T): C
}
