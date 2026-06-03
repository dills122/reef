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
}
