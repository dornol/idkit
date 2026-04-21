package io.github.dornol.idkit.testutil

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [generate] from [threads] threads, [perThread] times per thread, in parallel.
 *
 * All threads wait on a single gate before starting to maximize contention, then race to
 * insert the generated ids into a shared concurrent set. A duplicate or any exception in a
 * worker is captured in an [AtomicReference] and re-thrown from the caller's thread after the
 * workers have settled, so test failures surface cleanly instead of being swallowed by the
 * executor.
 *
 * @throws AssertionError if workers don't finish within 30 seconds or a duplicate is observed.
 */
internal fun <T : Any> collectConcurrently(
    threads: Int,
    perThread: Int,
    generate: () -> T,
): Set<T> {
    val pool = Executors.newFixedThreadPool(threads)
    val gate = CountDownLatch(1)
    val done = CountDownLatch(threads)
    val set = Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>(threads * perThread))
    val firstError = AtomicReference<Throwable?>(null)

    repeat(threads) {
        pool.submit {
            try {
                gate.await()
                repeat(perThread) {
                    val id = generate()
                    if (!set.add(id)) {
                        firstError.compareAndSet(null, AssertionError("Duplicate id detected: '$id'"))
                        return@submit
                    }
                }
            } catch (t: Throwable) {
                firstError.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }
    }

    gate.countDown()
    val finished = done.await(30, TimeUnit.SECONDS)
    pool.shutdown()
    if (!finished) throw AssertionError("concurrent workers did not finish within 30s")
    firstError.get()?.let { throw it }
    return set
}
