package com.reef.arena.controlplane.api

import com.reef.arena.controlplane.application.ArenaAdminApplicationService
import com.reef.arena.controlplane.arena.PostgresArenaBotEntitlementStore
import com.reef.arena.controlplane.arena.PostgresArenaBotRegistryStore
import com.reef.arena.controlplane.arena.PostgresArenaSubmissionAdmissionStore
import com.reef.platform.application.admin.AdminIdentityService
import com.reef.platform.application.admin.PostgresAdminIdentityStore
import com.reef.platform.api.OptionalProductRouteExtension
import com.reef.platform.api.OptionalProductRouteExtensionProvider
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.RuntimeDataSources

/** Arena-owned composition entry point discovered by the Reef runtime. */
class ArenaRouteExtensionProvider : OptionalProductRouteExtensionProvider {
    override fun extensions(): List<OptionalProductRouteExtension> {
        if (!RuntimeEnv.bool("PLATFORM_ARENA_ADMIN_ENABLED", false)) return emptyList()
        val jdbcUrl = RuntimeEnv.string("ARENA_POSTGRES_JDBC_URL", "")
            .ifBlank { error("ARENA_POSTGRES_JDBC_URL is required when PLATFORM_ARENA_ADMIN_ENABLED=true") }
        val store = PostgresArenaBotRegistryStore(
            RuntimeDataSources.dataSource(
                jdbcUrl,
                RuntimeEnv.string("ARENA_POSTGRES_USER", "reef"),
                RuntimeEnv.string("ARENA_POSTGRES_PASSWORD", "reef"),
                "arena-control-plane"
            )
        )
        val adminJdbcUrl = RuntimeEnv.string(
            "ADMIN_POSTGRES_JDBC_URL",
            RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
        ).ifBlank { error("ADMIN_POSTGRES_JDBC_URL or RUNTIME_POSTGRES_JDBC_URL is required when Arena is enabled") }
        val adminIdentityService = AdminIdentityService(
            PostgresAdminIdentityStore(
                RuntimeDataSources.dataSource(
                    adminJdbcUrl,
                    RuntimeEnv.string("ADMIN_POSTGRES_USER", RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")),
                    RuntimeEnv.string("ADMIN_POSTGRES_PASSWORD", RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")),
                    "arena-admin-identity"
                )
            )
        )
        val entitlementStore = PostgresArenaBotEntitlementStore(
            RuntimeDataSources.dataSource(
                jdbcUrl,
                RuntimeEnv.string("ARENA_POSTGRES_USER", "reef"),
                RuntimeEnv.string("ARENA_POSTGRES_PASSWORD", "reef"),
                "arena-entitlements"
            )
        )
        val submissionAdmissionStore = PostgresArenaSubmissionAdmissionStore(
            RuntimeDataSources.dataSource(
                jdbcUrl,
                RuntimeEnv.string("ARENA_POSTGRES_USER", "reef"),
                RuntimeEnv.string("ARENA_POSTGRES_PASSWORD", "reef"),
                "arena-submission-admissions"
            )
        )
        return listOf(
            ArenaAdminGateway(
                arenaAdminService = ArenaAdminApplicationService(
                    arenaRegistryStore = store,
                    adminIdentityService = adminIdentityService
                ),
                adminIdentityService = adminIdentityService,
                arenaBotEntitlementStore = entitlementStore,
                arenaSubmissionAdmissionStore = submissionAdmissionStore,
                analyticsRunExportService = null
            )
        )
    }
}
