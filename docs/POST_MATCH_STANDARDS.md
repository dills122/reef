# Reef Post-Match Standards (v1)

## Purpose

Define normative implementation standards for post-match workflows so simulator behavior is realistic, deterministic, and auditable.

## External Reality Baseline (U.S. defaults)

Reef is country-agnostic, but default behavior should mirror U.S. institutional equity post-trade conventions:

- standard settlement cycle default: `T+1` for applicable securities transactions.
- same-day allocation/confirmation/affirmation pressure modeled as trade-date operational deadline.
- central-counterparty clearing and netting model before settlement obligation execution.

Reference anchors:
- SEC Rule 15c6-1 and T+1 transition FAQ: [SEC T+1 FAQ](https://www.sec.gov/exams/educationhelpguidesfaqs/t1-faq)
- FINRA implementation notice for T+1 operations: [FINRA final reminder](https://www.finra.org/filing-reporting/technical-notice/final-reminder-t-1-settlement-052224)
- DTCC/NSCC clearing and CNS netting model: [NSCC](https://www.dtcc.com/about/businesses-and-subsidiaries/nscc), [CNS](https://www.dtcc.com/clearing-and-settlement-services/equities-clearing-services/cns)
- DTCC settlement process overview: [Understanding settlement](https://www.dtcc.com/understanding-settlement/index.html)
- settlement/clearing strategy and instant-post-trade profile: [`docs/SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md)
- internal research notes: [`docs/research/POST_MATCH_REFERENCE_NOTES_2026-05.md`](./research/POST_MATCH_REFERENCE_NOTES_2026-05.md)

## Role and Permission Baseline

Minimum personas:

- `venue_operator`
- `buy_side_trader`
- `sell_side_broker`
- `clearing_broker`
- `ccp_operator`
- `custodian_operator`
- `settlement_operator`
- `compliance_observer`
- `system_admin`

Permission model rules:

- every command handler must evaluate `actorId` and resolved actor role.
- privileged operations (calendar updates, policy updates, manual overrides, exception closure) require non-trader operational roles.
- read access for compliance personas should be broad; write access should be constrained.
- role checks must be in application-layer command handlers, not UI-only.

## Calendar and Time Configuration Standard

Calendar configuration must be admin-managed and environment-specific.

Required configuration artifacts:

- `market_calendar` (trading days, sessions, market holidays)
- `bank_calendar` (funding/payment holidays)
- `settlement_cycle_profile` (default `T+1`, configurable)
- `cutoff_profile` (stage-specific timestamps and timezone)

Rules:

- runtime must be timezone-explicit for all deadlines.
- settlement date computation must use configured business calendars, not ad hoc weekday arithmetic.
- each scenario run must persist calendar profile version used at runtime.
- default seed profile should be U.S. market/bank holidays.

## Observability Contract (Non-Negotiable)

Required metadata on every command/event:

- `commandId`
- `traceId`
- `correlationId`
- `causationId`
- `actorId`
- `occurredAt`

Rules:

- cross-service propagation of trace metadata is mandatory.
- each stage must emit queue-state metrics and lifecycle transition events.
- event timelines must support linking:
  `orderId -> executionId -> tradeId -> clearingId -> netBatchId -> settlementObligationId -> exceptionId`.

Minimum stage metrics:

- compare/affirm completion rate
- clearing acceptance/rejection rate
- netting compression ratio (gross vs net)
- settlement completion/fail/aged-fail counts
- exception open-to-resolve latency

## Exception Taxonomy (v1)

Required exception classes:

- `COMPARE_MISMATCH`
- `AFFIRMATION_TIMEOUT`
- `CLEARING_REJECT`
- `NETTING_POLICY_CONFLICT`
- `SETTLEMENT_FAIL`
- `SETTLEMENT_AGED_FAIL`
- `DATA_INTEGRITY_ERROR`

Each exception must include:

- severity: `low|medium|high|critical`
- owning queue/team persona
- SLA target
- allowed repair commands
- required audit comment for manual resolution

## Netting Policy Standard

v1 netting should be deterministic and policy-versioned.

Rules:

- net by participant + instrument + settlement date at minimum.
- preserve gross detail for audit while producing net obligations for settlement intake.
- persist `nettingPolicyVersion` on every netting batch and resultant obligation.
- policy updates must be admin-controlled and versioned.

## Settlement Ledger Standard

Default implementation:

- classic account-style relational ledger for obligations, movements, and balances.

Rules:

- ledger entries are append-only; corrections must be compensating entries.
- operational state machine and ledger postings must stay consistent and trace-linked.
- alternate adapters (event-log or blockchain) must preserve same domain semantics.

## Simulation-Backed Integration Standard

Reef should expose production-shaped integration interfaces while allowing simulator-backed implementations.

Rules:

- adapters should emulate realistic response semantics, failure codes, and timing.
- synthetic personas/banks/accounts are acceptable and preferred for sandbox execution.
- no simulator shortcut may bypass command handlers or domain invariants.

## Engine vs Service Extraction Standard

An engine is complete when it has:

- explicit commands/events
- explicit state machine
- deterministic tests
- queue/read-model visibility
- observability metrics and traces

A separate deployable service is justified only if:

- independent scaling characteristics are proven necessary
- isolation materially reduces blast radius
- operational lifecycle warrants separate deployability
