package com.reef.platform.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.security.MessageDigest

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

    fun parseLegacyObjectOrEmpty(body: String): JsonDocument {
        return try {
            parseObject(body)
        } catch (_: IllegalArgumentException) {
            JsonDocument(JsonNodeFactory.instance.objectNode())
        }
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

    fun writeNode(node: JsonNode): String = mapper.writeValueAsString(node)

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

    fun strictTextField(key: String): String {
        val value = root.get(key)
        require(value != null && value.isTextual && value.textValue().isNotBlank()) {
            "missing or invalid text: " + key
        }
        return value.textValue()
    }

    fun strictIntField(key: String): Int {
        val value = root.get(key)
        require(value != null && value.isIntegralNumber && value.canConvertToInt()) {
            "missing or invalid integer: " + key
        }
        return value.intValue()
    }

    fun strictLongField(key: String): Long {
        val value = root.get(key)
        require(value != null && value.isIntegralNumber && value.canConvertToLong()) {
            "missing or invalid long: " + key
        }
        return value.longValue()
    }

    fun strictObject(key: String): JsonDocument {
        val value = root.get(key)
        require(value is ObjectNode) { "missing or invalid object: " + key }
        return JsonDocument(value)
    }

    fun strictObjectDocuments(key: String, required: Boolean = true): List<JsonDocument> {
        val value = root.get(key)
        if (value == null && !required) return emptyList()
        require(value is ArrayNode) { "missing or invalid array: " + key }
        require(value.all { it is ObjectNode }) { "non-object array element: " + key }
        return value.map { JsonDocument(it) }
    }

    fun raw(key: String): String {
        val value = root.get(key) ?: return ""
        return JsonCodec.writeNode(value)
    }

    fun semanticSha256(excludedRootFields: Set<String> = emptySet()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateCanonicalDigest(digest, root, excludedRootFields, HashMap(), isRoot = true)
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
}

// Fixed bounded table; changes allocation only, not canonical bytes or field order.
private val canonicalTokenPrefixes = "nbsdao".map { kind ->
    Array(256) { length -> "$kind$length:".toByteArray(Charsets.UTF_8) }
}
private val canonicalFieldOrder = Comparator<Map.Entry<String, JsonNode>> { left, right ->
    left.key.compareTo(right.key)
}

private fun updateCanonicalDigest(
    digest: MessageDigest,
    node: JsonNode,
    excludedRootFields: Set<String>,
    fieldTokens: MutableMap<String, ByteArray>,
    isRoot: Boolean
) {
    when (node.nodeType) {
        JsonNodeType.NULL -> updateCanonicalToken(digest, 'n', byteArrayOf())
        JsonNodeType.BOOLEAN -> updateCanonicalToken(digest, 'b', if (node.booleanValue()) byteArrayOf('1'.code.toByte()) else byteArrayOf('0'.code.toByte()))
        JsonNodeType.STRING -> updateCanonicalToken(digest, 's', node.textValue().toByteArray(Charsets.UTF_8))
        JsonNodeType.NUMBER -> updateCanonicalToken(digest, 'd', node.asText().toByteArray(Charsets.UTF_8))
        JsonNodeType.ARRAY -> {
            updateCanonicalToken(digest, 'a', node.size().toString().toByteArray(Charsets.UTF_8))
            node.forEach { child -> updateCanonicalDigest(digest, child, excludedRootFields, fieldTokens, isRoot = false) }
        }
        JsonNodeType.OBJECT -> {
            val fields = ArrayList<Map.Entry<String, JsonNode>>(node.size())
            node.fields().forEachRemaining { entry ->
                if (!isRoot || entry.key !in excludedRootFields) fields.add(entry)
            }
            fields.sortWith(canonicalFieldOrder)
            updateCanonicalToken(digest, 'o', fields.size.toString().toByteArray(Charsets.UTF_8))
            fields.forEach { (name, child) ->
                val cached = fieldTokens[name]
                if (cached != null) digest.update(cached) else {
                    val bytes = name.toByteArray(Charsets.UTF_8)
                    if (bytes.size <= 128 && fieldTokens.size < 128) {
                        val token = canonicalTokenPrefixes[2][bytes.size] + bytes
                        fieldTokens[name] = token
                        digest.update(token)
                    } else updateCanonicalToken(digest, 's', bytes)
                }
                updateCanonicalDigest(digest, child, excludedRootFields, fieldTokens, isRoot = false)
            }
        }
        else -> throw IllegalArgumentException("unsupported canonical checksum JSON node: ${node.nodeType}")
    }
}

private fun updateCanonicalToken(digest: MessageDigest, kind: Char, value: ByteArray) {
    val index = when (kind) { 'n' -> 0; 'b' -> 1; 's' -> 2; 'd' -> 3; 'a' -> 4; 'o' -> 5; else -> error("unknown canonical token") }
    val prefix = if (value.size < 256) canonicalTokenPrefixes[index][value.size]
        else "$kind${value.size}:".toByteArray(Charsets.UTF_8)
    digest.update(prefix)
    digest.update(value)
}
