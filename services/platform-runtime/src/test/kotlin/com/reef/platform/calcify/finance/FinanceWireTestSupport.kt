package com.reef.platform.calcify.finance

/**
 * `toProto(d, input).toByteArray()` WITHOUT the digest check, for tests and benches that have just made [d] from [input].
 * Kept out of main code: anything that receives a decision and an input from outside must use `toProto` or `recordFromProto`.
 */
internal fun FinanceWire.encodeTrusted(d: Decision, input: FinanceInput): ByteArray = withInput(encode(d), input)
