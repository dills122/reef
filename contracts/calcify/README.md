# Calcify Phase 1 link contract

Version 1 uses fixed-size, big-endian binary records. Kafka keys are null. Each output record uses same numeric partition as input. No trade economics, trade ID, batch ID, checksum, or duplicate ID field is stored in link.

| Record | Bytes | Layout |
| --- | ---: | --- |
| MatchCommitment | 21 | version `u8=1`; sourceGeneration `i32`; sourcePartition `i32`; sourceOffset `i64`; flattened tradeOrdinal `i32` |
| CommitmentVerificationPassed | 23 | exact 21-byte commitment link; stub policyVersion `u16=1` |

All identity components must be nonnegative except sourceGeneration, which must be positive. Four source components are commitment ID and source pointer. Trade ordinal starts at zero and increments across every nested `TradeCreated` in outcome order within one committed `VenueEventBatch`. Zero-trade batch has no link; extractor still commits source offset transactionally. Verifier pass records only link and policy version. Policy 1 checks link shape and partition; it makes no business-eligibility or settlement claim.

Wire version or field-layout changes require new contract version and stream. Source generation increments on venue-event topic recreation. PostgreSQL `runtime.calcify_source_generations` registry maps generation to topic name and binds its broker topic UUID on first extractor start; extractor refuses unregistered or mismatched generation. Operators must stop extractor before topic recreation, register next generation, then restart it; same-generation startup or reassignment refuses changed broker topic UUID.

Fixture mapping, with sourceGeneration 1 and sourcePartition 2:

| Source fixture | Source offset | Nested trade counts by outcome | Emitted ordinals | Payload bytes per link |
| --- | ---: | --- | --- | ---: |
| zero | 80 | empty batch | none | 21 |
| one | 81 | 1 | 0 | 21 |
| many | 82 | 2, 0, 3 | 0, 1, 2, 3, 4 | 21 |

`CalcifyContractTest` constructs checksum-valid source fixtures and asserts mapping, wire sizes, round trips, and malformed-input rejection. Contract is deliberately a compact binary link rather than a Protobuf trade payload; original matching facts stay in source event stream.
