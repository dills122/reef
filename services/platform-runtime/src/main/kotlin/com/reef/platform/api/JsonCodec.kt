package com.reef.platform.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.json.JsonMapper

object JsonCodec {
    private val mapper = JsonMapper.builder().build()

    fun parseObject(body: String): JsonDocument {
        val root = try {
            mapper.readTree(body)
        } catch (ex: Exception) {
            throw IllegalArgumentException("invalid json payload", ex)
        }
        if (root == null || !root.isObject) {
            throw IllegalArgumentException("json payload must be an object")
        }
        return JsonDocument(root)
    }

    fun parseObjectOrEmpty(body: String): JsonDocument {
        return try {
            parseObject(body)
        } catch (_: Exception) {
            JsonDocument(JsonNodeFactory.instance.objectNode())
        }
    }

    fun fieldAsString(body: String, key: String): String {
        return parseObjectOrEmpty(body).string(key)
    }

    fun objectArrayElements(body: String, key: String): List<String> {
        return parseObjectOrEmpty(body).objectArray(key)
    }

    fun escapeString(value: String): String {
        val encoded = mapper.writeValueAsString(value)
        return encoded.substring(1, encoded.length - 1)
    }

    fun writeObject(vararg fields: Pair<String, Any?>): String {
        val node = JsonNodeFactory.instance.objectNode()
        fields.forEach { (key, value) -> node.set<JsonNode>(key, toNode(value)) }
        return mapper.writeValueAsString(node)
    }

    fun writeArray(values: Iterable<Any?>): String {
        val node = JsonNodeFactory.instance.arrayNode()
        values.forEach { value -> node.add(toNode(value)) }
        return mapper.writeValueAsString(node)
    }

    internal fun writeNode(node: JsonNode): String = mapper.writeValueAsString(node)

    fun rawJsonOrText(value: String): JsonNode {
        return try {
            mapper.readTree(value) ?: JsonNodeFactory.instance.textNode(value)
        } catch (_: Exception) {
            JsonNodeFactory.instance.textNode(value)
        }
    }

    private fun toNode(value: Any?): JsonNode {
        val factory = JsonNodeFactory.instance
        return when (value) {
            null -> factory.nullNode()
            is JsonNode -> value
            is String -> factory.textNode(value)
            is Boolean -> factory.booleanNode(value)
            is Int -> factory.numberNode(value)
            is Long -> factory.numberNode(value)
            is Double -> factory.numberNode(value)
            is Float -> factory.numberNode(value)
            is Iterable<*> -> {
                val array = factory.arrayNode()
                value.forEach { array.add(toNode(it)) }
                array
            }
            is Map<*, *> -> {
                val objectNode = factory.objectNode()
                value.forEach { (mapKey, mapValue) ->
                    objectNode.set<JsonNode>(mapKey.toString(), toNode(mapValue))
                }
                objectNode
            }
            else -> factory.textNode(value.toString())
        }
    }
}

class JsonDocument internal constructor(
    private val root: JsonNode
) {
    fun fieldNames(): Set<String> {
        return root.fieldNames().asSequence().toSet()
    }

    fun has(key: String): Boolean {
        return root.has(key)
    }

    fun string(key: String): String {
        val value = root.get(key) ?: return ""
        if (value.isNull) return ""
        return if (value.isTextual) value.textValue() else value.asText("")
    }

    fun obj(key: String): JsonDocument {
        val value = root.get(key)
        return if (value is ObjectNode) {
            JsonDocument(value)
        } else {
            JsonDocument(JsonNodeFactory.instance.objectNode())
        }
    }

    fun objectArray(key: String): List<String> {
        val value = root.get(key)
        if (value !is ArrayNode) return emptyList()
        return value.filterIsInstance<ObjectNode>().map { JsonCodec.writeNode(it) }
    }

    fun objectDocuments(key: String): List<JsonDocument> {
        val value = root.get(key)
        if (value !is ArrayNode) return emptyList()
        return value.filterIsInstance<ObjectNode>().map { JsonDocument(it) }
    }

    fun raw(key: String): String {
        val value = root.get(key) ?: return ""
        return JsonCodec.writeNode(value)
    }
}
