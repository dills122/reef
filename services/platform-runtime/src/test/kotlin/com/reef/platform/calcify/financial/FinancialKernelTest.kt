package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinancialKernelTest {
    private val fixtures = ObjectMapper().readTree(Path.of("../../docs/evidence/calcify-financial-sprint1/fixtures.json").readText())

    @Test fun completeHistoryRebuildsEveryCutAndEveryCheckpointSuffix() {
        for (fixture in fixtures["cases"]) {
            val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            val cuts = mutableListOf(kernel.ownerView())
            val checkpoints = mutableListOf(kernel.checkpoint())
            for (step in fixture["steps"]) {
                kernel.execute(step["input"])
                cuts.add(kernel.ownerView())
                checkpoints.add(kernel.checkpoint())
            }
            val records = kernel.history()
            for (cut in records.indices) {
                val rebuilt = FinancialKernel.replay(records.take(cut + 1))
                val fromCheckpoint = FinancialKernel.restore(rebuilt.checkpoint(), records.drop(cut + 1))
                assertEquals(kernel.ownerView(), fromCheckpoint.ownerView(), fixture["id"].asText())
            }
            assertEquals(kernel.ownerView(), FinancialKernel.replay(records).ownerView())
            for (checkpoint in checkpoints) {
                val seq = checkpoint["historySeq"].asLong()
                assertEquals(kernel.ownerView(), FinancialKernel.restore(checkpoint, records.filter { it["sequence"].asLong() > seq }).ownerView())
            }
        }
    }

    @Test fun replayFailsClosedOnMissingReorderedOrTamperedHistory() {
        val fixture = fixtures["cases"][0]
        val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        fixture["steps"].forEach { kernel.execute(it["input"]) }
        val records = kernel.history()
        assertFailsWith<IllegalArgumentException> { FinancialKernel.replay(listOf(records.first(), records.last())) }
        assertFailsWith<IllegalArgumentException> { FinancialKernel.replay(records.reversed()) }
        val corrupt = records.last().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("checksum", "corrupt")
        assertFailsWith<IllegalArgumentException> { FinancialKernel.replay(records.dropLast(1) + listOf(corrupt)) }
        val checkpoint = kernel.checkpoint().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        checkpoint["state"]["balances"].let { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("buyerCash", "999") }
        assertFailsWith<IllegalArgumentException> { FinancialKernel.restore(checkpoint, emptyList()) }
    }

    @Test fun seededTracesRestoreAtEveryHistoryRecordAndCheckpointCut() {
        val opening = fixtures["cases"][0]["genesisBalances"]
        for ((label, inputs) in FinancialOracleTest().generatedTraces()) {
            val kernel = FinancialKernel(opening, fixtures["policy"])
            val checkpoints = mutableListOf(kernel.checkpoint())
            for (input in inputs) { kernel.execute(input); checkpoints.add(kernel.checkpoint()) }
            val records = kernel.history()
            for (checkpoint in checkpoints) {
                val seq = checkpoint["historySeq"].asLong()
                val prefix = records.takeWhile { it["sequence"].asLong() <= seq }
                assertEquals(checkpoint["state"], FinancialKernel.replay(prefix).ownerView(), "$label history cut $seq")
                assertEquals(kernel.ownerView(), FinancialKernel.restore(checkpoint, records.drop(prefix.size)).ownerView(), "$label checkpoint cut $seq")
            }
        }
    }

    @Test fun deliverySchedulesPreserveBusinessCutsAndPendingMembership() {
        val fixture = fixtures["cases"].last()
        val inputs = fixture["steps"].map { it["input"] }
        val direct = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        val expectedCuts = inputs.map { direct.execute(it); direct.businessView() }
        for (schedule in fixtures["deliverySchedules"]) {
            val staged = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            val cuts = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
            val ahead = schedule["stageAhead"].asInt()
            if (ahead == 0) {
                inputs.forEach { staged.execute(it); cuts.add(staged.businessView()) }
            } else {
                var cursor = 0
                while (cursor < inputs.size || !staged.ownerView()["stagedInputs"].isEmpty) {
                    repeat(minOf(ahead, inputs.size - cursor)) { staged.stage(inputs[cursor++]) }
                    // Capture every equal business cut, independent of CPU yield/transaction grouping.
                    repeat(schedule["yieldBudget"].asInt()) {
                        if (staged.drainStaged(1).isNotEmpty()) cuts.add(staged.businessView())
                    }
                }
            }
            assertEquals(expectedCuts, cuts, schedule["id"].asText())
            assertEquals(direct.businessView(), staged.businessView())
            val history = staged.history()
            for (cut in history.indices) {
                val restored = FinancialKernel.replay(history.take(cut + 1))
                assertEquals(staged.ownerView(), FinancialKernel.restore(restored.checkpoint(), history.drop(cut + 1)).ownerView())
            }
            var grouped = FinancialKernel.replay(history.take(1))
            for (transaction in history.drop(1).chunked(schedule["transactionGroup"].asInt())) {
                grouped = FinancialKernel.restore(grouped.checkpoint(), transaction)
            }
            assertEquals(staged.ownerView(), grouped.ownerView(), "${schedule["id"].asText()} grouped recovery")
            assertEquals(0, staged.ownerView()["stagedInputs"].size())
        }
    }

    @Test fun clockBarrierStagesFundingAndDeduplicatesPendingMembership() {
        val fixture = fixtures["cases"].last()
        val steps = fixture["steps"].map { it["input"] }
        val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        steps.take(3).forEach { kernel.execute(it) }
        val before = kernel.businessView()
        assertEquals("STAGED", kernel.execute(steps[5])["disposition"].asText())
        val count = kernel.history().size
        kernel.execute(steps[5])
        assertEquals(count, kernel.history().size)
        assertEquals(1, kernel.ownerView()["stagedInputs"].size())
        assertEquals(before, kernel.businessView())
        assertEquals(emptyList(), kernel.drainStaged())
        val restored = FinancialKernel.replay(kernel.history())
        assertEquals(kernel.ownerView(), restored.ownerView())
        steps.slice(3..4).forEach { restored.execute(it) }
        restored.drainStaged()
        assertEquals("70000000000", restored.businessView()["balances"]["buyerCash"].asText())
        assertEquals("PENDING", restored.businessView()["obligations"].first { it["status"].asText() == "PENDING" }["status"].asText())
    }

    @Test fun clockBarrierDurablyStagesMalformedInputsUntilTerminalRejection() {
        val mapper = ObjectMapper()
        val fixture = fixtures["cases"].last()
        val steps = fixture["steps"].map { it["input"] }
        for (malformation in listOf("numeric", "missing-payload", "missing-kind", "continue-missing-payload", "continue-missing-workId")) {
            val continuationInput = malformation.startsWith("continue-")
            val input = steps[if (continuationInput) 3 else 0].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                put("actionId", "staged-invalid-$malformation")
                when (malformation) {
                    "numeric" -> (get("payload") as com.fasterxml.jackson.databind.node.ObjectNode).put("quantity", "NaN")
                    "missing-kind" -> remove("kind")
                    "continue-missing-workId" -> (get("payload") as com.fasterxml.jackson.databind.node.ObjectNode).remove("workId")
                    else -> remove("payload")
                }
            }
            val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            steps.take(3).forEach { kernel.execute(it) }
            val beforeStage = kernel.businessView()
            fun deliverPending() = if (continuationInput) kernel.stage(input) else kernel.execute(input)
            assertEquals("STAGED", deliverPending()["disposition"].asText(), malformation)
            val pending = kernel.ownerView()
            val stageRecord = kernel.lastRecord()!!
            assertEquals("STAGE", stageRecord["kind"].asText())
            assertTrue(mapper.writeValueAsBytes(stageRecord).size <= 65_536)
            assertTrue(stageRecord["changes"].size() <= 64)
            assertEquals(1, pending["stagedInputs"].size())
            assertEquals(input, pending["stagedInputs"].first()["input"])
            assertEquals(beforeStage, kernel.businessView())
            assertEquals("STAGED", deliverPending()["disposition"].asText())
            assertEquals(pending, kernel.ownerView(), "duplicate pending membership: $malformation")
            assertEquals(stageRecord, kernel.lastRecord(), "duplicate must not append history")
            assertEquals(emptyList(), kernel.drainStaged())

            val pendingCheckpoint = mapper.readTree(mapper.writeValueAsBytes(kernel.checkpoint()))
            val pendingHistory = kernel.history().map { mapper.readTree(mapper.writeValueAsBytes(it)) }
            val recovered = listOf(
                kernel,
                FinancialKernel.restore(pendingCheckpoint, emptyList()),
                FinancialKernel.replay(pendingHistory)
            )
            recovered.forEach { assertEquals(pending, it.ownerView(), "pending recovery: $malformation") }
            for (continuation in steps.slice(3..4)) {
                kernel.stage(continuation)
                val expected = kernel.drainStaged(1).single()
                recovered.drop(1).forEach {
                    it.stage(continuation)
                    assertEquals(expected, it.drainStaged(1).single())
                    assertEquals(kernel.ownerView(), it.ownerView())
                    assertEquals(kernel.lastRecord(), it.lastRecord())
                }
            }
            val beforeReject = kernel.ownerView()
            for (owner in recovered) {
                val result = owner.drainStaged().single()
                assertEquals("INVALID_INPUT", result["disposition"].asText(), malformation)
                assertEquals(0, result["journalLegs"].asInt())
                val terminal = owner.ownerView()
                assertEquals(0, terminal["stagedInputs"].size())
                assertEquals(beforeReject["businessSeq"].asLong() + 1, terminal["businessSeq"].asLong())
                assertEquals(beforeReject["deliveryCursor"].asLong() + 1, terminal["deliveryCursor"].asLong())
                for (field in listOf("balances", "executions", "obligations", "workflows", "instructions", "attempts", "exceptions", "reservations", "effects", "versions", "dueQueue")) {
                    assertEquals(beforeReject[field], terminal[field], "rejection changed $field: $malformation")
                }
                val rejection = terminal["dedup"].first { it["disposition"].asText() == "INVALID_INPUT" }
                assertEquals(pending["stagedInputs"].first()["digest"], rejection["digest"])
                assertEquals("gross-p1", rejection["context"]["policy"].asText())
                assertEquals("10", rejection["context"]["logicalTick"].asText())
                assertEquals(input["domain"], rejection["context"]["domain"])
                assertEquals(kernel.ownerView(), terminal, "deterministic rejection: $malformation")
                assertEquals(kernel.lastRecord(), owner.lastRecord())
                val terminalRecord = owner.lastRecord()
                assertEquals("BUSINESS", terminalRecord!!["kind"].asText())
                assertTrue(terminalRecord["journalGroups"].isEmpty)
                assertEquals("PRIOR_RESULT", owner.execute(input)["disposition"].asText())
                assertEquals(terminal, owner.ownerView(), "rejected retry must be inert")
                assertEquals(terminalRecord, owner.lastRecord())
            }
            assertEquals(kernel.ownerView(), FinancialKernel.replay(kernel.history()).ownerView())
            assertEquals(kernel.ownerView(), FinancialKernel.restore(pendingCheckpoint, kernel.history().drop(pendingHistory.size)).ownerView())
        }
    }

    @Test fun invalidEnvelopeIdentityRejectsAndIdenticalRetryReturnsPriorResult() {
        val mapper = ObjectMapper()
        val fixture = fixtures["cases"].last()
        for (field in listOf("namespace", "domain", "actionId")) for (malformation in listOf("missing", "empty", "null", "nontext")) {
            val input = fixture["steps"][0]["input"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                when (malformation) {
                    "missing" -> remove(field)
                    "empty" -> put(field, "")
                    "null" -> putNull(field)
                    "nontext" -> set<com.fasterxml.jackson.databind.JsonNode>(field, mapper.nodeFactory.numberNode(17))
                }
            }
            val label = "$field $malformation"
            val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            val before = kernel.ownerView()
            val result = kernel.execute(input)
            assertEquals("INVALID_INPUT", result["disposition"].asText(), label)
            assertEquals(0, result["journalLegs"].asInt())
            val rejected = kernel.ownerView()
            val record = kernel.lastRecord()
            for (economicField in listOf("balances", "executions", "obligations", "effects", "versions", "dueQueue")) {
                assertEquals(before[economicField], rejected[economicField], "$label changed $economicField")
            }
            for (owner in listOf(kernel, FinancialKernel.restore(kernel.checkpoint(), emptyList()), FinancialKernel.replay(kernel.history()))) {
                assertEquals("PRIOR_RESULT", owner.execute(input)["disposition"].asText(), label)
                assertEquals(rejected, owner.ownerView())
            }
            assertEquals(record, kernel.lastRecord())
        }
    }

    @Test fun envelopeIdentityTypesCannotConflateValidAndRejectedActions() {
        val mapper = ObjectMapper()
        val fixture = fixtures["cases"].last()
        for (field in listOf("namespace", "domain", "actionId")) for (invalidType in listOf("numeric", "null")) for (invalidFirst in listOf(false, true)) {
            val textValue = if (invalidType == "numeric") "17" else "null"
            val valid = fixture["steps"][0]["input"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { put(field, textValue) }
            val invalid = valid.deepCopy().apply {
                if (invalidType == "numeric") set<com.fasterxml.jackson.databind.JsonNode>(field, mapper.nodeFactory.numberNode(17)) else putNull(field)
            }
            val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            val label = "$field $invalidType invalidFirst=$invalidFirst"
            val ordered = if (invalidFirst) listOf(invalid, valid) else listOf(valid, invalid)
            for (input in ordered) {
                val before = kernel.ownerView()
                val result = kernel.execute(input)
                assertEquals(if (input === invalid) "INVALID_INPUT" else "CAPTURED", result["disposition"].asText(), label)
                if (input === invalid) {
                    assertEquals(0, result["journalLegs"].asInt())
                    for (economicField in listOf("balances", "executions", "obligations", "effects", "versions", "dueQueue")) {
                        assertEquals(before[economicField], kernel.ownerView()[economicField], "$label changed $economicField")
                    }
                }
            }
            val terminal = kernel.ownerView()
            assertEquals(2, terminal["dedup"].size(), label)
            assertEquals(setOf("CAPTURED", "INVALID_INPUT"), terminal["dedup"].map { it["disposition"].asText() }.toSet())
            assertEquals(1, terminal["executions"].size())
            for (owner in listOf(kernel, FinancialKernel.restore(kernel.checkpoint(), emptyList()), FinancialKernel.replay(kernel.history()))) {
                for (input in listOf(valid, invalid)) {
                    assertEquals("PRIOR_RESULT", owner.execute(input)["disposition"].asText(), label)
                    assertEquals(terminal, owner.ownerView(), "$label retry changed state")
                }
            }
        }
    }

    @Test fun missingStagingDeltaMutantCannotReconstructPendingOwnerState() {
        val fixture = fixtures["cases"].last()
        val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        fixture["steps"].take(3).forEach { kernel.execute(it["input"]) }
        kernel.stage(fixture["steps"][5]["input"])
        val history = kernel.history()
        val mutant = history.last().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        val changes = ObjectMapper().createArrayNode()
        mutant["changes"].filter { it["path"][0].asText() != "stagedInputs" }.forEach { changes.add(it) }
        mutant.set<com.fasterxml.jackson.databind.node.ArrayNode>("changes", changes)
        mutant.remove("checksum")
        fun canonical(n: com.fasterxml.jackson.databind.JsonNode): String = when {
            n.isObject -> n.properties().sortedBy { it.key }.joinToString(",", "{", "}") { ObjectMapper().writeValueAsString(it.key) + ":" + canonical(it.value) }
            n.isArray -> n.joinToString(",", "[", "]") { canonical(it) }
            else -> ObjectMapper().writeValueAsString(n)
        }
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(mutant).toByteArray()).joinToString("") { "%02x".format(it) }
        mutant.put("checksum", checksum)
        val rebuilt = FinancialKernel.replay(history.dropLast(1) + listOf(mutant))
        kotlin.test.assertNotEquals(kernel.ownerView(), rebuilt.ownerView(), "semantic staging omission must fail full-owner equality even with valid integrity hash")
        assertEquals(1, kernel.ownerView()["stagedInputs"].size())
        assertEquals(0, rebuilt.ownerView()["stagedInputs"].size())
    }

    @Test fun repeatedConflictRetainsFirstConflictContextAndDecisionIdentity() {
        val fixture = fixtures["cases"].first { it["id"].asText() == "changed-request-same-key" }
        val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        fixture["steps"].forEach { kernel.execute(it["input"]) }
        val before = kernel.ownerView()
        val result = kernel.execute(fixture["steps"].last()["input"])
        assertEquals("PRIOR_RESULT", result["disposition"].asText())
        assertEquals(before, kernel.ownerView())
        assertEquals("gross-p1", result["selectedPolicy"].asText())
    }

    @Test fun leanDecisionsEmitSameBoundedAuthorityWithoutProjectionImages() {
        val fixture = fixtures["cases"].last()
        val detailed = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        val lean = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        fixture["steps"].forEach { step ->
            val rich = detailed.execute(step["input"])
            val result = lean.execute(step["input"], detailed = false)
            assertEquals(rich["disposition"], result["disposition"])
            assertEquals(rich["journalLegs"], result["journalLegs"])
            assertEquals(setOf("disposition", "selectedPolicy", "journalLegs"), result.properties().map { it.key }.toSet())
            assertEquals(detailed.lastRecord(), lean.lastRecord())
        }
    }

    @Test fun seededStagedHistoriesRecoverEveryActualRecordCrashPoint() {
        val opening = fixtures["cases"][0]["genesisBalances"]
        for ((label, inputs) in FinancialOracleTest().generatedTraces()) {
            val direct = FinancialKernel(opening, fixtures["policy"])
            val staged = FinancialKernel(opening, fixtures["policy"])
            val uninterruptedCuts = mutableMapOf<Long, com.fasterxml.jackson.databind.JsonNode>(staged.historySequence() to staged.ownerView())
            inputs.forEach {
                direct.execute(it, detailed = false)
                staged.stage(it, detailed = false)
                uninterruptedCuts[staged.historySequence()] = staged.ownerView()
            }
            while (staged.drainStaged(1, detailed = false).isNotEmpty()) uninterruptedCuts[staged.historySequence()] = staged.ownerView()
            assertEquals(direct.businessView(), staged.businessView(), "$label staged economics")
            val history = staged.history()
            // Every actual STAGE/BUSINESS record is crash point. A retained checkpoint
            // anchors preceding record; only strict suffix replays after recovery.
            var prefix = FinancialKernel.replay(history.take(1))
            for (record in history.drop(1)) {
                val checkpoint = prefix.checkpoint()
                prefix = FinancialKernel.restore(checkpoint, listOf(record))
                assertEquals(uninterruptedCuts[record["sequence"].asLong()], prefix.ownerView(), "$label record ${record["sequence"]}")
            }
            assertEquals(staged.ownerView(), prefix.ownerView(), "$label complete delivery replay")
        }
    }

    @Test fun brokerModeRetainsOnlyLatestBoundedRecordAndReplaysStreamingHistory() {
        val fixture = fixtures["cases"].last()
        val retained = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
        val broker = FinancialKernel(fixture["genesisBalances"], fixtures["policy"], retainHistory = false)
        fixture["steps"].forEach { step ->
            retained.execute(step["input"], detailed = false)
            broker.execute(step["input"], detailed = false)
            assertEquals(retained.lastRecord(), broker.lastRecord())
        }
        assertFailsWith<IllegalStateException> { broker.history() }
        val stream = object : Iterable<com.fasterxml.jackson.databind.JsonNode> {
            override fun iterator() = retained.history().iterator()
        }
        val recovered = FinancialKernel.replay(stream, retainHistory = false)
        assertEquals(broker.ownerView(), recovered.ownerView())
        assertEquals(broker.lastRecord(), recovered.lastRecord())
        assertFailsWith<IllegalStateException> { recovered.history() }
    }

    @Test fun actualJsonWireHistoryAndCheckpointRoundTripsPreserveCompleteOwnerState() {
        val mapper = ObjectMapper()
        for (fixture in fixtures["cases"]) {
            val kernel = FinancialKernel(fixture["genesisBalances"], fixtures["policy"])
            fixture["steps"].forEach { kernel.execute(it["input"]) }
            val wireHistory = kernel.history().map { mapper.readTree(mapper.writeValueAsBytes(it)) }
            assertEquals(kernel.ownerView(), FinancialKernel.replay(wireHistory).ownerView(), fixture["id"].asText())
            for (cut in wireHistory.indices) {
                val prefix = FinancialKernel.replay(wireHistory.take(cut + 1))
                val wireCheckpoint = mapper.readTree(mapper.writeValueAsBytes(prefix.checkpoint()))
                assertEquals(kernel.ownerView(), FinancialKernel.restore(wireCheckpoint, wireHistory.drop(cut + 1)).ownerView())
            }
        }
        val staged = FinancialKernel(fixtures["cases"].last()["genesisBalances"], fixtures["policy"])
        fixtures["cases"].last()["steps"].take(3).forEach { staged.execute(it["input"]) }
        staged.stage(fixtures["cases"].last()["steps"][5]["input"])
        val restored = FinancialKernel.restore(mapper.readTree(mapper.writeValueAsBytes(staged.checkpoint())), emptyList())
        assertEquals(staged.ownerView(), restored.ownerView(), "wire pending membership checkpoint")
    }
}
