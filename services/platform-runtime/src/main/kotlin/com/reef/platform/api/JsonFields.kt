package com.reef.platform.api

object JsonFields {
    fun extract(body: String, key: String): String {
        return JsonCodec.parseLegacyObjectOrEmpty(body).string(key)
    }

    fun extractObjects(body: String, key: String): List<String> {
        return JsonCodec.parseLegacyObjectOrEmpty(body).objectArray(key)
    }

    fun escape(value: String): String {
        return JsonCodec.escapeString(value)
    }
}
