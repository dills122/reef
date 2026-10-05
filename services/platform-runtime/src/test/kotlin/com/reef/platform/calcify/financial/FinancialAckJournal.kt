package com.reef.platform.calcify.financial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Test-only local ACK evidence, separate from broker financial authority.
 * Four forces per bounded batch: source facts, ordinal index, witness, publication.
 * Recovery streams published frames with bounded memory before writer activation.
 * Original clocks remain scoped; lost controller admission notifications stay unknown.
 */
internal class FinancialAckJournal(
    val directory: Path,
    val scope: Scope,
    val queueCapacity: Int = 16384,
    val batchSize: Int = 256,
    val batchMillis: Long = 10,
    val maxBytes: Long = 256L * 1024 * 1024,
    private val boundary: (String) -> Unit = {},
    private val onPublished: (JsonNode, Long) -> Unit = { _, _ -> }
) : AutoCloseable {
    data class Scope(val runId: String, val topic: String, val topicUuid: String, val partition: Int = 0, val domain: String = "hot")
    private data class Callback(val ordinal: Long, val offset: Long, val payload: ByteArray, val phase: String, val kind: String, val trade: Long, val offeredNano: Long, val callbackNano: Long)
    private data class Frame(val node: JsonNode, val end: Long, val checksum: String)
    private val json = ObjectMapper()
    private val clockScope = "${scope.runId}:${ProcessHandle.current().pid()}:${UUID.randomUUID()}"
    private val scopeHash = hash(json.writeValueAsBytes(scope))
    private val queue = ArrayBlockingQueue<Callback>(queueCapacity)
    private val failureRef = AtomicReference<Throwable>()
    val failure: Throwable? get() = failureRef.get()
    private val published = AtomicLong()
    private val forcedBatches = AtomicLong()
    private val forceElapsedNanos = AtomicLong()
    private val lastPublicationForceNano = AtomicLong()
    val publishedCount: Long get() = published.get()
    private val stopping = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val lockChannel: FileChannel
    private val fileLock: FileLock
    private val data: FileChannel
    private val index: FileChannel
    private val publications: FileChannel
    private val witness: FileChannel
    private var dataChain = "GENESIS"
    private var publicationChain = "GENESIS"
    private var lastPhysical = -1L
    private var auxiliaryBytes = 0L
    private val writer: Thread
    val recoveredCount: Long
    var recoveryReceipt: Map<String, Any> = emptyMap(); private set

    init {
        require(queueCapacity in 1..16384 && batchSize in 1..256 && batchSize <= queueCapacity && batchMillis in 1..1000)
        require(maxBytes in 4096..(2L * 1024 * 1024 * 1024)) { "ACK_JOURNAL_BYTE_CAP_INVALID" }
        require(scope.partition >= 0 && listOf(scope.runId, scope.topic, scope.topicUuid, scope.domain).all { it.isNotBlank() && it.length <= 256 })
        Files.createDirectories(directory)
        lockChannel = FileChannel.open(directory.resolve("writer.lock"), CREATE, WRITE)
        fileLock = try { requireNotNull(lockChannel.tryLock()) { "ACK_JOURNAL_ALREADY_OPEN" } } catch (t: Throwable) { lockChannel.close(); throw t }
        val opened = mutableListOf<FileChannel>()
        try {
            val header = directory.resolve("scope.json")
            val names = listOf("members.bin", "ordinal-index.bin", "publications.bin", "publication-witness.bin")
            if (Files.exists(header)) {
                require(Files.size(header) <= MAX_FRAME) { "ACK_JOURNAL_HEADER_BOUND" }
                val identity = json.readTree(Files.readAllBytes(header))
                require(identity["schema"].asText() == "financial-ack-journal-v1" && identity["scopeSha256"].asText() == scopeHash && identity["scope"] == json.valueToTree<JsonNode>(scope)) { "ACK_JOURNAL_GENERATION_MISMATCH" }
                require(names.all { Files.isRegularFile(directory.resolve(it)) }) { "ACK_JOURNAL_PUBLISHED_FILE_MISSING" }
            } else {
                require(names.none { Files.exists(directory.resolve(it)) }) { "ACK_JOURNAL_HEADER_MISSING" }
                names.forEach { FileChannel.open(directory.resolve(it), CREATE_NEW, WRITE).use { c -> c.force(true) } }
                FileChannel.open(header, CREATE_NEW, WRITE).use { c -> write(c, 0, json.writeValueAsBytes(mapOf("schema" to "financial-ack-journal-v1", "scope" to scope, "scopeSha256" to scopeHash))); c.force(true) }
                FileChannel.open(directory, READ).use { it.force(true) }
            }
            data = FileChannel.open(directory.resolve(names[0]), READ, WRITE).also { opened.add(it) }
            index = FileChannel.open(directory.resolve(names[1]), READ, WRITE).also { opened.add(it) }
            publications = FileChannel.open(directory.resolve(names[2]), READ, WRITE).also { opened.add(it) }
            witness = FileChannel.open(directory.resolve(names[3]), READ, WRITE).also { opened.add(it) }
            auxiliaryBytes = allStorageBytes() - data.size() - index.size() - publications.size() - witness.size()
            recover(); recoveredCount = publishedCount
            writer = Thread({ runWriter() }, "financial-ack-journal").apply { isDaemon = true; start() }
        } catch (t: Throwable) { opened.forEach { it.close() }; fileLock.release(); lockChannel.close(); throw t }
    }

    fun callback(ordinal: Long, offset: Long, payload: ByteArray, phase: String, kind: String, trade: Long, offeredNano: Long, callbackNano: Long) {
        try {
            checkHealthy(); check(!stopping.get()) { "ACK_JOURNAL_CLOSED" }
            require(ordinal in 0 until MAX_MEMBERS && offset >= 0 && trade >= 0 && payload.size in 1..MAX_PAYLOAD && phase in setOf("aged", "pending", "timed") && kind in setOf("CAPTURE", "SETTLE")) { "ACK_JOURNAL_CALLBACK_BOUND" }
            check(queue.offer(Callback(ordinal, offset, payload.copyOf(), phase, kind, trade, offeredNano, callbackNano))) { "ACK_JOURNAL_QUEUE_EXHAUSTED" }
        } catch (t: Throwable) { failureRef.compareAndSet(null, t); throw t }
    }

    fun checkHealthy() { failure?.let { throw IllegalStateException("ACK_JOURNAL_FAILED", it) } }

    /** Lookup owns one bounded frame; no complete JSON prefix or unbounded cache. */
    fun member(ordinal: Long): JsonNode? {
        try {
            checkHealthy(); if (ordinal < 0 || ordinal >= publishedCount) return null
            val location = ByteBuffer.wrap(read(index, ordinal * INDEX_BYTES, INDEX_BYTES))
            val dataPosition = location.long; val publicationPosition = location.long
            val frame = frame(data, dataPosition)
            val publication = frame(publications, publicationPosition)
            require(frame(witness, publicationPosition).checksum == publication.checksum) { "ACK_JOURNAL_PUBLICATION_WITNESS_MISMATCH" }
            val marker = publication.node
            validateMember(frame.node, ordinal, null)
            require(marker["firstOrdinal"].asLong() <= ordinal && marker["publishedCount"].asLong() > ordinal && marker["scopeSha256"].asText() == scopeHash) { "ACK_JOURNAL_INDEX_PUBLICATION_MISMATCH" }
            return frame.node.deepCopy<ObjectNode>().apply {
                val envelope = json.readTree(Base64.getDecoder().decode(get("payloadBase64").asText()))
                set<JsonNode>("input", envelope["input"]); put("dataForceNano", marker["dataForceNano"].asLong())
                put("forceClockScope", marker["clockScope"].asText()); putNull("controllerAdmissionNano")
            }
        } catch (t: Throwable) { failureRef.compareAndSet(null, t); throw t }
    }

    fun telemetry(): Map<String, Any?> = mapOf("schema" to "financial-ack-journal-v1", "directory" to directory.toString(), "scope" to scope,
        "publishedMembers" to publishedCount, "recoveredMembers" to recoveredCount, "queueCapacity" to queueCapacity, "queueSize" to queue.size,
        "batchSize" to batchSize, "batchMillis" to batchMillis, "maxPayloadBytes" to MAX_PAYLOAD, "maxFrameBytes" to MAX_FRAME,
        "maxAggregateBytes" to maxBytes, "logicalBytes" to storageBytes(), "dataSha256" to hashFile(directory.resolve("members.bin")),
        "publicationSha256" to hashFile(directory.resolve("publications.bin")), "publicationWitnessSha256" to hashFile(directory.resolve("publication-witness.bin")),
        "forcesPerBatch" to 4, "liveForcedBatches" to forcedBatches.get(), "liveForceElapsedNanos" to forceElapsedNanos.get(),
        "liveLastPublicationForceNano" to lastPublicationForceNano.get(), "liveClockScope" to clockScope,
        "controllerAdmissionRecovery" to "unknown; no source-derived timestamps", "recoveryReceipt" to recoveryReceipt)

    private fun runWriter() {
        try {
            while (!stopping.get() || queue.isNotEmpty()) {
                checkHealthy()
                val first = queue.poll(batchMillis, TimeUnit.MILLISECONDS) ?: continue
                val batch = ArrayList<Callback>(batchSize).apply { add(first) }
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchMillis)
                while (batch.size < batchSize) {
                    val remaining = until - System.nanoTime(); if (remaining <= 0) break
                    val next = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break; batch.add(next)
                }
                publish(batch)
            }
        } catch (t: Throwable) { failureRef.compareAndSet(null, t) }
    }

    private fun publish(batch: List<Callback>) {
        val first = publishedCount; val publicationPosition = publications.size()
        var position = data.size()
        val notices = ArrayList<JsonNode>(batch.size)
        for ((i, callback) in batch.withIndex()) {
            require(callback.ordinal == first + i && callback.offset > lastPhysical) { "ACK_JOURNAL_LOGICAL_PREFIX_OR_PHYSICAL_ORDER" }
            val member = json.valueToTree<ObjectNode>(mapOf("ordinal" to callback.ordinal, "offset" to callback.offset, "runId" to scope.runId, "topic" to scope.topic, "topicUuid" to scope.topicUuid,
                "partition" to scope.partition, "domain" to scope.domain, "payloadSha256" to hash(callback.payload), "bytes" to callback.payload.size,
                "payloadBase64" to Base64.getEncoder().encodeToString(callback.payload), "phase" to callback.phase, "kind" to callback.kind,
                "trade" to callback.trade, "offeredNano" to callback.offeredNano, "callbackNano" to callback.callbackNano, "clockScope" to clockScope, "priorSha256" to dataChain))
            validateMember(member, callback.ordinal, lastPhysical)
            val body = json.writeValueAsBytes(member)
            require(body.size <= MAX_FRAME) { "ACK_JOURNAL_FRAME_BOUND" }
            require(storageBytes() + body.size + FRAME_OVERHEAD + INDEX_BYTES + 2 * (MAX_FRAME + FRAME_OVERHEAD) <= maxBytes) { "ACK_JOURNAL_AGGREGATE_BYTE_CAP" }
            write(index, callback.ordinal * INDEX_BYTES, ByteBuffer.allocate(INDEX_BYTES).putLong(position).putLong(publicationPosition).array())
            position = appendFrame(data, position, body); dataChain = hash(body); lastPhysical = callback.offset; notices.add(member)
        }
        boundary("before-data-force"); checkHealthy()
        var forceStart = System.nanoTime(); data.force(true); val dataForceNano = System.nanoTime(); forceElapsedNanos.addAndGet(dataForceNano - forceStart)
        forceStart = System.nanoTime(); index.force(true); forceElapsedNanos.addAndGet(System.nanoTime() - forceStart)
        boundary("after-data-force-before-publication")
        val marker = json.writeValueAsBytes(mapOf("firstOrdinal" to first, "publishedCount" to first + batch.size, "dataEnd" to position,
            "indexEnd" to (first + batch.size) * INDEX_BYTES, "lastPhysicalOffset" to lastPhysical, "dataSha256" to dataChain, "scopeSha256" to scopeHash,
            "dataForceNano" to dataForceNano, "clockScope" to clockScope, "priorSha256" to publicationChain))
        require(storageBytes() + 2 * (marker.size + FRAME_OVERHEAD) <= maxBytes) { "ACK_JOURNAL_AGGREGATE_BYTE_CAP" }
        // Independent durable witness rejects loss of a whole otherwise-valid publication suffix.
        appendFrame(witness, publicationPosition, marker); forceStart = System.nanoTime(); witness.force(true); forceElapsedNanos.addAndGet(System.nanoTime() - forceStart)
        boundary("after-witness-force-before-publication")
        appendFrame(publications, publicationPosition, marker); forceStart = System.nanoTime(); publications.force(true)
        val publicationForceNano = System.nanoTime(); forceElapsedNanos.addAndGet(publicationForceNano - forceStart)
        lastPublicationForceNano.set(publicationForceNano); forcedBatches.incrementAndGet()
        boundary("after-publication-force-before-notification"); checkHealthy()
        publicationChain = hash(marker)
        notices.forEach { checkHealthy(); onPublished(it, System.nanoTime()) }
        published.set(first + batch.size)
    }

    private fun recover() {
        require(storageBytes() <= maxBytes) { "ACK_JOURNAL_AGGREGATE_BYTE_CAP" }
        require(witness.size() == publications.size()) { "ACK_JOURNAL_PUBLICATION_WITNESS_MISMATCH" }
        var pubPosition = 0L; var dataPosition = 0L; var count = 0L
        while (pubPosition < publications.size()) {
            val publication = frame(publications, pubPosition); val marker = publication.node
            require(frame(witness, pubPosition).checksum == publication.checksum) { "ACK_JOURNAL_PUBLICATION_WITNESS_MISMATCH" }
            require(marker["scopeSha256"].asText() == scopeHash && marker["priorSha256"].asText() == publicationChain && marker["firstOrdinal"].asLong() == count) { "ACK_JOURNAL_PUBLICATION_PREFIX" }
            val endCount = marker["publishedCount"].asLong()
            require(endCount > count && endCount <= MAX_MEMBERS && endCount - count <= 256) { "ACK_JOURNAL_PUBLICATION_BOUND" }
            while (count < endCount) {
                val location = ByteBuffer.wrap(read(index, count * INDEX_BYTES, INDEX_BYTES))
                require(location.long == dataPosition && location.long == pubPosition) { "ACK_JOURNAL_INDEX_MUTATED" }
                val member = frame(data, dataPosition)
                validateMember(member.node, count, lastPhysical)
                require(member.node["priorSha256"].asText() == dataChain && member.node["clockScope"].asText() == marker["clockScope"].asText()) { "ACK_JOURNAL_MEMBER_CHAIN" }
                dataChain = member.checksum; lastPhysical = member.node["offset"].asLong(); dataPosition = member.end; count++
            }
            require(marker["dataEnd"].asLong() == dataPosition && marker["indexEnd"].asLong() == count * INDEX_BYTES && marker["lastPhysicalOffset"].asLong() == lastPhysical && marker["dataSha256"].asText() == dataChain) { "ACK_JOURNAL_PUBLISHED_FRONTIER_MISMATCH" }
            publicationChain = publication.checksum; pubPosition = publication.end
        }
        val dataTail = data.size() - dataPosition; val indexTail = index.size() - count * INDEX_BYTES
        require(dataTail >= 0 && indexTail >= 0) { "ACK_JOURNAL_PUBLISHED_HISTORY_MISSING" }
        if (dataTail > 0 || indexTail > 0) {
            require(storageBytes() + dataTail + indexTail + MAX_FRAME <= maxBytes) { "ACK_JOURNAL_RECOVERY_TAIL_PRESERVATION_CAP" }
            val nonce = UUID.randomUUID().toString()
            val dataPath = directory.resolve("unpublished-$nonce-members.bin"); val indexPath = directory.resolve("unpublished-$nonce-index.bin")
            copyTail(data, dataPosition, dataPath); copyTail(index, count * INDEX_BYTES, indexPath)
            recoveryReceipt = mapOf("publishedMembers" to count, "unpublishedDataBytes" to dataTail, "unpublishedIndexBytes" to indexTail,
                "retainedDataFile" to dataPath.fileName.toString(), "retainedDataSha256" to hashFile(dataPath), "retainedIndexFile" to indexPath.fileName.toString(), "retainedIndexSha256" to hashFile(indexPath))
            FileChannel.open(directory.resolve("recovery-$nonce.json"), CREATE_NEW, WRITE).use { c -> write(c, 0, json.writeValueAsBytes(recoveryReceipt)); c.force(true) }
            FileChannel.open(directory, READ).use { it.force(true) }
            data.truncate(dataPosition); index.truncate(count * INDEX_BYTES); data.force(true); index.force(true)
            auxiliaryBytes = allStorageBytes() - data.size() - index.size() - publications.size() - witness.size()
        }
        published.set(count)
    }

    private fun validateMember(member: JsonNode, ordinal: Long, priorPhysical: Long?) {
        require(member["ordinal"].asLong() == ordinal && member["runId"].asText() == scope.runId && member["topic"].asText() == scope.topic && member["topicUuid"].asText() == scope.topicUuid && member["partition"].asInt() == scope.partition && member["domain"].asText() == scope.domain) { "ACK_JOURNAL_MEMBER_IDENTITY" }
        require(member["offset"].asLong() >= 0 && (priorPhysical == null || member["offset"].asLong() > priorPhysical)) { "ACK_JOURNAL_PHYSICAL_ORDER" }
        val payload = Base64.getDecoder().decode(member["payloadBase64"].asText())
        require(payload.size in 1..MAX_PAYLOAD && member["bytes"].asInt() == payload.size && hash(payload) == member["payloadSha256"].asText()) { "ACK_JOURNAL_PAYLOAD_IDENTITY" }
        val envelope = json.readTree(payload)
        require(envelope["inputOrdinal"].asLong() == ordinal && envelope["domain"].asText() == scope.domain && envelope["mode"].asText() == "EXECUTE" && envelope["input"]["kind"].asText() == member["kind"].asText()) { "ACK_JOURNAL_PAYLOAD_SCOPE" }
        require(member["phase"].asText() in setOf("aged", "pending", "timed") && member["kind"].asText() in setOf("CAPTURE", "SETTLE") && member["trade"].asLong() >= 0 && member["clockScope"].asText().isNotBlank()) { "ACK_JOURNAL_WORKLOAD_SCOPE" }
    }

    private fun frame(channel: FileChannel, position: Long): Frame {
        val size = ByteBuffer.wrap(read(channel, position, 4)).int
        require(size in 1..MAX_FRAME) { "ACK_JOURNAL_FRAME_BOUND_OR_TORN" }
        val body = read(channel, position + 4, size); val checksum = hash(body)
        require(HexFormat.of().formatHex(read(channel, position + 4 + size, 32)) == checksum) { "ACK_JOURNAL_FRAME_MUTATED" }
        return Frame(json.readTree(body), position + size + FRAME_OVERHEAD, checksum)
    }
    private fun appendFrame(channel: FileChannel, position: Long, body: ByteArray): Long {
        require(body.size <= MAX_FRAME)
        write(channel, position, ByteBuffer.allocate(body.size + FRAME_OVERHEAD).putInt(body.size).put(body).put(HexFormat.of().parseHex(hash(body))).array())
        return position + body.size + FRAME_OVERHEAD
    }
    private fun read(channel: FileChannel, position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length); var cursor = position
        while (buffer.hasRemaining()) { val n = channel.read(buffer, cursor); require(n > 0) { "ACK_JOURNAL_PUBLISHED_HISTORY_TORN" }; cursor += n }
        return buffer.array()
    }
    private fun write(channel: FileChannel, position: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes); var cursor = position
        while (buffer.hasRemaining()) { val n = channel.write(buffer, cursor); check(n > 0) { "ACK_JOURNAL_WRITE_STALLED" }; cursor += n }
    }
    private fun copyTail(channel: FileChannel, position: Long, path: Path) {
        FileChannel.open(path, CREATE_NEW, WRITE).use { target ->
            var cursor = position; var output = 0L
            while (cursor < channel.size()) { val size = minOf(65536L, channel.size() - cursor).toInt(); write(target, output, read(channel, cursor, size)); cursor += size; output += size }
            target.force(true)
        }
    }
    private fun storageBytes() = auxiliaryBytes + Files.size(directory.resolve("members.bin")) + Files.size(directory.resolve("ordinal-index.bin")) + Files.size(directory.resolve("publications.bin")) + Files.size(directory.resolve("publication-witness.bin"))
    private fun allStorageBytes() = Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
    private fun hashFile(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }
        return HexFormat.of().formatHex(md.digest())
    }
    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopping.set(true); writer.join(5000)
        if (writer.isAlive) { failureRef.compareAndSet(null, IllegalStateException("ACK_JOURNAL_CLOSE_TIMEOUT")); writer.interrupt() }
        listOf(data, index, publications, witness).forEach { runCatching { it.close() }.onFailure { t -> failureRef.compareAndSet(null, t) } }
        fileLock.release(); lockChannel.close(); checkHealthy()
    }
    private companion object {
        const val MAX_PAYLOAD = 1024
        const val MAX_FRAME = 4096
        const val FRAME_OVERHEAD = 36
        const val INDEX_BYTES = 16
        const val MAX_MEMBERS = 6_000_000L
    }
}
