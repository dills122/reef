package com.reef.arena.controlplane.arena

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.security.MessageDigest

enum class ArenaResolvedPolicyKind(val wireValue: String, val schemaVersion: String, val idField: String) {
    ActorProfileCatalog("actor_profile_catalog", "reef.arena.actorProfiles.v1", "catalogId"),
    EconomicPolicy("economic_policy", "reef.arena.economicPolicy.v1", "policyId")
}

data class ArenaResolvedPolicyArtifact(
    val kind: ArenaResolvedPolicyKind,
    val artifactId: String,
    val version: String,
    val contentHash: String,
    val canonicalContent: String
) {
    init {
        require(artifactId.isNotBlank() && artifactId.trim() == artifactId) { "artifactId is required" }
        require(version.isNotBlank() && version.trim() == version) { "version is required" }
        require(CANONICAL_SHA256.matches(contentHash)) { "contentHash must be a canonical sha256 digest" }
        require(canonicalContent.isNotBlank()) { "canonicalContent is required" }
    }

    private companion object {
        val CANONICAL_SHA256 = Regex("^sha256:[a-f0-9]{64}$")
    }
}

data class ArenaRosterResolvedPolicies(
    val actorProfileCatalog: ArenaResolvedPolicyArtifact,
    val economicPolicy: ArenaResolvedPolicyArtifact
) {
    init {
        require(actorProfileCatalog.kind == ArenaResolvedPolicyKind.ActorProfileCatalog) {
            "actorProfileCatalog artifact kind is invalid"
        }
        require(economicPolicy.kind == ArenaResolvedPolicyKind.EconomicPolicy) {
            "economicPolicy artifact kind is invalid"
        }
    }
}

class ArenaRosterPolicyVerifier(
    private val mapper: JsonMapper = JsonMapper.builder().build()
) {
    fun verify(snapshot: ArenaRosterPolicySnapshot, resolved: ArenaRosterResolvedPolicies) {
        verifyArtifact(resolved.actorProfileCatalog)
        verifyArtifact(resolved.economicPolicy)
        require(snapshot.actorProfileVersion == resolved.actorProfileCatalog.version) {
            "actor profile version does not match resolved catalog"
        }
        require(snapshot.actorProfileHash == resolved.actorProfileCatalog.contentHash) {
            "actor profile hash does not match resolved catalog"
        }
        require(snapshot.economicPolicyVersion == resolved.economicPolicy.version) {
            "economic policy version does not match resolved policy"
        }
        require(snapshot.economicPolicyHash == resolved.economicPolicy.contentHash) {
            "economic policy hash does not match resolved policy"
        }
    }

    fun canonicalArtifact(
        kind: ArenaResolvedPolicyKind,
        artifactId: String,
        version: String,
        content: String
    ): ArenaResolvedPolicyArtifact {
        val node = parseObject(content)
        validateIdentity(kind, artifactId, version, node)
        val canonical = canonicalJson(node)
        return ArenaResolvedPolicyArtifact(kind, artifactId, version, sha256(canonical), canonical)
    }

    private fun verifyArtifact(artifact: ArenaResolvedPolicyArtifact) {
        val node = parseObject(artifact.canonicalContent)
        validateIdentity(artifact.kind, artifact.artifactId, artifact.version, node)
        val canonical = canonicalJson(node)
        require(canonical == artifact.canonicalContent) {
            "${artifact.kind.wireValue} content is not canonical JSON"
        }
        require(sha256(canonical) == artifact.contentHash) {
            "${artifact.kind.wireValue} content hash does not match canonical content"
        }
    }

    private fun validateIdentity(
        kind: ArenaResolvedPolicyKind,
        artifactId: String,
        version: String,
        node: JsonNode
    ) {
        require(node.path("schemaVersion").asText() == kind.schemaVersion) {
            "${kind.wireValue} schemaVersion must be ${kind.schemaVersion}"
        }
        require(node.path(kind.idField).asText() == artifactId) {
            "${kind.wireValue} ${kind.idField} does not match artifactId"
        }
        require(node.path("version").asText() == version) {
            "${kind.wireValue} version does not match artifact version"
        }
        when (kind) {
            ArenaResolvedPolicyKind.ActorProfileCatalog -> {
                requireExactFields(node, setOf("schemaVersion", "catalogId", "version", "profiles"), kind)
                validateActorProfiles(node.path("profiles"))
            }
            ArenaResolvedPolicyKind.EconomicPolicy -> validateEconomicPolicy(node, kind)
        }
    }

    private fun validateActorProfiles(profiles: JsonNode) {
        require(profiles.isArray && !profiles.isEmpty) { "actor profile catalog profiles must be a non-empty array" }
        val profileIds = mutableSetOf<String>()
        profiles.forEachIndexed { index, profile ->
            val path = "actor profile catalog profiles[$index]"
            require(profile.isObject) { "$path must be an object" }
            requireExactFields(
                profile,
                setOf(
                    "profileId", "version", "actorClass", "description", "difficultyBucket", "scoreEffect",
                    "allowedParamKeys", "params"
                ),
                path
            )
            val profileId = requiredText(profile, "profileId", path)
            require(profileIds.add(profileId)) { "duplicate actor profile $profileId" }
            requiredText(profile, "version", path)
            require(requiredText(profile, "actorClass", path) in ACTOR_CLASSES) { "$path.actorClass is unsupported" }
            requiredText(profile, "description", path)
            requiredText(profile, "difficultyBucket", path)
            require(requiredText(profile, "scoreEffect", path) in SCORE_EFFECTS) { "$path.scoreEffect is unsupported" }
            val allowedParamKeys = profile.path("allowedParamKeys")
            require(allowedParamKeys.isArray) { "$path.allowedParamKeys must be an array" }
            val allowed = allowedParamKeys.mapIndexed { keyIndex, value ->
                require(value.isTextual && value.textValue().isNotBlank()) { "$path.allowedParamKeys[$keyIndex] must be a string" }
                value.textValue()
            }
            require(allowed.distinct().size == allowed.size) { "$path.allowedParamKeys must be unique" }
            val params = profile.path("params")
            require(params.isObject) { "$path.params must be an object" }
            require(params.fieldNames().asSequence().all { it in allowed }) { "$path.params contains an unknown parameter" }
            require(params.elements().asSequence().all { it.isTextual || it.isNumber || it.isBoolean }) {
                "$path.params values must be finite scalars"
            }
        }
    }

    private fun validateEconomicPolicy(node: JsonNode, kind: ArenaResolvedPolicyKind) {
        requireExactFields(
            node,
            setOf(
                "schemaVersion", "policyId", "version", "currency", "competitionLedger", "houseLedger",
                "fees", "rebates", "sources", "sinks", "reconciliation"
            ),
            kind
        )
        require(Regex("^[A-Z]{3}$").matches(node.path("currency").asText())) {
            "economic policy currency must be an ISO-style three-letter code"
        }
        validateDecimalObject(
            node.path("competitionLedger"),
            setOf("startingCashPerCompetitor", "allowNegativeCash"),
            "economic policy competitionLedger",
            setOf("allowNegativeCash")
        )
        validateDecimalObject(
            node.path("houseLedger"),
            setOf("marketMakerStartingCash", "npcStartingCash", "subsidyBudget"),
            "economic policy houseLedger"
        )
        validateDecimalObject(
            node.path("fees"),
            setOf("makerBps", "takerBps", "cancelFee", "borrowBps", "liquidationPenaltyBps"),
            "economic policy fees"
        )
        val rebates = node.path("rebates")
        requireExactFields(rebates, setOf("makerBps", "fundingSource"), "economic policy rebates")
        requireDecimal(rebates.path("makerBps"), "economic policy rebates.makerBps")
        require(rebates.path("fundingSource").asText() in setOf("none", "taker_fees", "house_subsidy")) {
            "economic policy rebates.fundingSource is unsupported"
        }
        validateFlows(node.path("sources"), "sources")
        validateFlows(node.path("sinks"), "sinks")
        val reconciliation = node.path("reconciliation")
        requireExactFields(
            reconciliation,
            setOf("tolerance", "requireBalancedTransfers", "competitionLedger", "houseLedger"),
            "economic policy reconciliation"
        )
        requireDecimal(reconciliation.path("tolerance"), "economic policy reconciliation.tolerance")
        listOf("requireBalancedTransfers", "competitionLedger", "houseLedger").forEach { field ->
            require(reconciliation.path(field).isBoolean) { "economic policy reconciliation.$field must be true or false" }
        }
    }

    private fun validateDecimalObject(node: JsonNode, fields: Set<String>, path: String, booleans: Set<String> = emptySet()) {
        requireExactFields(node, fields, path)
        fields.forEach { field ->
            if (field in booleans) require(node.path(field).isBoolean) { "$path.$field must be true or false" }
            else requireDecimal(node.path(field), "$path.$field")
        }
    }

    private fun validateFlows(node: JsonNode, name: String) {
        require(node.isArray) { "economic policy $name must be an array" }
        val codes = mutableSetOf<String>()
        node.forEachIndexed { index, flow ->
            val path = "economic policy $name[$index]"
            requireExactFields(flow, setOf("code", "ledger", "enabled", "funding"), path)
            require(codes.add(requiredText(flow, "code", path))) { "duplicate economic policy $name code" }
            require(requiredText(flow, "ledger", path) in setOf("competition", "house")) { "$path.ledger is unsupported" }
            require(flow.path("enabled").isBoolean) { "$path.enabled must be true or false" }
            requiredText(flow, "funding", path)
        }
    }

    private fun requireDecimal(node: JsonNode, path: String) {
        require(node.isTextual && DECIMAL.matches(node.textValue())) { "$path must be a nonnegative canonical decimal string" }
    }

    private fun requiredText(node: JsonNode, field: String, path: String): String {
        val value = node.path(field)
        require(value.isTextual && value.textValue().isNotBlank() && value.textValue().trim() == value.textValue()) {
            "$path.$field must be a non-empty trimmed string"
        }
        return value.textValue()
    }

    private fun requireExactFields(node: JsonNode, expected: Set<String>, kind: ArenaResolvedPolicyKind) {
        require(node.isObject) { "${kind.wireValue} must be an object" }
        val actual = node.fieldNames().asSequence().toSet()
        require(actual == expected) {
            val unexpected = (actual - expected).sorted()
            val missing = (expected - actual).sorted()
            "${kind.wireValue} fields are not strict; unexpected=$unexpected missing=$missing"
        }
    }

    private fun requireExactFields(node: JsonNode, expected: Set<String>, path: String) {
        require(node.isObject) { "$path must be an object" }
        val actual = node.fieldNames().asSequence().toSet()
        require(actual == expected) {
            "$path fields are not strict; unexpected=${(actual - expected).sorted()} missing=${(expected - actual).sorted()}"
        }
    }

    private fun parseObject(content: String): JsonNode {
        val node = try {
            mapper.readTree(content)
        } catch (ex: Exception) {
            throw IllegalArgumentException("resolved policy content must be valid JSON", ex)
        }
        require(node != null && node.isObject) { "resolved policy content must be a JSON object" }
        return node
    }

    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> node.properties().asSequence().sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
            "${mapper.writeValueAsString(key)}:${canonicalJson(value)}"
        }
        node.isArray -> node.joinToString(",", "[", "]") { canonicalJson(it) }
        node.isTextual -> mapper.writeValueAsString(node.textValue())
        node.isNull -> "null"
        node.isBoolean -> if (node.booleanValue()) "true" else "false"
        node.isIntegralNumber -> node.bigIntegerValue().toString()
        node.isFloatingPointNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
        else -> throw IllegalArgumentException("unsupported resolved policy JSON node: ${node.nodeType}")
    }

    private fun sha256(value: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val ACTOR_CLASSES = setOf("benchmark", "competitor", "house_market_maker", "npc_flow")
        val SCORE_EFFECTS = setOf("diagnostic-only", "difficulty-bucket", "eligible-for-score")
        val DECIMAL = Regex("^(0|[1-9]\\d*)(\\.\\d+)?$")
    }
}
