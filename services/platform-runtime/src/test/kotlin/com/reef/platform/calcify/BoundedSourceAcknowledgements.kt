package com.reef.platform.calcify

import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Test producer only: FIFO metadata publication after actual durable acknowledgements. */
internal class BoundedSourceAcknowledgements<T, A>(
    private val capacity: Int,
    private val acknowledged: (T, A) -> Unit
) {
    private val pending = ArrayDeque<Pair<T, Future<A>>>()
    var peakPending = 0
        private set

    init { require(capacity in 1..16) { "source in-flight bound must be between1 and16" } }

    fun add(value: T, future: Future<A>) {
        pending.addLast(value to future)
        peakPending = maxOf(peakPending, pending.size)
        if (pending.size >= capacity) drainFirst()
    }

    fun drain() { while (pending.isNotEmpty()) drainFirst() }

    private fun drainFirst() {
        val (value, future) = pending.removeFirst()
        val metadata = future.get(20, TimeUnit.SECONDS)
        acknowledged(value, metadata)
    }
}
