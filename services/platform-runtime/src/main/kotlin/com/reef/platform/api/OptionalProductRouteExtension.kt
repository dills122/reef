package com.reef.platform.api

/** Product-owned routes that can be composed into, but are not owned by, Reef. */
internal interface OptionalProductRouteExtension {
    val internalPaths: List<String>
    val publicReadPaths: List<String>

    fun handleInternal(method: String, path: String, query: String?, body: String): PlatformHotPathResponse?

    fun handlePublicRead(path: String, query: String?): PlatformHotPathResponse?
}
