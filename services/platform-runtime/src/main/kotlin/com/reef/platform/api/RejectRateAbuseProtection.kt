package com.reef.platform.api

import java.time.Instant

interface RateLimitStore {
    fun increment(clientId: String, route: String, windowStartEpochSeconds: Long, windowSeconds: Long): Long
}

class InMemoryRateLimitStore(
    private val retainedWindows: Long = 2
) : RateLimitStore {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun increment(clientId: String, route: String, windowStartEpochSeconds: Long, windowSeconds: Long): Long {
        cleanupBefore(windowStartEpochSeconds - (windowSeconds * (retainedWindows - 1).coerceAtLeast(0)))
        val key = "$clientId|$route|$windowStartEpochSeconds"
        return counts.merge(key, 1L) { current, one -> current + one } ?: 1L
    }

    internal fun entryCount(): Int = counts.size

    private fun cleanupBefore(minWindowStartEpochSeconds: Long) {
        counts.keys.removeIf { key ->
            val windowStart = key.substringAfterLast('|').toLongOrNull() ?: return@removeIf true
            windowStart < minWindowStartEpochSeconds
        }
    }
}

data class RejectRatePolicy(
    val maxRejects: Int,
    val windowSeconds: Long,
    val blockSeconds: Long
)

class RejectRateAbuseProtectionHook(
    private val maxRejects: Int,
    private val windowSeconds: Long,
    private val blockSeconds: Long,
    private val trackedRejectCodes: Set<String>,
    private val trackedRoutes: Set<String>,
    private val routePolicies: Map<String, RejectRatePolicy> = emptyMap(),
    private val warningOnly: Boolean = false,
    private val clock: () -> Instant = { Instant.now() }
) : AbuseProtectionHook {
    private data class RejectState(
        val route: String,
        val windowStartEpochSeconds: Long,
        val rejectCount: Int,
        val blockedUntilEpochSeconds: Long,
        val warnedInWindow: Boolean
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, RejectState>()
    private val tripCount = java.util.concurrent.atomic.AtomicLong(0)
    private val blockedCount = java.util.concurrent.atomic.AtomicLong(0)
    private val releaseCount = java.util.concurrent.atomic.AtomicLong(0)

    override fun allow(clientId: String, route: String): BoundaryError? {
        if (!isConfigured()) return null
        if (!tracksRoute(route)) return null
        val now = clock().epochSecond
        val key = key(clientId, route)
        val policy = policyFor(route) ?: return null
        val state = states.computeIfPresent(key) { _, current ->
            if (current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds) {
                releaseCount.incrementAndGet()
                val currentWindowStart = currentWindowStart(now, policy)
                if (current.windowStartEpochSeconds == currentWindowStart) {
                    current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                } else {
                    null
                }
            } else if (current.blockedUntilEpochSeconds == 0L && stateWindowExpired(current, policy, now)) {
                null
            } else {
                current
            }
        }
        if (state != null && state.blockedUntilEpochSeconds > now) {
            blockedCount.incrementAndGet()
            val secondsRemaining = state.blockedUntilEpochSeconds - now
            return BoundaryError(429, "ABUSE_BLOCKED", "client temporarily blocked for abusive traffic ($secondsRemaining seconds remaining)")
        }
        return null
    }

    override fun observe(clientId: String, route: String, responseStatus: Int, rejectCode: String?) {
        if (!tracksRoute(route)) return
        val policy = policyFor(route) ?: return
        if (responseStatus < 200 || responseStatus >= 300) return
        val normalizedCode = rejectCode?.uppercase()?.trim().orEmpty()
        if (normalizedCode.isBlank() || !trackedRejectCodes.contains(normalizedCode)) return

        val now = clock().epochSecond
        val currentWindowStart = currentWindowStart(now, policy)
        val key = key(clientId, route)
        states.compute(key) { _, current ->
            val base = when {
                current == null -> RejectState(route, currentWindowStart, 0, 0, false)
                current.windowStartEpochSeconds != currentWindowStart -> RejectState(route, currentWindowStart, 0, 0, false)
                current.blockedUntilEpochSeconds > 0 && now >= current.blockedUntilEpochSeconds -> current.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                else -> current
            }
            val updatedCount = base.rejectCount + 1
            if (updatedCount <= policy.maxRejects) {
                return@compute base.copy(rejectCount = updatedCount)
            }

            if (warningOnly) {
                if (!base.warnedInWindow) {
                    System.err.println(
                        "abuse_breaker_warning mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount windowSeconds=${policy.windowSeconds}"
                    )
                }
                return@compute base.copy(rejectCount = updatedCount, warnedInWindow = true)
            }

            if (base.blockedUntilEpochSeconds > now) {
                return@compute base
            }

            val blockedUntil = now + policy.blockSeconds
            val trips = tripCount.incrementAndGet()
            System.err.println(
                "abuse_breaker_trip mode=reject-rate clientId=$clientId route=$route rejectCode=$normalizedCode rejects=$updatedCount maxRejects=${policy.maxRejects} windowSeconds=${policy.windowSeconds} blockSeconds=${policy.blockSeconds} trips=$trips"
            )
            base.copy(rejectCount = updatedCount, blockedUntilEpochSeconds = blockedUntil, warnedInWindow = false)
        }
    }

    override fun stats(): AbuseProtectionStats {
        val now = clock().epochSecond
        cleanupExpiredStates(now)
        val activeBlockedClients = states.values.count { it.blockedUntilEpochSeconds > now }
        return AbuseProtectionStats(
            mode = "reject-rate",
            enabled = isConfigured(),
            warningOnly = warningOnly,
            maxRejects = maxRejects,
            windowSeconds = windowSeconds,
            blockSeconds = blockSeconds,
            trackedRejectCodes = trackedRejectCodes,
            trackedRoutes = trackedRoutes,
            routePolicyOverrides = routePolicies.mapValues { (_, policy) ->
                "${policy.maxRejects}/${policy.windowSeconds}/${policy.blockSeconds}"
            },
            trips = tripCount.get(),
            blocks = blockedCount.get(),
            releases = releaseCount.get(),
            activeBlockedClients = activeBlockedClients
        )
    }

    private fun isConfigured(): Boolean {
        return maxRejects > 0 && windowSeconds > 0 && blockSeconds > 0 && trackedRejectCodes.isNotEmpty()
    }

    private fun policyFor(route: String): RejectRatePolicy? {
        val defaultPolicy = if (isConfigured()) {
            RejectRatePolicy(maxRejects, windowSeconds, blockSeconds)
        } else {
            null
        }
        return routePolicies[route] ?: defaultPolicy
    }

    private fun tracksRoute(route: String): Boolean {
        if (trackedRoutes.isEmpty()) return true
        return trackedRoutes.contains(route)
    }

    internal fun trackedStateCount(): Int = states.size

    private fun cleanupExpiredStates(now: Long) {
        for (key in states.keys) {
            states.computeIfPresent(key) { _, state ->
                val policy = policyFor(state.route) ?: return@computeIfPresent null
                if (state.blockedUntilEpochSeconds > 0 && now >= state.blockedUntilEpochSeconds) {
                    releaseCount.incrementAndGet()
                    return@computeIfPresent if (stateWindowExpired(state, policy, now)) {
                        null
                    } else {
                        state.copy(blockedUntilEpochSeconds = 0, warnedInWindow = false)
                    }
                }
                if (state.blockedUntilEpochSeconds == 0L && stateWindowExpired(state, policy, now)) {
                    null
                } else {
                    state
                }
            }
        }
    }

    private fun stateWindowExpired(state: RejectState, policy: RejectRatePolicy, now: Long): Boolean {
        return now >= state.windowStartEpochSeconds + policy.windowSeconds
    }

    private fun currentWindowStart(now: Long, policy: RejectRatePolicy): Long {
        return now / policy.windowSeconds * policy.windowSeconds
    }

    private fun key(clientId: String, route: String): String {
        return "$clientId|$route"
    }
}
