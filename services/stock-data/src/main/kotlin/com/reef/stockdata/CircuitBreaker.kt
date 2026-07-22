package com.reef.stockdata

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

class CircuitOpenException(message: String) : Exception(message)

/**
 * Per-provider circuit breaker. Opens after [failureThreshold] consecutive
 * failures, short-circuits calls for [cooldown], then allows one trial call.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val cooldown: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = Instant::now,
) {
    private val consecutiveFailures = AtomicInteger(0)
    private val state = AtomicReference(CircuitState.CLOSED)
    private val openedAt = AtomicReference<Instant?>(null)

    fun <T> call(action: () -> T): T {
        when (state.get()) {
            CircuitState.OPEN -> {
                val opened = openedAt.get()
                val cooldownElapsed = opened != null && Duration.between(opened, clock()).compareTo(cooldown) >= 0
                if (!cooldownElapsed || !state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    throw CircuitOpenException("circuit open, cooldown until ${opened?.plus(cooldown)}")
                }
            }
            CircuitState.HALF_OPEN -> {
                throw CircuitOpenException("circuit half-open, trial call in progress")
            }
            CircuitState.CLOSED -> {}
        }

        return try {
            val result = action()
            onSuccess()
            result
        } catch (ex: Exception) {
            onFailure()
            throw ex
        }
    }

    private fun onSuccess() {
        consecutiveFailures.set(0)
        state.set(CircuitState.CLOSED)
        openedAt.set(null)
    }

    private fun onFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= failureThreshold) {
            state.set(CircuitState.OPEN)
            openedAt.set(clock())
        }
    }

    fun currentState(): CircuitState = state.get()
}
