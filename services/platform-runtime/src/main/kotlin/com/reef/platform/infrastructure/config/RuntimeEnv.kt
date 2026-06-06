package com.reef.platform.infrastructure.config

object RuntimeEnv {
    fun string(key: String, fallback: String, lookup: (String) -> String? = systemLookup): String {
        return lookup(key)?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun int(key: String, fallback: Int, min: Int? = null, lookup: (String) -> String? = systemLookup): Int {
        val value = lookup(key)?.toIntOrNull() ?: fallback
        return if (min == null) value else value.coerceAtLeast(min)
    }

    fun long(key: String, fallback: Long, min: Long? = null, lookup: (String) -> String? = systemLookup): Long {
        val value = lookup(key)?.toLongOrNull() ?: fallback
        return if (min == null) value else value.coerceAtLeast(min)
    }

    fun bool(key: String, fallback: Boolean, lookup: (String) -> String? = systemLookup): Boolean {
        return when (lookup(key)?.trim()?.lowercase()) {
            null, "" -> fallback
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> fallback
        }
    }

    private val systemLookup: (String) -> String? = { key -> System.getenv(key) }
}
