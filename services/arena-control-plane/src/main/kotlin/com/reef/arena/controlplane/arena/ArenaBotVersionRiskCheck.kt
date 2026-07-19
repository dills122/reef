package com.reef.arena.controlplane.arena

import com.reef.platform.api.AccountRiskCheckExtension
import com.reef.platform.api.AccountRiskCheckRequest
import com.reef.platform.api.AccountRiskCheckResult
import com.reef.platform.api.AccountRiskDecision
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ArenaBotVersionRiskCheck(
    private val store: ArenaBotRegistryStore,
    private val refreshExecutor: Executor = RefreshExecutor,
    private val nanoTime: () -> Long = System::nanoTime,
    private val cacheTtlNanos: Long = DEFAULT_CACHE_TTL_NANOS,
    private val failureBackoffNanos: Long = DEFAULT_FAILURE_BACKOFF_NANOS,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES
) : AccountRiskCheckExtension {
    init {
        require(cacheTtlNanos > 0) { "cacheTtlNanos must be positive" }
        require(failureBackoffNanos > 0) { "failureBackoffNanos must be positive" }
        require(maxCacheEntries > 0) { "maxCacheEntries must be positive" }
    }

    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult? {
        val botId = request.botId
        val botVersion = request.botVersion
        if (botId.isBlank() || botVersion.isBlank() || !isCacheable(botId, botVersion)) {
            return null
        }
        val key = BotVersionKey(botId, botVersion)
        val now = nanoTime()
        val cached = cache[key]
        if (cached == null || now - cached.refreshedAtNanos >= cacheTtlNanos) {
            requestRefresh(key, now)
        }
        return cache[key]?.version?.toRiskResult()
    }

    private fun requestRefresh(key: BotVersionKey, now: Long) {
        if (retryAfterNanos[key]?.let { now < it } == true || !refreshing.add(key)) {
            return
        }
        synchronized(cacheLock) {
            if (!cache.containsKey(key) && cache.size >= maxCacheEntries) {
                refreshing.remove(key)
                return
            }
        }
        try {
            refreshExecutor.execute {
                val refreshedAt = nanoTime()
                try {
                    val version = store.version(key.botId, key.versionId)
                    synchronized(cacheLock) {
                        cache[key] = CachedVersion(version, refreshedAt)
                    }
                    retryAfterNanos.remove(key)
                } catch (_: Exception) {
                    // Arena is optional: retry asynchronously and never make Reef ingress wait for it.
                    retryAfterNanos[key] = refreshedAt + failureBackoffNanos
                } finally {
                    refreshing.remove(key)
                }
            }
        } catch (_: Exception) {
            refreshing.remove(key)
            retryAfterNanos[key] = now + failureBackoffNanos
        }
    }

    private fun ArenaBotVersion.toRiskResult(): AccountRiskCheckResult? {
        if (status in RuntimeAllowedStatuses) return null
        return AccountRiskCheckResult(
            decision = AccountRiskDecision.DISABLED_BOT,
            message = "bot version is ${status.name}: $botId/$versionId"
        )
    }

    private fun isCacheable(botId: String, versionId: String): Boolean {
        return botId.length <= MAX_IDENTIFIER_LENGTH && versionId.length <= MAX_IDENTIFIER_LENGTH
    }

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 128
        const val DEFAULT_MAX_CACHE_ENTRIES = 10_000
        const val DEFAULT_CACHE_TTL_NANOS = 1_000_000_000L
        const val DEFAULT_FAILURE_BACKOFF_NANOS = 5_000_000_000L

        val RefreshExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arena-bot-version-risk-refresh").apply { isDaemon = true }
        }

        val RuntimeAllowedStatuses = setOf(
            ArenaBotVersionStatus.Approved,
            ArenaBotVersionStatus.Active
        )
    }

    private data class BotVersionKey(val botId: String, val versionId: String)
    private data class CachedVersion(val version: ArenaBotVersion?, val refreshedAtNanos: Long)

    private val cache = ConcurrentHashMap<BotVersionKey, CachedVersion>()
    private val retryAfterNanos = ConcurrentHashMap<BotVersionKey, Long>()
    private val refreshing = ConcurrentHashMap.newKeySet<BotVersionKey>()
    private val cacheLock = Any()
}
