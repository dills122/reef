package com.reef.platform.domain

data class PostTradeProfile(
    val profileId: String,
    val mode: String,
    val settlementCycle: String,
    val nettingMode: String,
    val ledgerPostingMode: String,
    val policyVersion: Int = 1,
    val active: Boolean = false
)
