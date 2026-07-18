package com.reef.arena.controlplane.arena

import com.reef.platform.api.AccountRiskCheckExtension
import com.reef.platform.api.AccountRiskCheckExtensionProvider
import com.reef.platform.infrastructure.persistence.RuntimeDataSources

class ArenaAccountRiskExtensionProvider : AccountRiskCheckExtensionProvider {
    override fun extensions(lookup: (String) -> String?): List<AccountRiskCheckExtension> {
        if (lookup("EXTERNAL_API_ARENA_BOT_VERSION_RISK_ENABLED")?.trim()?.lowercase() !in setOf("true", "1", "yes", "on")) {
            return emptyList()
        }
        val jdbcUrl = lookup("ARENA_POSTGRES_JDBC_URL")
            ?.takeIf { it.isNotBlank() }
            ?: error("ARENA_POSTGRES_JDBC_URL is required when EXTERNAL_API_ARENA_BOT_VERSION_RISK_ENABLED=true")
        return listOf(
            ArenaBotVersionRiskCheck(
                PostgresArenaBotRegistryStore(
                    RuntimeDataSources.dataSource(
                        jdbcUrl,
                        lookup("ARENA_POSTGRES_USER") ?: "reef",
                        lookup("ARENA_POSTGRES_PASSWORD") ?: "reef",
                        "arena-bot-version-risk"
                    )
                )
            )
        )
    }
}
