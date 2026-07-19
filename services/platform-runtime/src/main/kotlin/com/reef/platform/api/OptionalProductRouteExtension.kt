package com.reef.platform.api

import java.util.ServiceLoader
import com.reef.platform.application.admin.AdminServiceTokenFamily

data class OptionalProductAdminRoute(
    val externalPath: String,
    val methods: Set<String>,
    val internalPath: String,
    val fallbackTokenEnv: String,
    val fallbackActorEnv: String,
    val serviceTokenFamilies: Set<AdminServiceTokenFamily>,
    val sessionRoles: Set<String> = emptySet()
)

/** Product-owned routes that can be composed into, but are not owned by, Reef. */
interface OptionalProductRouteExtension {
    val internalPaths: List<String>
    val publicReadPaths: List<String>
    val adminRoutes: List<OptionalProductAdminRoute>

    fun handleInternal(
        method: String,
        path: String,
        query: String?,
        body: String,
        principal: AdminRequestPrincipal
    ): PlatformHotPathResponse?

    fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse?
}

/** Supplies product-owned routes without adding a product implementation to Reef's artifact. */
fun interface OptionalProductRouteExtensionProvider {
    fun extensions(): List<OptionalProductRouteExtension>
}

fun loadOptionalProductRouteExtensions(): List<OptionalProductRouteExtension> {
    return validateOptionalProductRouteExtensions(
        ServiceLoader.load(OptionalProductRouteExtensionProvider::class.java)
        .flatMap { it.extensions() }
    )
}

/**
 * Fails startup deterministically when independently-owned products claim the
 * same route. Without this guard, dispatch order would choose one extension
 * silently (and JDK HttpServer rejects duplicate public contexts later).
 */
fun validateOptionalProductRouteExtensions(
    extensions: List<OptionalProductRouteExtension>
): List<OptionalProductRouteExtension> {
    fun requireUnique(kind: String, values: List<String>) {
        val duplicates = values.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        require(duplicates.isEmpty()) {
            "duplicate optional product $kind: ${duplicates.joinToString(", ")}"
        }
    }

    requireUnique("internal path", extensions.flatMap { it.internalPaths })
    requireUnique("public read path", extensions.flatMap { it.publicReadPaths })
    requireUnique(
        "admin route",
        extensions.flatMap { extension ->
            extension.adminRoutes.flatMap { route ->
                route.methods.map { method -> "$method ${route.externalPath}" }
            }
        }
    )
    return extensions
}
