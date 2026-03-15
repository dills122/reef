# Proto Contracts

This directory now contains the first draft runtime-to-engine contract in [`order_execution.proto`](./order_execution.proto).

Current scope:

- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `OrderAccepted`
- `OrderRejected`
- `ExecutionCreated`
- `TradeCreated`
- `SubmitOrderResult`

Current usage model:

- the `.proto` file is the canonical contract draft
- the first runnable implementation uses HTTP JSON with equivalent shapes
- protobuf generation can be added once the Kotlin and Go service toolchains are fully bootstrapped

Contract rules:

- include stable identifiers
- include actor and correlation metadata
- avoid floating-point price and quantity fields
- version messages deliberately
