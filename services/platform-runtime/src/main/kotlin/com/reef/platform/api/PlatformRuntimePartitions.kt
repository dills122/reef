package com.reef.platform.api

internal fun configuredRuntimePartitions(rawValue: String, partitionCount: Int): List<Int> {
    val raw = rawValue.trim()
    if (raw.equals("all", ignoreCase = true)) {
        return (0 until partitionCount).toList()
    }
    return raw.split(",")
        .mapNotNull { value -> value.trim().toIntOrNull() }
        .filter { it in 0 until partitionCount }
        .distinct()
        .sorted()
}
