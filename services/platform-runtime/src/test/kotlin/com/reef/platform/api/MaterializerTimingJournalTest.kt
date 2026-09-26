package com.reef.platform.api

import com.reef.platform.infrastructure.persistence.VenueCommandOutcomeFact
import com.reef.platform.infrastructure.persistence.VenueEventBatchFact
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterializerTimingJournalTest {
    private val commit = Instant.parse("2026-09-01T12:00:01Z")
    private val clock = Clock.fixed(commit, ZoneOffset.UTC)

    @Test
    fun retainsExactNoncontiguousMembershipAndDecimalStrings() {
        val journal = MaterializerTimingJournal(true, clock = clock)
        val first = 9_007_199_254_740_993L
        journal.record(batch(sequences = listOf(first, first + 2)), commit)
        val snapshot = journal.snapshot()
        val json = JsonCodec.parseObject(JsonCodec.writeObject(*snapshot.entries.map { it.key to it.value }.toTypedArray()))
        val record = json.objectDocuments("records").single()
        assertEquals("\"1\"", json.raw("lastSequence"))
        assertEquals("\"2\"", record.raw("commandCount"))
        assertEquals("[\"9007199254740993\",\"9007199254740995\"]", record.raw("streamSequences"))
        assertEquals("batch-1", record.string("batchId"))
        assertEquals("checksum", record.string("payloadChecksum"))
        assertEquals("commands", record.string("commandStream"))
        assertEquals(commit.toString(), record.string("canonicalCommitObservedAt"))
    }

    @Test
    fun capacityDoesNotEvictAndReportsEveryDrop() {
        val journal = MaterializerTimingJournal(true, maxEntries = 1, clock = clock)
        journal.record(batch(), commit)
        val before = journal.snapshot()
        journal.record(batch("batch-2"), commit)
        journal.record(batch("batch-3"), commit)
        assertEquals("2", journal.snapshot()["dropped"])
        assertEquals("1", journal.snapshot()["lastSequence"])
        assertEquals(before["records"], journal.snapshot()["records"])
        assertEquals("0", before["dropped"])
    }

    @Test
    fun duplicatesCannotReplaceOriginalEvidenceAndRestartChangesIdentity() {
        val journal = MaterializerTimingJournal(true, clock = clock)
        journal.record(batch(), commit)
        journal.record(batch().copy(payloadChecksum = "changed"), commit)
        assertEquals("1", journal.snapshot()["duplicate"])
        assertEquals("1", journal.snapshot()["lastSequence"])
        assertNotEquals(journal.snapshot()["instanceId"], MaterializerTimingJournal(true, clock = clock).snapshot()["instanceId"])
    }

    @Test
    fun invalidMembershipAndMissingOrFutureTimingFailClosed() {
        val journal = MaterializerTimingJournal(true, clock = clock)
        listOf(
            batch().copy(workFinishedAt = ""),
            batch().copy(workFinishedAt = "invalid"),
            batch().copy(workFinishedAt = commit.plusSeconds(1).toString()),
            batch().copy(commandCount = 2),
            batch(sequences = listOf(1, 1)),
            batch().copy(firstSequence = 2),
            batch().copy(payloadChecksumAlgorithm = ""),
            batch().copy(commandStream = "")
        ).forEach { journal.record(it, commit) }
        journal.record(batch(), commit.plusSeconds(1))
        assertEquals("9", journal.snapshot()["invalid"])
        assertEquals("0", journal.snapshot()["lastSequence"])
        assertTrue((journal.snapshot()["records"] as List<*>).isEmpty())
    }

    @Test
    fun disabledJournalRetainsNothing() {
        val journal = MaterializerTimingJournal(false, clock = clock)
        journal.record(batch(), commit)
        assertEquals(false, journal.snapshot()["enabled"])
        assertEquals("0", journal.snapshot()["lastSequence"])
    }

    @Test
    fun checkpointsSelectLaterRecordsWithoutChangingEarlierSnapshot() {
        val journal = MaterializerTimingJournal(true, clock = clock)
        journal.record(batch(), commit)
        val before = journal.snapshot()
        journal.record(batch("batch-2", listOf(4, 7)), commit)
        val after = journal.snapshot()
        assertEquals(before["instanceId"], after["instanceId"])
        assertEquals("1", before["lastSequence"])
        assertEquals("2", after["lastSequence"])
        @Suppress("UNCHECKED_CAST")
        val records = after["records"] as List<Map<String, Any>>
        assertEquals(listOf("1", "2"), records.map { it["sequence"] })
        assertEquals(listOf("4", "7"), records.last()["streamSequences"])
        assertEquals(1, (before["records"] as List<*>).size)
    }

    @Test
    fun preservesCompleteCheckpointShapeAndOriginalMembershipAfterCallerMutation() {
        val first = 9_007_199_254_740_993L
        val outcomes = batch(sequences = listOf(first, first + 2)).outcomes.toMutableList()
        val input = batch(sequences = listOf(first, first + 2)).copy(outcomes = outcomes)
        val journal = MaterializerTimingJournal(true, clock = clock)
        journal.record(input, commit)
        outcomes.clear()
        outcomes.addAll(batch(sequences = listOf(99)).outcomes)

        val snapshot = journal.snapshot()
        assertEquals(
            mapOf(
                "schemaVersion" to 1, "enabled" to true, "instanceId" to snapshot["instanceId"],
                "lastSequence" to "1", "dropped" to "0", "duplicate" to "0", "invalid" to "0",
                "records" to listOf(mapOf(
                    "sequence" to "1", "batchId" to "batch-1", "payloadChecksum" to "checksum",
                    "commandStream" to "commands", "partition" to 0, "commandCount" to "2",
                    "streamSequences" to listOf(first.toString(), (first + 2).toString()),
                    "workFinishedAt" to "2026-09-01T12:00:00Z",
                    "canonicalCommitObservedAt" to commit.toString()
                ))
            ),
            snapshot
        )
    }

    @Test
    fun mutatingReturnedCheckpointCannotChangeRetainedEvidence() {
        val journal = MaterializerTimingJournal(true, clock = clock)
        journal.record(batch(sequences = listOf(1, 3)), commit)
        val expected = journal.snapshot()
        val exposed = journal.snapshot()
        @Suppress("UNCHECKED_CAST")
        val records = exposed.getValue("records") as MutableList<MutableMap<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val membership = records.single().getValue("streamSequences") as MutableList<String>
        membership[0] = "999"
        records.single()["payloadChecksum"] = "changed"
        records.clear()
        assertEquals(expected, journal.snapshot())
        journal.record(batch("batch-2"), commit)
        assertEquals(1, (expected.getValue("records") as List<*>).size)
    }

    private fun batch(id: String = "batch-1", sequences: List<Long> = listOf(1)) = VenueEventBatchFact(
        batchId = id, shardId = "shard", partition = 0, commandStream = "commands", eventStream = "events",
        firstSequence = sequences.first(), lastSequence = sequences.last(), commandCount = sequences.size,
        createdAt = "2026-09-01T12:00:00Z", workFinishedAt = "2026-09-01T12:00:00Z",
        payloadChecksum = "checksum", payloadChecksumAlgorithm = "sha256-reef-canonical-v1",
        outcomes = sequences.map { VenueCommandOutcomeFact("command-$it", "SubmitOrder", it, 1, "hash", "AAPL", "order-$it", "ACCEPTED") }
    )
}
