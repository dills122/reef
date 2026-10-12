package com.reef.platform.api

import com.reef.platform.calcify.finance.FinanceAttemptRow
import com.reef.platform.calcify.finance.FinanceCheckpointRow
import com.reef.platform.calcify.finance.FinanceDecisionRow
import com.reef.platform.calcify.finance.FinanceObligationRow
import com.reef.platform.calcify.finance.FinancePositionRow
import com.reef.platform.calcify.finance.FinanceReadStore
import com.sun.net.httpserver.Headers

/**
 * S3-A1: GET-only read routes over the O4 projection of the finite P3 financial decision log. One store query per route.
 *
 * Authorization (owner-approved default rule):
 *  - operator routes `/internal/boundary/calcify/finance/{bindingDigest}/{resource}` read any binding and all five resources; they sit
 *    behind the internal-HTTP exposure mode like every other internal boundary read route;
 *  - participant routes `/api/v1/calcify/finance/{bindingDigest}/{resource}?accountId=` serve positions, obligations and attempts for an
 *    account the authenticated principal is explicitly scoped to; a principal with no account scope, or another account, gets 403;
 *    checkpoint and decisions (which carry other participants' action keys) are operator-only and answer 403 here.
 * Unlike the older `requireScope` helper, an empty scope never means "unrestricted" on these routes.
 */
internal class CalcifyFinanceReadGateway(private val store: FinanceReadStore?, private val boundary: ExternalApiBoundary) {
    companion object {
        const val OPERATOR_PREFIX = "/internal/boundary/calcify/finance/"
        const val PARTICIPANT_PREFIX = "/api/v1/calcify/finance/"
        const val MAX_DECISIONS = 320
        private val digest = Regex("^[0-9a-f]{64}$")
        private val participantResources = setOf("positions", "obligations", "attempts")
        private val operatorResources = participantResources + setOf("checkpoint", "decisions")
    }

    fun isOperatorPath(path: String) = path.startsWith(OPERATOR_PREFIX)
    fun isParticipantPath(path: String) = path.startsWith(PARTICIPANT_PREFIX)

    private fun error(status: Int, code: String, message: String) = PlatformHotPathResponse(status, JsonCodec.writeObject("code" to code, "message" to message))
    private fun parse(path: String, prefix: String): Pair<String, String>? {
        val parts = path.removePrefix(prefix).trim('/').split('/')
        return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
    }

    fun operatorResponse(path: String, query: String?): PlatformHotPathResponse {
        val (binding, resource) = parse(path, OPERATOR_PREFIX) ?: return error(404, "NOT_FOUND", "unknown finance route")
        if (!digest.matches(binding)) return error(400, "BAD_BINDING", "binding digest must be 64 lowercase hex characters")
        if (resource !in operatorResources) return error(404, "NOT_FOUND", "unknown finance resource")
        val s = store ?: return error(503, "UNAVAILABLE", "finance read store is not configured")
        return guarded { render(s, binding, resource, null, query) }
    }

    fun participantResponse(headers: Headers, path: String, query: String?): PlatformHotPathResponse {
        val (binding, resource) = parse(path, PARTICIPANT_PREFIX) ?: return error(404, "NOT_FOUND", "unknown finance route")
        if (!digest.matches(binding)) return error(400, "BAD_BINDING", "binding digest must be 64 lowercase hex characters")
        if (resource in setOf("checkpoint", "decisions")) {
            boundary.checkRead(headers, PARTICIPANT_PREFIX + "{bindingDigest}/" + resource)?.let { return denied(headers, it) }
            return error(403, "OPERATOR_ONLY", "this finance resource is available to operators only")
        }
        if (resource !in participantResources) return error(404, "NOT_FOUND", "unknown finance resource")
        val accountId = queryValue(query, "accountId")
        boundary.checkAccountRead(headers, PARTICIPANT_PREFIX + "{bindingDigest}/" + resource, accountId)?.let { return denied(headers, it) }
        val s = store ?: return error(503, "UNAVAILABLE", "finance read store is not configured")
        return guarded { render(s, binding, resource, setOf(accountId), query) }
    }

    private fun denied(headers: Headers, e: BoundaryError) = PlatformHotPathResponse(e.status, boundary.toErrorJson(e, headers.getFirst("X-Correlation-Id").orEmpty()))

    private fun guarded(block: () -> PlatformHotPathResponse): PlatformHotPathResponse =
        try { block() } catch (_: Exception) { error(503, "UNAVAILABLE", "finance read failed") }   // no SQL or driver text leaves the process

    private fun render(s: FinanceReadStore, binding: String, resource: String, accounts: Set<String>?, query: String?): PlatformHotPathResponse {
        fun respond(name: String, progress: FinanceCheckpointRow?, rows: List<Map<String, Any?>>): PlatformHotPathResponse {
            // Every resource carries the progress it reflects, read in the same snapshot as its rows; a binding with no projection is 404 everywhere.
            val p = progress ?: return error(404, "UNKNOWN_BINDING", "no projection for this binding")
            return PlatformHotPathResponse(200, JsonCodec.writeObject("bindingDigest" to binding,
                "progress" to mapOf("lastSequence" to p.lastSequence, "lastChain" to p.lastChain), name to rows))
        }
        return when (resource) {
            "checkpoint" -> {
                val row = s.checkpoint(binding) ?: return error(404, "UNKNOWN_BINDING", "no projection for this binding")
                PlatformHotPathResponse(200, JsonCodec.writeObject("bindingDigest" to binding, "ownerEpoch" to row.ownerEpoch,
                    "progress" to mapOf("lastSequence" to row.lastSequence, "lastChain" to row.lastChain), "checkpoint" to mapOf("ownerEpoch" to row.ownerEpoch, "lastSequence" to row.lastSequence, "lastChain" to row.lastChain)))
            }
            "decisions" -> {
                val after = queryValue(query, "afterSequence").ifBlank { "0" }.toLongOrNull()?.takeIf { it >= 0 } ?: return error(400, "BAD_QUERY", "afterSequence must be a non-negative integer")
                val limit = queryValue(query, "limit").ifBlank { MAX_DECISIONS.toString() }.toIntOrNull()?.takeIf { it in 1..MAX_DECISIONS } ?: return error(400, "BAD_QUERY", "limit must be 1..$MAX_DECISIONS")
                s.decisions(binding, after, limit).let { respond("decisions", it.progress, it.rows.map(::decision)) }
            }
            "positions" -> s.positions(binding, accounts).let { respond("positions", it.progress, it.rows.map(::position)) }
            "obligations" -> s.obligations(binding, accounts).let { respond("obligations", it.progress, it.rows.map(::obligation)) }
            else -> s.attempts(binding, accounts).let { respond("attempts", it.progress, it.rows.map(::attempt)) }
        }
    }

    private fun decision(r: FinanceDecisionRow) = mapOf("sequence" to r.sequence, "action" to mapOf("namespace" to r.actionNamespace, "domain" to r.actionDomain, "id" to r.actionId),
        "disposition" to r.disposition, "inputDigest" to r.inputDigest, "priorChain" to r.priorChain, "checksum" to r.checksum)
    private fun position(r: FinancePositionRow) = mapOf("accountId" to r.accountId, "assetId" to r.assetId, "amount" to r.amount)
    private fun obligation(r: FinanceObligationRow) = mapOf("runId" to r.runId, "venueSessionId" to r.venueSessionId, "instrumentId" to r.instrumentId, "executionId" to r.executionId,
        "buyerAccountId" to r.buyerAccountId, "sellerAccountId" to r.sellerAccountId, "quantity" to r.quantity, "priceNanos" to r.priceNanos,
        "status" to r.status, "cashResidual" to r.cashResidual, "shareResidual" to r.shareResidual)
    private fun attempt(r: FinanceAttemptRow) = mapOf("runId" to r.runId, "venueSessionId" to r.venueSessionId, "instrumentId" to r.instrumentId, "executionId" to r.executionId,
        "attempt" to r.attempt, "disposition" to r.disposition)
}
