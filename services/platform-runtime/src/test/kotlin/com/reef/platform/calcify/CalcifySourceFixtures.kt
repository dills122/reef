package com.reef.platform.calcify

import com.fasterxml.jackson.databind.node.ObjectNode
import com.reef.platform.api.JsonCodec
import java.nio.file.Path
import kotlin.io.path.readLines

/** Historical fixture commands all use run-a; retain original evidence unchanged.
 * Upgrade outcome metadata for current-producer tests; legacy decoding tested separately.
 */
internal object CalcifySourceFixtures {
    fun currentBodies(): List<String> = Path.of("../../docs/evidence/calcify-phase2/source-fixture.jsonl").readLines().map { body ->
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(body) as ObjectNode
        root.get("outcomes").forEach { (it as ObjectNode).put("runId", "run-a") }
        rechecksum(root.toString())
    }

    fun rechecksum(body: String): String {
        val json = JsonCodec.parseObject(body)
        return body.replace(json.string("payloadChecksum"), json.semanticSha256(setOf("createdAt", "workFinishedAt", "timingChecksum", "payloadChecksum", "payloadChecksumAlgorithm")))
    }
}
