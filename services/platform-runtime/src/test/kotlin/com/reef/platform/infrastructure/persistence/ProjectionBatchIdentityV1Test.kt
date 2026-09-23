package com.reef.platform.infrastructure.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProjectionBatchIdentityV1Test {
    private val candidates = listOf(
        ProjectionBatchIdentityCandidate(
            partitionId = 3,
            streamSequence = 844424930131969,
            commandId = "command-a",
            canonicalBatchId = "batch-a",
            commandType = "SubmitOrder",
            payloadHash = "payload-hash-a"
        ),
        ProjectionBatchIdentityCandidate(
            partitionId = 7,
            streamSequence = 1970324836974593,
            commandId = "command-b",
            canonicalBatchId = "batch-b",
            commandType = "CancelOrder",
            payloadHash = "payload-hash-b"
        )
    )

    @Test
    fun producesTheFrozenCanonicalEncodingVector() {
        val identity = ProjectionBatchIdentityV1.digest(
            projectionName = "runtime-command-status",
            eventStream = "REEF_VENUE_EVENTS",
            projectionStage = ProjectionStage.CommandStatus,
            includeFills = false,
            candidates = candidates
        )

        assertEquals("9a5ce66a7a28af6eaa5937828b92e77b1f034089bee3d96c8e0b69bc93f08d4a", identity)
    }

    @Test
    fun changesForOrderedMembershipAndEffectChangingConfiguration() {
        val baseline = digest()

        assertNotEquals(baseline, digest(candidates = candidates.reversed()))
        assertNotEquals(baseline, digest(projectionName = "runtime-timeline"))
        assertNotEquals(baseline, digest(eventStream = "OTHER_STREAM"))
        assertNotEquals(baseline, digest(projectionStage = ProjectionStage.Timeline))
        assertNotEquals(baseline, digest(includeFills = true))
        assertNotEquals(
            baseline,
            digest(candidates = candidates.toMutableList().also { it[0] = it[0].copy(payloadHash = "different") })
        )
    }

    @Test
    fun lengthPrefixesKeepFieldBoundariesUnambiguous() {
        val left = listOf(
            candidates.first().copy(commandId = "ab", canonicalBatchId = "c")
        )
        val right = listOf(
            candidates.first().copy(commandId = "a", canonicalBatchId = "bc")
        )

        assertNotEquals(digest(candidates = left), digest(candidates = right))
    }

    @Test
    fun schedulingBatchSizeDoesNotChangeIdentityForTheSameSelectedMembership() {
        val selectedWithSmallLimit = candidates.take(2)
        val selectedWithLargeLimit = candidates.take(5000)

        assertEquals(
            digest(candidates = selectedWithSmallLimit),
            digest(candidates = selectedWithLargeLimit)
        )
    }

    private fun digest(
        projectionName: String = "runtime-command-status",
        eventStream: String = "REEF_VENUE_EVENTS",
        projectionStage: ProjectionStage = ProjectionStage.CommandStatus,
        includeFills: Boolean = false,
        candidates: List<ProjectionBatchIdentityCandidate> = this.candidates
    ): String {
        return ProjectionBatchIdentityV1.digest(
            projectionName = projectionName,
            eventStream = eventStream,
            projectionStage = projectionStage,
            includeFills = includeFills,
            candidates = candidates
        )
    }
}
