package com.reef.platform.application.settlementjournal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/** Exact, versioned control bytes retained by the journal for future replay. */
object SettlementJournalControlCodec {
    private const val TAG = "reef.settlement.control.v1"
    private const val MAX_FIELD_BYTES = 1_048_576
    private const val MAX_MEMBERS = 100_000

    fun encode(control: ReferenceControl): ByteArray {
        require(control.version == 1 && control.controlSequence > 0 && control.controlId.isNotBlank())
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.field(TAG)
            output.field(when (control) {
                is ReferencePolicyActivation -> "POLICY"
                is ReferenceOpening -> "OPENING"
                is ReferenceFunding -> "FUNDING"
            })
            output.writeInt(control.version)
            output.writeLong(control.controlSequence)
            output.field(control.controlId)
            when (control) {
                is ReferencePolicyActivation -> {
                    output.field(control.runId)
                    output.field(control.venueSessionId)
                    val frontiers = control.effectiveAfterSourceFrontiers.entries.sortedWith(compareBy(
                        { it.key.eventStream }, { it.key.sourceGeneration }, { it.key.partitionId }
                    ))
                    output.writeInt(frontiers.size)
                    frontiers.forEach { (stream, sequence) ->
                        output.field(stream.eventStream)
                        output.field(stream.sourceGeneration)
                        output.writeInt(stream.partitionId)
                        output.writeLong(sequence)
                    }
                    output.field(control.profileId)
                    output.writeInt(control.policyVersion)
                    output.field(control.mode)
                    output.field(control.settlementCycle)
                    output.field(control.nettingMode)
                    output.field(control.ledgerPostingMode)
                    output.field(control.selectionSource)
                }
                is ReferenceOpening -> {
                    output.account(control.account)
                    output.field(control.amount.toPlainString())
                }
                is ReferenceFunding -> {
                    output.account(control.account)
                    output.field(control.amount.toPlainString())
                    output.writeInt(control.retryTradeIds.size)
                    control.retryTradeIds.forEach { output.field(it) }
                }
            }
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): ReferenceControl = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.field() == TAG) { "unsupported journal control encoding" }
            val kind = input.field()
            val version = input.readInt()
            val sequence = input.readLong()
            val id = input.field()
            require(version == 1 && sequence > 0 && id.isNotBlank()) {
                "invalid journal control identity"
            }
            val control = when (kind) {
                "POLICY" -> {
                    val runId = input.field()
                    val venueSessionId = input.field()
                    val count = input.memberCount()
                    val frontiers = linkedMapOf<ReferenceStreamPartition, Long>()
                    repeat(count) {
                        val stream = ReferenceStreamPartition(input.field(), input.field(), input.readInt())
                        val sequenceAtActivation = input.readLong()
                        require(frontiers.putIfAbsent(stream, sequenceAtActivation) == null) {
                            "duplicate policy source frontier"
                        }
                    }
                    ReferencePolicyActivation(version, sequence, id, runId, venueSessionId,
                        frontiers, input.field(), input.readInt(), input.field(), input.field(),
                        input.field(), input.field(), input.field())
                }
                "OPENING" -> ReferenceOpening(version, sequence, id, input.account(),
                    BigDecimal(input.field()))
                "FUNDING" -> {
                    val account = input.account()
                    val amount = BigDecimal(input.field())
                    val retryTradeIds = List(input.memberCount()) { input.field() }
                    ReferenceFunding(version, sequence, id, account, amount, retryTradeIds)
                }
                else -> error("unsupported journal control kind")
            }
            require(input.available() == 0 && encode(control).contentEquals(bytes)) {
                "journal control has trailing or noncanonical bytes"
            }
            control
        }
    } catch (error: java.io.IOException) {
        throw IllegalArgumentException("invalid journal control bytes", error)
    }

    private fun DataOutputStream.field(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES) { "journal control field exceeds bound" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.field(): String {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "journal control field exceeds bound" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.memberCount(): Int = readInt().also {
        require(it in 0..MAX_MEMBERS) { "journal control member count exceeds bound" }
    }

    private fun DataOutputStream.account(account: ReferenceAccountKey) {
        field(account.runId)
        field(account.participantId)
        field(account.accountId)
        field(account.assetType)
        field(account.assetId)
    }

    private fun DataInputStream.account(): ReferenceAccountKey = ReferenceAccountKey(
        field(), field(), field(), field(), field()
    )
}
