package com.reef.platform.api

import com.fasterxml.jackson.databind.JsonNode
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

    fun raw(key: String): String {
        val value = root.get(key) ?: return ""
        return JsonCodec.writeNode(value)
    }

    fun semanticSha256(excludedRootFields: Set<String> = emptySet()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateCanonicalDigest(digest, root, excludedRootFields, isRoot = true)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun updateCanonicalDigest(
    digest: MessageDigest,
    node: JsonNode,
    excludedRootFields: Set<String>,
    isRoot: Boolean
) {
    when {
        node.isNull -> updateCanonicalToken(digest, 'n', byteArrayOf())
        node.isBoolean -> updateCanonicalToken(digest, 'b', if (node.booleanValue()) byteArrayOf('1'.code.toByte()) else byteArrayOf('0'.code.toByte()))
        node.isTextual -> updateCanonicalToken(digest, 's', node.textValue().toByteArray(Charsets.UTF_8))
        node.isNumber -> updateCanonicalToken(digest, 'd', node.asText().toByteArray(Charsets.UTF_8))
        node.isArray -> {
            updateCanonicalToken(digest, 'a', node.size().toString().toByteArray(Charsets.UTF_8))
            node.forEach { child -> updateCanonicalDigest(digest, child, excludedRootFields, isRoot = false) }
        }
        node.isObject -> {
            val fields = node.fields().asSequence()
                .filterNot { (name, _) -> isRoot && name in excludedRootFields }
                .sortedBy { (name, _) -> name }
                .toList()
            updateCanonicalToken(digest, 'o', fields.size.toString().toByteArray(Charsets.UTF_8))
            fields.forEach { (name, child) ->
                updateCanonicalToken(digest, 's', name.toByteArray(Charsets.UTF_8))
                updateCanonicalDigest(digest, child, excludedRootFields, isRoot = false)
            }
        }
        else -> throw IllegalArgumentException("unsupported canonical checksum JSON node: ${node.nodeType}")
    }
}

private fun updateCanonicalToken(digest: MessageDigest, kind: Char, value: ByteArray) {
    digest.update(kind.code.toByte())
    digest.update(value.size.toString().toByteArray(Charsets.UTF_8))
    digest.update(':'.code.toByte())
    digest.update(value)
}
