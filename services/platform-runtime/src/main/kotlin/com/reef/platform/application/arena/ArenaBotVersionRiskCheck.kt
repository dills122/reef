package com.reef.platform.application.arena

import com.reef.platform.api.AccountRiskCheck
import com.reef.platform.api.AccountRiskCheckRequest
import com.reef.platform.api.AccountRiskCheckResult
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.api.AllowAllAccountRiskCheck

class ArenaBotVersionRiskCheck(
    private val store: ArenaBotRegistryStore,
    private val delegate: AccountRiskCheck = AllowAllAccountRiskCheck()
) : AccountRiskCheck {
    override fun evaluate(request: AccountRiskCheckRequest): AccountRiskCheckResult {
        val botId = request.botId
        val botVersion = request.botVersion
        if (botId.isNotBlank() && botVersion.isNotBlank()) {
            val version = store.version(botId, botVersion)
            if (version != null && version.status !in RuntimeAllowedStatuses) {
                return AccountRiskCheckResult(
                    decision = AccountRiskDecision.DISABLED_BOT,
                    message = "bot version is ${version.status.name}: $botId/$botVersion"
                )
            }
        }
        return delegate.evaluate(request)
    }

    private companion object {
        val RuntimeAllowedStatuses = setOf(
            ArenaBotVersionStatus.Approved,
            ArenaBotVersionStatus.Active
        )
    }
}
