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

    fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
