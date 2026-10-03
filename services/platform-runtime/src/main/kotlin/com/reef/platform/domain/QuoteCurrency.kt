package com.reef.platform.domain

import java.util.Currency

// Currency amounts are never normalized or converted at intake.
internal fun validQuoteCurrency(value: String): Boolean =
    value != "XXX" && runCatching { Currency.getInstance(value).currencyCode == value }.getOrDefault(false)
