package com.reef.platform.api

enum class CommandProcessingMode(val configValue: String) {
    SyncResult("sync-result"),
    CapturedSyncEngine("captured-sync-engine"),
    CapturedAck("captured-ack");

    companion object {
        fun from(raw: String?): CommandProcessingMode {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return SyncResult
            return entries.firstOrNull { it.configValue == normalized }
                ?: throw IllegalArgumentException("Unsupported EXTERNAL_API_COMMAND_PROCESSING_MODE: $normalized")
        }

        fun fromEnv(lookup: (String) -> String? = { key -> System.getenv(key) }): CommandProcessingMode {
            return from(lookup("EXTERNAL_API_COMMAND_PROCESSING_MODE"))
        }
    }
}
