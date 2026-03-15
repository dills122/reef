package com.reef.platform.api

object JsonFields {
    fun extract(body: String, key: String): String {
        val marker = "\"$key\":\""
        val start = body.indexOf(marker)
        if (start < 0) return ""
        val valueStart = start + marker.length
        val end = body.indexOf('"', valueStart)
        if (end < 0) return ""
        return body.substring(valueStart, end)
    }

    fun extractObjects(body: String, key: String): List<String> {
        val marker = "\"$key\":["
        val start = body.indexOf(marker)
        if (start < 0) return emptyList()

        val arrayStart = start + marker.length
        val arrayEnd = body.indexOf(']', arrayStart)
        if (arrayEnd < 0) return emptyList()

        val content = body.substring(arrayStart, arrayEnd)
        if (content.isBlank()) return emptyList()

        val matches = Regex("""\{[^{}]*\}""").findAll(content)
        return matches.map { it.value }.toList()
    }

    fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
