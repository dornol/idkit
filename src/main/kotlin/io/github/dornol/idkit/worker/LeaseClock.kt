package io.github.dornol.idkit.worker

/** Time source used by lease implementations; production code should use [SystemLeaseClock]. */
fun interface LeaseClock {
    fun millis(): Long
}

object SystemLeaseClock : LeaseClock {
    override fun millis(): Long = System.currentTimeMillis()
}
