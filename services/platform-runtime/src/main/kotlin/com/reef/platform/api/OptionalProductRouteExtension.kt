package com.reef.platform.api

import java.util.ServiceLoader

/** Product-owned routes that can be composed into, but are not owned by, Reef. */
interface OptionalProductRouteExtension {
    val internalPaths: List<String>
    val publicReadPaths: List<String>

    fun handleInternal(method: String, path: String, query: String?, body: String): PlatformHotPathResponse?

    fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse?
}

/** Supplies product-owned routes without adding a product implementation to Reef's artifact. */
fun interface OptionalProductRouteExtensionProvider {
    fun extensions(): List<OptionalProductRouteExtension>
}

fun loadOptionalProductRouteExtensions(): List<OptionalProductRouteExtension> {
    return ServiceLoader.load(OptionalProductRouteExtensionProvider::class.java)
        .flatMap { it.extensions() }
}
