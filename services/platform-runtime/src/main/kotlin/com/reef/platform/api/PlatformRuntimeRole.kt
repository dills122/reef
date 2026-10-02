package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv

enum class PlatformRuntimeRole(
    val configValue: String,
    val publicHttpEnabled: Boolean,
    val backgroundWorkersEnabled: Boolean
) {
    Api("api", publicHttpEnabled = true, backgroundWorkersEnabled = false),
    Worker("worker", publicHttpEnabled = false, backgroundWorkersEnabled = true),
    Projector("projector", publicHttpEnabled = false, backgroundWorkersEnabled = true),
    Materializer("materializer", publicHttpEnabled = false, backgroundWorkersEnabled = true);

    companion object {
        fun from(raw: String): PlatformRuntimeRole {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.configValue == normalized }
                ?: throw IllegalArgumentException("Unsupported PLATFORM_RUNTIME_ROLE: $raw")
        }

        fun fromEnv(): PlatformRuntimeRole {
            return from(RuntimeEnv.string("PLATFORM_RUNTIME_ROLE", "api"))
        }
    }
}
