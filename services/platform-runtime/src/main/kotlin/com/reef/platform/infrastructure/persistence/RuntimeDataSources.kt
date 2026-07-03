package com.reef.platform.infrastructure.persistence

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

object RuntimeDataSources {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    fun dataSource(jdbcUrl: String, user: String, password: String, poolName: String = "default"): DataSource {
        val normalizedPoolName = poolName.ifBlank { "default" }
        val key = "$normalizedPoolName|$jdbcUrl|$user"
        return pools.computeIfAbsent(key) {
            val envSuffix = poolEnvSuffix(normalizedPoolName)
            val defaultMaximumPoolSize = RuntimeEnv.int("RUNTIME_DB_POOL_MAX", 24, min = 1)
            val defaultMinimumIdle = RuntimeEnv.int("RUNTIME_DB_POOL_MIN_IDLE", 4, min = 0)
            val roleMaximumPoolSize = defaultMaximumPoolSize(normalizedPoolName, defaultMaximumPoolSize)
            val roleMinimumIdle = defaultMinimumIdle(normalizedPoolName, defaultMinimumIdle, roleMaximumPoolSize)
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                setPoolName("reef-$normalizedPoolName")
                maximumPoolSize = RuntimeEnv.int("RUNTIME_DB_POOL_${envSuffix}_MAX", roleMaximumPoolSize, min = 1)
                minimumIdle = RuntimeEnv.int("RUNTIME_DB_POOL_${envSuffix}_MIN_IDLE", roleMinimumIdle, min = 0)
                    .coerceAtMost(maximumPoolSize)
                connectionTimeout = RuntimeEnv.long("RUNTIME_DB_POOL_CONN_TIMEOUT_MS", 2000L)
                idleTimeout = RuntimeEnv.long("RUNTIME_DB_POOL_IDLE_TIMEOUT_MS", 120000L)
                maxLifetime = RuntimeEnv.long("RUNTIME_DB_POOL_MAX_LIFETIME_MS", 600000L)
                isAutoCommit = true
            }
            HikariDataSource(config)
        }
    }

    fun snapshots(): List<RuntimeDataSourceSnapshot> {
        return pools.entries
            .map { (key, dataSource) ->
                val bean = dataSource.hikariPoolMXBean
                RuntimeDataSourceSnapshot(
                    key = key,
                    poolName = dataSource.poolName,
                    jdbcUrl = dataSource.jdbcUrl,
                    username = dataSource.username,
                    maximumPoolSize = dataSource.maximumPoolSize,
                    minimumIdle = dataSource.minimumIdle,
                    activeConnections = bean?.activeConnections ?: 0,
                    idleConnections = bean?.idleConnections ?: 0,
                    totalConnections = bean?.totalConnections ?: 0,
                    threadsAwaitingConnection = bean?.threadsAwaitingConnection ?: 0
                )
            }
            .sortedBy { it.key }
    }

    private fun poolEnvSuffix(poolName: String): String {
        return poolName
            .uppercase()
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
            .ifBlank { "DEFAULT" }
    }

    private fun defaultMaximumPoolSize(poolName: String, globalDefault: Int): Int {
        return when (poolName) {
            "command-log", "default" -> globalDefault
            "runtime", "async-runtime" -> globalDefault.coerceAtMost(16)
            "stream-intake" -> globalDefault.coerceAtLeast(32)
            "stream-runtime" -> globalDefault.coerceAtLeast(24)
            "command-capture", "idempotency" -> globalDefault.coerceAtMost(8)
            "admin-runtime" -> globalDefault.coerceAtMost(4)
            else -> globalDefault.coerceAtMost(8)
        }.coerceAtLeast(1)
    }

    private fun defaultMinimumIdle(poolName: String, globalDefault: Int, maximumPoolSize: Int): Int {
        val roleDefault = when (poolName) {
            "default" -> globalDefault
            "command-log" -> globalDefault.coerceAtMost(8)
            "runtime", "async-runtime" -> globalDefault.coerceAtMost(4)
            "stream-intake", "stream-runtime" -> globalDefault.coerceAtLeast(8)
            else -> globalDefault.coerceAtMost(2)
        }
        return roleDefault.coerceIn(0, maximumPoolSize)
    }
}

data class RuntimeDataSourceSnapshot(
    val key: String,
    val poolName: String,
    val jdbcUrl: String,
    val username: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val threadsAwaitingConnection: Int
)
