package com.reef.platform.api

import com.reef.platform.application.postmatch.CanonicalStreamPosition
import com.reef.platform.domain.OwnExecutionView
import com.reef.platform.domain.OwnOrderView
import com.reef.platform.infrastructure.config.RuntimeEnv
import com.reef.platform.infrastructure.persistence.PostMatchOperationalReadStore
import com.reef.platform.infrastructure.persistence.PostMatchSourceCatalog
import com.reef.platform.infrastructure.persistence.PostMatchReadSourceCatalog
import com.reef.platform.infrastructure.persistence.RuntimeDataSources

/** Participant reads pinned to one source generation and one target snapshot. */
class PostMatchOwnReadGateway(
    private val catalog: PostMatchReadSourceCatalog,
    private val store: PostMatchOperationalReadStore,
    private val eventStream: String,
    private val partitionCount: Int
) {
    data class Snapshot<T>(
        val rows: List<T>, val eventStream: String, val sourceGeneration: String,
        val frontiers: Map<Int, Long>, val sourceHeads: Map<Int, Long>
    ) {
        fun asOf(): Map<String, Any> = mapOf(
            "eventStream" to eventStream,
            "sourceGeneration" to sourceGeneration,
            "frontiers" to frontiers.toSortedMap().map { (partitionId, sequence) ->
                mapOf(
                    "partitionId" to partitionId,
                    "lastStreamSequence" to sequence,
                    "sourceHeadSequence" to sourceHeads.getValue(partitionId),
                    "sourceLag" to sourceHeads.getValue(partitionId) - sequence
                )
            }
        )
    }

    init {
        require(eventStream.isNotBlank() && partitionCount in 1..32768)
    }

    fun ordersForParticipant(
        participantId: String, openOnly: Boolean, instrumentId: String, limit: Int
    ): Snapshot<OwnOrderView> = covered { generation ->
        store.ordersForParticipant(eventStream, generation, participantId, openOnly, instrumentId, limit)
    }

    fun executionsForParticipant(
        participantId: String, instrumentId: String, runId: String, limit: Int
    ): Snapshot<OwnExecutionView> = covered { generation ->
        store.executionsForParticipant(eventStream, generation, participantId, instrumentId, runId, limit)
    }

    private fun <T> covered(read: (String) -> PostMatchOperationalReadStore.Snapshot<T>): Snapshot<T> {
        val generation = catalog.generation()
        val snapshot = read(generation)
        val sourceHeads = catalog.partitionHeads(eventStream, partitionCount)
        check(sourceHeads.size == partitionCount) { "live read lacks canonical partition coverage" }
        val frontiers = (0 until partitionCount).associateWith { partition ->
            val origin = CanonicalStreamPosition.origin(partition)
            val head = sourceHeads.getValue(partition)
            val committed = snapshot.frontiers[partition]
            check(head >= origin && (head == origin || committed != null)) {
                "live read lacks an active partition frontier"
            }
            val frontier = committed ?: origin
            check(frontier in origin..head) { "live read frontier conflicts with canonical source" }
            check(head == frontier) { "live read is behind canonical source" }
            frontier
        }
        check(catalog.generation() == generation) { "live read source generation changed" }
        return Snapshot(snapshot.rows, eventStream, generation, frontiers, sourceHeads)
    }

    companion object {
        fun fromEnvOrNull(): PostMatchOwnReadGateway? {
            if (!RuntimeEnv.bool("POSTMATCH_LIVE_READS_ENABLED", false)) return null
            if (PlatformRuntimeRole.fromEnv() != PlatformRuntimeRole.Api) return null
            val eventStream = RuntimeEnv.string("POSTMATCH_EVENT_STREAM", "")
            val partitionCount = RuntimeEnv.int("STREAM_ACK_PARTITION_COUNT", 64, min = 1)
            val sourceUrl = RuntimeEnv.string("RUNTIME_POSTGRES_JDBC_URL", "")
            val targetUrl = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_JDBC_URL", "")
            require(sourceUrl.isNotBlank() && targetUrl.isNotBlank()) { "live reads require source and post-match JDBC URLs" }
            val sourceUser = RuntimeEnv.string("RUNTIME_POSTGRES_USER", "reef")
            val sourcePassword = RuntimeEnv.string("RUNTIME_POSTGRES_PASSWORD", "reef")
            val targetUser = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_USER", sourceUser)
            val targetPassword = RuntimeEnv.string("RUNTIME_POSTMATCH_POSTGRES_PASSWORD", sourcePassword)
            val source = RuntimeDataSources.dataSource(sourceUrl, sourceUser, sourcePassword, "postmatch-read-source")
            val target = RuntimeDataSources.dataSource(targetUrl, targetUser, targetPassword, "postmatch-read-target")
            return PostMatchOwnReadGateway(
                PostMatchSourceCatalog(source), PostMatchOperationalReadStore(target), eventStream,
                partitionCount
            )
        }
    }
}
