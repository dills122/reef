package com.reef.platform.api

// Thin facade kept for arena-control-plane, which consumes the platform-runtime jar.
object JsonFields {
    fun extract(body: String, key: String): String {
        return JsonCodec.parseLegacyObjectOrEmpty(body).string(key)
    }

    fun escape(value: String): String {
        return JsonCodec.escapeString(value)
    }
}
