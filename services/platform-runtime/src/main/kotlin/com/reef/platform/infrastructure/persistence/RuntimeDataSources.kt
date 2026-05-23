package com.reef.platform.infrastructure.persistence

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
                maximumPoolSize = System.getenv("RUNTIME_DB_POOL_MAX")?.toIntOrNull() ?: 24
                minimumIdle = System.getenv("RUNTIME_DB_POOL_MIN_IDLE")?.toIntOrNull() ?: 4
                connectionTimeout = System.getenv("RUNTIME_DB_POOL_CONN_TIMEOUT_MS")?.toLongOrNull() ?: 2000L
                idleTimeout = System.getenv("RUNTIME_DB_POOL_IDLE_TIMEOUT_MS")?.toLongOrNull() ?: 120000L
                maxLifetime = System.getenv("RUNTIME_DB_POOL_MAX_LIFETIME_MS")?.toLongOrNull() ?: 600000L
                isAutoCommit = true
            }
            HikariDataSource(config)
        }
    }
}
