package com.reef.platform.infrastructure.persistence

import com.reef.platform.infrastructure.config.RuntimeEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

object RuntimeDataSources {
    private val pools = ConcurrentHashMap<String, HikariDataSource>()

    fun dataSource(jdbcUrl: String, user: String, password: String): DataSource {
        val key = "$jdbcUrl|$user"
        return pools.computeIfAbsent(key) {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                maximumPoolSize = RuntimeEnv.int("RUNTIME_DB_POOL_MAX", 24)
                minimumIdle = RuntimeEnv.int("RUNTIME_DB_POOL_MIN_IDLE", 4)
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
}

data class RuntimeDataSourceSnapshot(
    val key: String,
    val jdbcUrl: String,
    val username: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val threadsAwaitingConnection: Int
)
