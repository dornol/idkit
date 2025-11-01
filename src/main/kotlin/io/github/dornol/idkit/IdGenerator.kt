package io.github.dornol.idkit

interface IdGenerator<T> {

    fun nextId(): T

}