package com.reef.platform.api

import com.reef.platform.infrastructure.config.RuntimeEnv

enum class PlatformRuntimeRole(
    val configValue: String,
    val publicHttpEnabled: Boolean,
    val commandWorkersEnabled: Boolean
) {
    Api("api", publicHttpEnabled = true, commandWorkersEnabled = false),
    Worker("worker", publicHttpEnabled = false, commandWorkersEnabled = true),
    Projector("projector", publicHttpEnabled = false, commandWorkersEnabled = true),
    Materializer("materializer", publicHttpEnabled = false, commandWorkersEnabled = true),
    PostMatch("postmatch", publicHttpEnabled = false, commandWorkersEnabled = false);

    val postMatchWorkersEnabled: Boolean get() = this == PostMatch

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
