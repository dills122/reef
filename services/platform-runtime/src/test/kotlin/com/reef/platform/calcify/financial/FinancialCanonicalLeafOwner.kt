package com.reef.platform.calcify.financial
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat
/** Complete immutable canonical leaves; no retained mutable JSON nodes. Test-only. */
internal class FinancialCanonicalLeafOwner(initial: JsonNode) {
    private val json = ObjectMapper()
    private val categories = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val roots = linkedMapOf<String, String>()
    init {
        require(initial.isObject)
        initial.fields().forEach { (key, value) ->
            if (key in CATEGORY_KEYS) {
                require(value.isObject)
                categories[key] = linkedMapOf<String, String>().apply {
                    value.fields().forEach { (leafKey, leaf) -> put(leafKey, canonical(leaf)) }
                }
            } else roots[key] = canonical(value)
        }
    }
    fun get(path: List<String>): JsonNode {
        require(path.size in 1..2)
        val encoded = if (path.size == 1) {
            require(path[0] !in categories) { "category materialization requires explicit finite-fixture materialize()" }
            roots[path[0]]
        } else categories[path[0]]?.get(path[1])
        return encoded?.let { json.readTree(it) } ?: json.nullNode()
    }
    fun contains(root: String, key: String): Boolean = categories[root]?.containsKey(key) == true
    fun write(path: List<String>, value: JsonNode) {
        require(path.size in 1..2)
        if (path.size == 1) {
            require(path[0] !in CATEGORY_KEYS) { "category root writes unsupported" }
            roots[path[0]] = canonical(value)
        } else {
            val leaves = requireNotNull(categories[path[0]]) { "unknown category" }
            if (value.isNull) leaves.remove(path[1]) else leaves[path[1]] = canonical(value)
        }
    }
    /** Explicit finite-fixture/selfCheck use only; observer paths stream digest. */
    fun materialize(): ObjectNode = json.createObjectNode().apply {
        roots.forEach { (key, value) -> set<JsonNode>(key, json.readTree(value)) }
        categories.forEach { (key, leaves) ->
            set<JsonNode>(key, json.createObjectNode().apply {
                leaves.forEach { (leafKey, value) -> set<JsonNode>(leafKey, json.readTree(value)) }
            })
        }
    }
    fun clear() { categories.values.forEach { it.clear() }; categories.clear(); roots.clear() }
    fun retainedLeafCount(): Int = categories.values.sumOf { it.size }
    /** O(N) temporary sorted key references; never joins owner bytes or parses leaves. */
    fun ownerDigest(): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun part(value: String) { md.update(value.toByteArray(Charsets.UTF_8)) }
        part("{")
        (roots.keys + categories.keys).sorted().forEachIndexed { rootIndex, key ->
            if (rootIndex > 0) part(",")
            part(json.writeValueAsString(key)); part(":")
            val leaves = categories[key]
            if (leaves == null) part(roots.getValue(key)) else {
                part("{")
                leaves.keys.sorted().forEachIndexed { leafIndex, leafKey ->
                    if (leafIndex > 0) part(",")
                    part(json.writeValueAsString(leafKey)); part(":"); part(leaves.getValue(leafKey))
                }
                part("}")
            }
        }
        part("}")
        return HexFormat.of().formatHex(md.digest())
    }
    private fun canonical(n: JsonNode): String = when {
        n.isObject -> n.fields().asSequence().sortedBy { it.key }.joinToString(",", "{", "}") {
            json.writeValueAsString(it.key) + ":" + canonical(it.value)
        }
        n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
        else -> n.toString()
    }
    private companion object {
        val CATEGORY_KEYS = setOf("balances", "executions", "obligations", "workflows", "instructions", "attempts",
            "exceptions", "reservations", "dedup", "effects", "versions", "dueQueue", "stagedInputs")
    }
}
