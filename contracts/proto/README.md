# Proto Contracts

This directory will hold protobuf definitions once the first runtime-to-engine contract is formalized.

The first planned contract slice should cover:

- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `OrderAccepted`
- `OrderRejected`
- `ExecutionCreated`
- `TradeCreated`

Contract rules:

- include stable identifiers
- include actor and correlation metadata
- avoid floating-point price and quantity fields
- version messages deliberately
