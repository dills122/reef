package com.reef.platform.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.SettlementColdKafkaTopicProbe
import com.reef.platform.infrastructure.persistence.SettlementColdKafkaTopics
import com.reef.platform.infrastructure.persistence.SettlementSourceTopicBinding
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostMatchCandidateSourceEnrollmentTest {
    private val generation = "04858c29-b59c-4dc0-bf6b-17de4423c271"
    private val topics = SettlementColdKafkaTopics(
        "75HmTgAYQR6fPIl7Uw5wQw", "4lQ3vSFqRDihUXjKWVbC1Q")

    @Test
    fun freshEnrollmentEmitsOneParseableReceiptWithAllColdFrontiers() {
        val fixture = Fixture()
        val receipt = fixture.service.enroll("stream", "commands", "events")
        assertEquals(1, fixture.registry.enrollCalls)
        assertEquals(16, receipt.frontiers.size)
        assertEquals((0 until 16).associateWith(CanonicalStreamPosition::origin), receipt.frontiers)
        assertEquals(fixture.registry.binding!!.digest(), receipt.bindingDigest)
        val json = ObjectMapper().readTree(receipt.jsonLine())
        assertEquals("settlement_source_enrollment", json["type"].asText())
        assertEquals(generation, json["sourceGeneration"].asText())
        assertEquals(16, json["partitionCount"].asInt())
        assertEquals(16, json["frontiers"].size())
        assertEquals(receipt.bindingDigest, json["bindingDigest"].asText())
        assertTrue(!receipt.jsonLine().contains('\n'))
    }

    @Test
    fun repeatedColdEnrollmentVerifiesExistingBindingWithoutReinserting() {
        val fixture = Fixture()
        fixture.registry.binding = fixture.binding()
        val receipt = fixture.service.enroll("stream", "commands", "events")
        assertEquals(0, fixture.registry.enrollCalls)
        assertEquals(topics.commandTopicId, receipt.commandTopicId)
    }

    @Test
    fun changedTopicIncarnationCannotReuseAnExistingBinding() {
        val fixture = Fixture()
        fixture.registry.binding = fixture.binding().copy(commandTopicId = "new-topic-id")
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
        assertEquals(0, fixture.registry.enrollCalls)
    }

    @Test
    fun populatedCanonicalSourceOrKafkaTopicsFailBeforeInsert() {
        val fixture = Fixture()
        fixture.sourceEmpty = false
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
        fixture.sourceEmpty = true
        fixture.coldTopics = null
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
        assertEquals(0, fixture.registry.enrollCalls)
    }

    @Test
    fun nonColdPartitionOrWrongKafkaPartitionCountFailsClosed() {
        val fixture = Fixture()
        fixture.heads = fixture.heads + (3 to CanonicalStreamPosition.origin(3) + 1)
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
        fixture.heads = (0 until 16).associateWith(CanonicalStreamPosition::origin)
        fixture.partitionsMatch = false
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
        assertEquals(0, fixture.registry.enrollCalls)
    }

    @Test
    fun concurrentIdenticalBindingInsertMayWinButDifferentBindingCannot() {
        val fixture = Fixture()
        fixture.registry.duplicateOnEnroll = true
        val receipt = fixture.service.enroll("stream", "commands", "events")
        assertEquals(topics.venueEventTopicId, receipt.venueEventTopicId)
        fixture.registry.binding = fixture.binding().copy(venueEventTopicId = "different")
        assertFailsWith<IllegalStateException> {
            fixture.service.enroll("stream", "commands", "events")
        }
    }

    private inner class Fixture {
        var sourceEmpty = true
        var coldTopics: SettlementColdKafkaTopics? = topics
        var partitionsMatch = true
        var heads = (0 until 16).associateWith(CanonicalStreamPosition::origin)
        val catalog = object : PostMatchReadSourceCatalog {
            override fun generation() = generation
            override fun partitionHeads(eventStream: String, partitionCount: Int) = heads
        }
        val registry = FakeRegistry()
        val service = PostMatchCandidateSourceEnrollment(catalog, registry,
            SettlementColdKafkaTopicProbe { _, _ -> coldTopics },
            { sourceEmpty }, { _, count -> partitionsMatch && count == 16 })

        fun binding() = SettlementSourceTopicBinding(generation, "stream", "commands",
            topics.commandTopicId, "events", topics.venueEventTopicId)

        inner class FakeRegistry : SourceEnrollmentRegistry {
            var binding: SettlementSourceTopicBinding? = null
            var enrollCalls = 0
            var duplicateOnEnroll = false
            override fun readBinding(eventStream: String, sourceGeneration: String) = binding
            override fun enrollFresh(eventStream: String, commandTopic: String,
                venueEventTopic: String) {
                enrollCalls++
                binding = this@Fixture.binding()
                if (duplicateOnEnroll) throw SQLException("concurrent binding", "23505")
            }
        }
    }
}
