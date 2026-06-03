package com.reef.platform.api

object JsonFields {
    fun extract(body: String, key: String): String {
        return JsonCodec.fieldAsString(body, key)
    }

    fun extractObjects(body: String, key: String): List<String> {
        return JsonCodec.objectArrayElements(body, key)
    }

    fun escape(value: String): String {
        return JsonCodec.escapeString(value)
    }
}
