package com.reef.platform.calcify

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.google.protobuf.ByteString
import com.reef.platform.api.JsonDocument
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import reef.contracts.calcify.v1.*
import reef.contracts.orderexecution.v1.*

internal data class FiniteParty(val participant: String, val account: String, val side: OrderSide)

/** Logical payload budgets. Physical broker/JVM bounds and durable admission belong to O2. */
internal data class FiniteLifecycleBudget(
    val attempts: Long = 32, val publications: Int = 16, val commandBytes: Int = 8192,
    val outcomes: Int = 1, val trades: Int = 8, val openWindows: Int = 1,
    val sourceBytes: Int = 65536, val captureBytes: Int = 131072,
    val attemptBytes: Int = 4096, val openingBytes: Int = 16384,
    val orderIds: Int = 8, val quantity: Long = 10, val price: Long = 100000000000,
) {
    init {
        require(attempts in 1..32 && publications in 1..16 && commandBytes in 1..8192)
        require(outcomes == 1 && trades in 1..8 && openWindows == 1)
        require(sourceBytes in 1..65536 && captureBytes in 1..131072)
        require(attemptBytes in 1..4096 && openingBytes in 1..16384)
        require(orderIds in 1..8 && quantity in 1..10 && price in 1..100000000000)
        Math.multiplyExact(quantity, price)
    }
    val sourceTotalBytes: Long get() = publications.toLong() * sourceBytes
    val captureTotalBytes: Long get() = publications.toLong() * captureBytes
    // Complete record history plus acceptance/dependency rows and maximum fault suffix.
    val stateBytes: Long get() = 2 * captureTotalBytes + sourceTotalBytes + 8L * captureBytes + openingBytes

    fun canonical(profileHash: String, policy: String, run: String, session: String, instrument: String,
                  currency: String, parties: List<FiniteParty>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("calcify-finite-budget-v1\u0000".toByteArray())
            out.write(FiniteLifecycleContract.hex(profileHash))
            listOf(policy, run, session, instrument, currency).forEach { out.frame(it) }
            val ordered = parties.sortedWith(compareBy<FiniteParty> { it.participant }.thenBy { it.account })
            require(ordered.size == 2 && ordered.map { it.participant to it.account }.distinct().size == 2)
            out.writeInt(ordered.size)
            ordered.forEach { out.frame(it.participant); out.frame(it.account); out.writeByte(when(it.side) {
                OrderSide.ORDER_SIDE_BUY -> 1; OrderSide.ORDER_SIDE_SELL -> 2; else -> throw IllegalArgumentException("unsupported party side")
            }) }
            listOf(attempts, publications.toLong(), commandBytes.toLong(), outcomes.toLong(), trades.toLong(),
                openWindows.toLong(), sourceBytes.toLong(), captureBytes.toLong(), attemptBytes.toLong(), openingBytes.toLong())
                .forEach(out::writeLong)
        }
        return bytes.toByteArray()
    }
}

/** Immutable model binding. Construction never proves durable registration or writer isolation. */
internal data class FiniteLifecycleBinding(
    val digest: String, val profileHash: String, val run: String, val session: String,
    val instrument: String, val currency: String, val parties: List<FiniteParty>,
    val sourceTopic: String, val sourceTopicId: String, val generation: Int, val partition: Int,
    val commandTopic: String, val commandTopicId: String, val captureTopic: String, val captureTopicId: String,
    val applicationId: String, val genesisOffset: Long, val budget: FiniteLifecycleBudget = FiniteLifecycleBudget(),
    val policy: String = "calcify-limit-day-v1",
) {
    init {
        FiniteLifecycleContract.hex(digest); FiniteLifecycleContract.hex(profileHash)
        listOf(run, session, instrument, currency, sourceTopic, sourceTopicId, commandTopic, commandTopicId,
            captureTopic, captureTopicId, applicationId).forEach { FiniteLifecycleContract.text(it, false) }
        require(generation > 0 && partition >= 0 && genesisOffset >= 0 && policy == "calcify-limit-day-v1")
        require(parties.size == 2 && parties.map { it.participant }.distinct().size == 2 && parties.map { it.account }.distinct().size == 2)
        parties.forEach { FiniteLifecycleContract.text(it.participant, false); FiniteLifecycleContract.text(it.account, false) }
        require(parties.map { it.side }.toSet() == setOf(OrderSide.ORDER_SIDE_BUY, OrderSide.ORDER_SIDE_SELL))
    }
    fun party(participant: String, account: String): FiniteParty = parties.singleOrNull {
        it.participant == participant && it.account == account
    } ?: throw IllegalArgumentException("unmapped decoded participant/account")

    fun identity(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("calcify-finite-capture-binding-v1\u0000".toByteArray())
            out.write(FiniteLifecycleContract.hex(digest))
            out.write(budget.canonical(profileHash, policy, run, session, instrument, currency, parties))
            listOf(sourceTopic, sourceTopicId, commandTopic, commandTopicId, captureTopic, captureTopicId, applicationId,
                FiniteLifecycleContract.STATE_VERSION).forEach { out.frame(it) }
            out.writeInt(generation); out.writeInt(partition); out.writeLong(genesisOffset)
            // Matcher profile caps are separately identity-bound; never alter frozen P0 canonical bytes.
            out.writeInt(budget.orderIds); out.writeLong(budget.quantity); out.writeLong(budget.price)
        }
        return bytes.toByteArray()
    }
}

private fun DataOutputStream.frame(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes)
}

internal object FiniteLifecycleContract {
    const val STATE_VERSION = "calcify-finite-capture-state-v1"
    const val COMMAND_SCHEMA = "calcify-order-lifecycle-command-v1"
    const val CAPTURE_SCHEMA = "calcify-finite-lifecycle-capture-v1"
    private val strictMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    data class Outcome(val command: OrderLifecycleCommandV1, val payloadHash: String, val status: String,
                       val accepted: OrderAccepted?, val rejected: OrderRejected?,
                       val acceptance: AcceptedOrderSourceV1?, val trades: List<TradeSourceV1>,
                       val executions: List<ExecutionCreated>)
    data class Batch(val source: SourceProvenanceV1, val outcomes: List<Outcome>, val bytes: Int, val digest: String)

    fun parse(payload: ByteArray, binding: FiniteLifecycleBinding, offset: Long): Batch {
        require(payload.size <= binding.budget.sourceBytes) { "source record byte budget exceeded" }
        try { require(strictMapper.readTree(payload)?.isObject == true) { "source JSON must be one object" } }
        catch (ex: Exception) { throw IllegalArgumentException("invalid/duplicate/trailing source JSON", ex) }
        val root = CalcifySourceBatch.checked(payload, binding.sourceTopic, binding.partition)
        require(root.strictTextField("commandStream") == binding.commandTopic) { "command topic mismatch" }
        val rows = root.strictObjectDocuments("outcomes")
        require(rows.size <= binding.budget.outcomes) { "source outcome budget exceeded" }
        val decoded = MatchContextResolver.parseBatch(payload, binding.sourceTopic, binding.sourceTopicId,
            binding.generation, binding.partition, offset)
        val source = SourceProvenanceV1.newBuilder().setSourceGeneration(binding.generation)
            .setSourcePartition(binding.partition).setSourceOffset(offset).setSourceTopic(binding.sourceTopic)
            .setSourceTopicId(binding.sourceTopicId).setBatchId(root.strictTextField("batchId"))
            .setBatchChecksum(root.strictTextField("payloadChecksum")).build()
        val outcomes = rows.mapIndexed { ordinal, row ->
            val command = command(row.strictObject("lifecycleCommand"), binding)
            listOf("commandId" to command.commandId, "runId" to command.runId, "instrumentId" to command.instrumentId,
                "orderId" to command.orderId, "commandType" to kindText(command.kind)).forEach { (name, expected) ->
                require(row.strictTextField(name) == expected) { "outer/typed $name mismatch" }
            }
            val result = row.strictObject("result")
            require(!result.has("cancelled")) { "unsupported implicit cancellation" }
            val status = row.strictTextField("status")
            require(status in setOf("accepted", "rejected")) { "unsupported outcome status" }
            require(result.has("accepted") == (status == "accepted") && result.has("rejected") == (status == "rejected")) { "contradictory outcome result" }
            val accepted = if (status == "accepted") accepted(result.strictObject("accepted")) else null
            val rejected = if (status == "rejected") rejected(result.strictObject("rejected")) else null
            validateResult(command,status,accepted,rejected)
            val trades = decoded.trades.filter { it.source.outcomeOrdinal == ordinal }
            require(trades.size <= binding.budget.trades) { "trade fanout budget exceeded" }
            val executions = result.strictObjectDocuments("executions",false).map { execution(it) }
            require(executions.size == trades.size*2 && executions.map { it.executionId }.distinct().size == executions.size) { "missing/duplicate execution membership" }
            val memberSource = source.toBuilder().setOutcomeOrdinal(ordinal).setCommandId(command.commandId).build()
            trades.forEachIndexed { index, trade ->
                validateTrade(command,memberSource,trade,executions.subList(index*2,index*2+2),binding)
            }
            if (status == "rejected") {
                require(trades.isEmpty() && result.strictObjectDocuments("executions", false).isEmpty()) { "rejection has execution" }
            }
            if (command.kind == LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL) require(trades.isEmpty()) { "cancel has trades" }
            val acceptance = decoded.acceptedOrders.singleOrNull { it.source.outcomeOrdinal == ordinal }
            if (status == "accepted" && command.hasSubmit()) {
                require(acceptance != null) { "missing immutable submit acceptance" }
                val rawFact = result.strictObject("acceptedOrder")
                require(rawFact.fieldNames() == setOf("orderId","engineOrderId","clientOrderId","runId","venueSessionId",
                    "instrumentId","participantId","accountId","side","orderType","quantityUnits","limitPrice","currency","timeInForce","acceptedAt")) { "missing/unknown immutable acceptance fields" }
                optional(rawFact,"clientOrderId")
                validateAcceptance(command,memberSource,acceptance,accepted!!)
            } else if (status == "accepted") require(!result.has("acceptedOrder")) { "non-submit immutable acceptance" }
            Outcome(command, row.strictTextField("payloadHash").also(::hex), status, accepted, rejected, acceptance, trades, executions)
        }
        return Batch(source, outcomes, payload.size, sha(payload))
    }

    private fun command(row: JsonDocument, binding: FiniteLifecycleBinding): OrderLifecycleCommandV1 {
        val common = setOf("schema", "sourceProfileHash", "finiteBindingDigest", "commandType", "commandId", "runId",
            "venueSessionId", "instrumentId", "orderId", "participantId", "accountId", "traceId", "causationId", "correlationId", "actorId", "occurredAt")
        val kind = row.strictTextField("commandType")
        val payloadName = when(kind) { "SubmitOrder" -> "submit"; "ModifyOrder" -> "modify"; "CancelOrder" -> "cancel"; else -> throw IllegalArgumentException("unsupported lifecycle command") }
        require(row.fieldNames() == common + payloadName) { "missing/unknown lifecycle command fields" }
        require(row.strictTextField("schema") == COMMAND_SCHEMA && row.strictTextField("sourceProfileHash") == binding.profileHash &&
            row.strictTextField("finiteBindingDigest") == binding.digest) { "lifecycle version/profile/binding mismatch" }
        require(row.strictTextField("runId") == binding.run && row.strictTextField("venueSessionId") == binding.session &&
            row.strictTextField("instrumentId") == binding.instrument) { "lifecycle scope mismatch" }
        val order = row.strictTextField("orderId")
        require(Regex("p0-[1-9][0-9]*").matches(order) && (order.removePrefix("p0-").toIntOrNull() ?: Int.MAX_VALUE) <= binding.budget.orderIds) { "order outside profile" }
        val party = binding.party(row.strictTextField("participantId"), row.strictTextField("accountId"))
        val builder = OrderLifecycleCommandV1.newBuilder().setSchema(COMMAND_SCHEMA).setSourceProfileHash(binding.profileHash)
            .setFiniteBindingDigest(binding.digest).setCommandId(required(row, "commandId")).setRunId(required(row,"runId"))
            .setVenueSessionId(required(row,"venueSessionId")).setInstrumentId(required(row,"instrumentId")).setOrderId(order)
            .setParticipantId(required(row,"participantId")).setAccountId(required(row,"accountId"))
            .setTraceId(optional(row,"traceId")).setCausationId(optional(row,"causationId"))
            .setCorrelationId(optional(row,"correlationId")).setActorId(optional(row,"actorId")).setOccurredAt(timestamp(row,"occurredAt"))
        val fields = row.strictObject(payloadName)
        when(payloadName) {
            "submit" -> {
                require(fields.fieldNames() == setOf("clientOrderId","side","orderType","quantityUnits","limitPrice","currency","timeInForce"))
                val side = when(fields.strictTextField("side")) { "BUY" -> OrderSide.ORDER_SIDE_BUY; "SELL" -> OrderSide.ORDER_SIDE_SELL; else -> throw IllegalArgumentException("unsupported side") }
                val orderType = fields.strictTextField("orderType")
                require(side == party.side && orderType in setOf("LIMIT", "LIMIT_HIDDEN") && fields.strictTextField("currency") == binding.currency && fields.strictTextField("timeInForce") == "DAY") { "unsupported finite policy" }
                builder.setKind(LifecycleCommandKindV1.LIFECYCLE_COMMAND_SUBMIT).setSubmit(LifecycleSubmitV1.newBuilder()
                    .setClientOrderId(optional(fields,"clientOrderId")).setSide(side).setOrderType(OrderType.ORDER_TYPE_LIMIT)
                    .setSourceOrderType(orderType).setQuantityUnits(required(fields,"quantityUnits")).setLimitPrice(required(fields,"limitPrice"))
                    .setCurrency(binding.currency).setTimeInForce(TimeInForce.TIME_IN_FORCE_DAY))
            }
            "modify" -> {
                require(fields.fieldNames() == setOf("quantityUnits", "limitPrice"))
                builder.setKind(LifecycleCommandKindV1.LIFECYCLE_COMMAND_MODIFY).setModify(LifecycleModifyV1.newBuilder()
                    .setQuantityUnits(required(fields,"quantityUnits")).setLimitPrice(required(fields,"limitPrice")))
            }
            "cancel" -> { require(fields.fieldNames() == setOf("reason")); builder.setKind(LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL).setCancel(LifecycleCancelV1.newBuilder().setReason(optional(fields,"reason"))) }
        }
        return builder.build().also { validateCommand(it,binding) }
    }
    fun validateCommand(command: OrderLifecycleCommandV1, binding: FiniteLifecycleBinding) {
        require(command.schema == COMMAND_SCHEMA && command.sourceProfileHash == binding.profileHash &&
            command.finiteBindingDigest == binding.digest && command.runId == binding.run &&
            command.venueSessionId == binding.session && command.instrumentId == binding.instrument) { "typed checkpoint command binding mismatch" }
        listOf(command.commandId,command.runId,command.venueSessionId,command.instrumentId,command.orderId,
            command.participantId,command.accountId,command.occurredAt).forEach { text(it,false) }
        listOf(command.traceId,command.causationId,command.correlationId,command.actorId).forEach { text(it,true) }
        require(Regex("p0-[1-9][0-9]*").matches(command.orderId) &&
            (command.orderId.removePrefix("p0-").toIntOrNull() ?: Int.MAX_VALUE) <= binding.budget.orderIds) { "typed checkpoint order outside profile" }
        try { OffsetDateTime.parse(command.occurredAt) } catch(ex: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("typed checkpoint timestamp invalid",ex)
        }
        val party = binding.party(command.participantId,command.accountId)
        when(command.kind) {
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_SUBMIT -> {
                require(command.hasSubmit() && command.submit.side == party.side && command.submit.orderType == OrderType.ORDER_TYPE_LIMIT &&
                    command.submit.sourceOrderType in setOf("LIMIT","LIMIT_HIDDEN") && command.submit.timeInForce == TimeInForce.TIME_IN_FORCE_DAY &&
                    command.submit.currency == binding.currency) { "typed checkpoint submit missing/unsupported" }
                text(command.submit.clientOrderId,true); text(command.submit.quantityUnits,false); text(command.submit.limitPrice,false)
            }
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_MODIFY -> {
                require(command.hasModify()) { "typed checkpoint modify missing" }
                text(command.modify.quantityUnits,false); text(command.modify.limitPrice,false)
            }
            LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL -> { require(command.hasCancel()) { "typed checkpoint cancel missing" }; text(command.cancel.reason,true) }
            else -> throw IllegalArgumentException("typed checkpoint kind unsupported")
        }
    }
    /** Typed facts use the same semantic gates when decoded from source or restored from receipts. */
    fun validateResult(command: OrderLifecycleCommandV1, status: String, accepted: OrderAccepted?, rejected: OrderRejected?) {
        require(status in setOf("accepted","rejected") && (accepted != null) == (status == "accepted") &&
            (rejected != null) == (status == "rejected")) { "contradictory typed outcome result" }
        if(accepted != null) {
            listOf(accepted.eventId,accepted.orderId,accepted.engineOrderId).forEach { text(it,false) }
            time(accepted.occurredAt)
            require(accepted.orderId == command.orderId && accepted.occurredAt == command.occurredAt) { "accepted result command mismatch" }
        } else {
            requireNotNull(rejected)
            listOf(rejected.eventId,rejected.orderId,rejected.code,rejected.reason).forEach { text(it,false) }
            time(rejected.occurredAt)
            require(rejected.orderId == command.orderId && rejected.occurredAt == command.occurredAt) { "rejected result command mismatch" }
        }
    }
    fun validateAcceptance(command: OrderLifecycleCommandV1, source: SourceProvenanceV1,
                           acceptance: AcceptedOrderSourceV1, accepted: OrderAccepted) {
        val fact = acceptance.fact; val attempt = command.submit
        require(command.hasSubmit() && acceptance.source == source && acceptance.acceptance == accepted &&
            fact.orderId == command.orderId && fact.engineOrderId == accepted.engineOrderId &&
            fact.runId == command.runId && fact.venueSessionId == command.venueSessionId &&
            fact.instrumentId == command.instrumentId && fact.participantId == command.participantId && fact.accountId == command.accountId &&
            fact.clientOrderId == attempt.clientOrderId && fact.side == attempt.side && fact.orderType == attempt.orderType &&
            fact.quantityUnits == attempt.quantityUnits && fact.limitPrice == attempt.limitPrice && fact.currency == attempt.currency &&
            fact.timeInForce == attempt.timeInForce && fact.acceptedAt == command.occurredAt) { "typed submit/acceptance mismatch" }
    }
    fun validateTrade(command: OrderLifecycleCommandV1, source: SourceProvenanceV1, trade: TradeSourceV1,
                      executions: List<ExecutionCreated>, binding: FiniteLifecycleBinding) {
        val fact = trade.fact
        listOf(fact.eventId,fact.tradeId,fact.executionId,fact.buyOrderId,fact.sellOrderId,fact.instrumentId,
            fact.quantity.units,fact.price.nanos,fact.price.currency,trade.runId).forEach { text(it,false) }
        time(fact.occurredAt)
        require(command.kind != LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL && trade.source == source &&
            trade.runId == command.runId && fact.instrumentId == command.instrumentId && fact.price.currency == binding.currency &&
            fact.buyOrderId != fact.sellOrderId && (fact.buyOrderId == command.orderId || fact.sellOrderId == command.orderId) &&
            fact.occurredAt == command.occurredAt) { "trade command/scope/provenance mismatch" }
        Math.multiplyExact(amount(fact.quantity.units,binding.budget.quantity),amount(fact.price.nanos,binding.budget.price))
        require(executions.size == 2 && executions.map { it.executionId }.distinct().size == 2 &&
            executions.map { it.orderId }.toSet() == setOf(fact.buyOrderId,fact.sellOrderId) &&
            executions.map { it.liquidityRole }.toSet() == setOf(LiquidityRole.LIQUIDITY_ROLE_MAKER,LiquidityRole.LIQUIDITY_ROLE_TAKER)) { "execution party/role contradiction" }
        executions.forEach { execution ->
            listOf(execution.eventId,execution.executionId,execution.orderId,execution.instrumentId,
                execution.quantity.units,execution.executionPrice.nanos,execution.executionPrice.currency).forEach { text(it,false) }
            time(execution.occurredAt)
            // Preserve source maker/taker roles; only explicit source side suffixes are checked.
            val suffix = if(execution.orderId == fact.buyOrderId) "-buy" else "-sell"
            require(execution.executionId == fact.executionId+suffix && execution.instrumentId == fact.instrumentId &&
                execution.quantity == fact.quantity && execution.executionPrice == fact.price && execution.occurredAt == fact.occurredAt) { "execution/trade economics contradiction" }
        }
    }
    fun amount(value: String, cap: Long): Long = (value.toLongOrNull() ?: throw IllegalArgumentException("invalid integer economics"))
        .also { require(it in 1..cap) { "economics outside finite profile" } }
    private fun time(value: String) {
        text(value,false)
        try { OffsetDateTime.parse(value) } catch(ex: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("invalid finite fact timestamp",ex)
        }
    }
    private fun accepted(row: JsonDocument): OrderAccepted = OrderAccepted.newBuilder().setEventId(required(row,"eventId"))
        .setOrderId(required(row,"orderId")).setEngineOrderId(required(row,"engineOrderId")).setOccurredAt(timestamp(row,"occurredAt")).build()
    private fun rejected(row: JsonDocument): OrderRejected = OrderRejected.newBuilder().setEventId(required(row,"eventId"))
        .setOrderId(required(row,"orderId")).setCode(required(row,"code")).setReason(required(row,"reason")).setOccurredAt(timestamp(row,"occurredAt")).build()
    private fun execution(row: JsonDocument): ExecutionCreated = ExecutionCreated.newBuilder().setEventId(required(row,"eventId"))
        .setExecutionId(required(row,"executionId")).setOrderId(required(row,"orderId")).setInstrumentId(required(row,"instrumentId"))
        .setQuantity(OrderQuantity.newBuilder().setUnits(required(row,"quantityUnits")))
        .setExecutionPrice(Price.newBuilder().setNanos(required(row,"executionPrice")).setCurrency(required(row,"currency")))
        .setOccurredAt(timestamp(row,"occurredAt")).setLiquidityRole(when(required(row,"liquidityRole")) {
            "MAKER" -> LiquidityRole.LIQUIDITY_ROLE_MAKER; "TAKER" -> LiquidityRole.LIQUIDITY_ROLE_TAKER
            else -> throw IllegalArgumentException("unknown execution liquidity role")
        }).build()
    fun kindText(kind: LifecycleCommandKindV1) = when(kind) {
        LifecycleCommandKindV1.LIFECYCLE_COMMAND_SUBMIT -> "SubmitOrder"; LifecycleCommandKindV1.LIFECYCLE_COMMAND_MODIFY -> "ModifyOrder"
        LifecycleCommandKindV1.LIFECYCLE_COMMAND_CANCEL -> "CancelOrder"; else -> throw IllegalArgumentException("unknown kind")
    }
    private fun required(row: JsonDocument, key: String) = row.strictTextField(key).also { text(it,false) }
    private fun optional(row: JsonDocument, key: String): String {
        require(row.has(key) && row.raw(key).startsWith("\"")) { "missing/invalid explicit text $key" }
        return row.string(key).also { text(it,true) }
    }
    private fun timestamp(row: JsonDocument, key: String) = required(row,key).also {
        try { OffsetDateTime.parse(it) } catch(ex: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("invalid finite timestamp $key",ex)
        }
    }
    fun text(value: String, empty: Boolean) { require((empty || value.isNotBlank()) && value.toByteArray(Charsets.UTF_8).size <= 128) { "invalid/oversized finite text" } }
    fun hex(value: String): ByteArray { require(Regex("[0-9a-f]{64}").matches(value)) { "invalid SHA256 hex" }; return java.util.HexFormat.of().parseHex(value) }
    fun sha(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun digest(envelope: FiniteLifecycleCaptureV1): String = sha("calcify-finite-capture-content-v1\u0000".toByteArray() + envelope.toBuilder().clearContentDigest().build().toByteArray())
    fun genesis(binding: FiniteLifecycleBinding): FiniteLifecycleStateV1 = FiniteLifecycleStateV1.newBuilder()
        .setStateVersion(STATE_VERSION).setFiniteBindingDigest(binding.digest).setSourceProfileHash(binding.profileHash)
        .setBindingIdentity(ByteString.copyFrom(binding.identity())).setResumeOffset(binding.genesisOffset).build()
}
