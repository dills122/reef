# Calcify arena credit and failure policy

October 11, 2026. **Proposed `arena-receivable-credit-research-v1`; executable
reference proof, not deployed policy.** Owner selected immediate reuse of booked
unsettled proceeds. This proposal supplies concrete limits and failure rules for
that choice. Numeric caps need workload calibration before ranked adoption.
[Arena completion contract](CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) owns 10k API-visible
trades/s and timing gates; this document owns proposed financial admission rules.

## Decision and research basis

Use receivable-backed credit with a **prefunded settlement cash pool**. Reserve
credit before matching, alongside ordinary cash/security holds. Draw cash only
when settlement needs it; incoming proceeds repay advances before becoming free
cash. Credit authorization without liquidity cannot settle an outgoing cash leg.

| Primary source, read October 11 | Evidence borrowed | Reef decision/inference |
| --- | --- | --- |
| [CPSS-IOSCO PFMI](https://www.bis.org/publications/principles-financial-market-infrastructures.pdf), principles 4, 7, 12, 13 | Credit exposure, settlement liquidity, conditional exchange of assets and default procedures need distinct controls | Separate account borrowing cap from funded pool; atomic cash/security settlement; preserve obligations after failure. Engineering analogy, not a claim of regulatory compliance |
| [TigerBeetle pending transfers](https://docs.tigerbeetle.com/coding/two-phase-transfers/) | Pending amounts reserve capacity before posting; resolution is explicit | Open orders and unsettled fills consume capacity; timeout or cancel request cannot silently release it |
| [TigerBeetle requests](https://docs.tigerbeetle.com/coding/requests/) | Ordered execution, stable identities and retries distinguish commit from acknowledgement | Persist decision/identity and business state together; uncertain caller retries same ID |
| [TigerBeetle linked events](https://docs.tigerbeetle.com/coding/linked-events/) | Individual request events may fail independently; linked chains give all-or-nothing effects | Cash, security, credit draw/repay, obligation and reservation changes form one financial transition |

Sources motivate mechanisms; **none establishes Reef's 25%, 10%, 80% or 30s
choices**. No external library code copied. This does not select TigerBeetle or
replace existing financial authority proposal.

## Initial limits

Scope: one run, USD, gross DvP, fixed-point integer minor units, zero fees/interest,
settled-security sales only. Separate accounts and funded pool for each run;
collocated runs cannot share capacity implicitly. All policy/configuration values
freeze at genesis and bind replay. No cross-currency borrowing, shorts, withdrawal,
unsettled-security resale or external receivables in this version.

| Control | Proposed value/rule |
| --- | --- |
| Per competitor credit cap `L` | 25% of frozen opening cash, rounded down to minor unit; zero opening cash means zero credit |
| Aggregate funded pool `F` | 10% of sum of eligible competitors' opening cash, rounded down; explicit house cash transfer at genesis |
| House liquidity | Existing house must fund `F` in addition to its remaining trading needs; insufficient cash rejects genesis. Pool receives no borrowing allowance |
| Eligible collateral | 100% face value of valid booked, unsettled sell receivables in same run/currency, backed by reserved settled securities and admitted buyer funding |
| Exclusions | Unbooked fills, stale/failed receivables, duplicate trades, projected SQL balances and open sell orders grant no credit |
| Utilization | Warn at 80% of either cap; refuse capacity-increasing admission above 100%. Never raise caps automatically to pass throughput |
| Age | At original eligibility age 30s, ordered expiry fact revokes receivable eligibility and freezes affected participants' new orders; late settlement remains allowed |
| Priority | First committed reservation wins within financial domain; no best-effort grants from API replicas. Fair-share scheduling remains unqualified |

Current preview gives each competitor $1m cash. Under this proposal each gets
$250k credit; 100 competitors require an additional **$10m** funded pool. Existing
`preview-zero-fee-v1` has no such transfer: introduce a new versioned economic
policy before implementation. Do not reinterpret subsidy or count pool funding
as competitor profit. Profit cannot increase frozen credit caps.

100% face-value eligibility is deliberately narrow: a closed simulation reserves
the buyer's funding and seller's settled securities before matching. Pool still
bears failure exposure. External counterparties, price collateral or security
reuse require a different policy, haircuts and stress proof; this model gives no
basis for extending eligibility to them.

## Accounting and admission

For each account, let `C` be settled cash, `R` eligible unsettled sell receivables,
`P` unsettled purchase payables, `H` open buy-order holds at maximum executable
notional, and `D` outstanding drawn pool debt. Maintain:

```text
E = D + max(0, P + H - C)
normal account: E <= min(R, L)
all accounts, including frozen: E <= L
sum(E) <= F
pool liquid cash = F - sum(D) >= 0
cash conservation: sum(account settled cash) + pool cash = initial cash
```

`E` includes drawn debt AND undrawn commitments. Checking only debt permits many
orders to promise the same pool cash. A failed receivable may leave `E > R`; retain
exposure and freeze the account instead of erasing debt or asserting it vanished.
Such an exception never permits new admission. Global/account hard caps remain.

Available account budget before shared-pool check is
`max(0, C + max(0, min(R-D, L-D)) - P - H)`. Admission must also reserve the exact
increase in aggregate `E`; a displayed budget is not authorization. API must expose
account headroom and shared-pool constraints separately; another account may win
capacity between read and submit. Full order holds cover quantity × limit price;
market/pegged orders need a frozen executable-price bound or rejection in this
profile. Price improvement releases excess; amendments require atomic replacement
or canonical cancel-and-new, never unreserved expansion.

Partial fill replaces corresponding buy hold with actual payable and sell hold
with delivery obligation. Booked seller receivable grants eligibility only once.
Cancel request changes no hold; same-lane canonical cancellation releases only
unfilled remainder. No automatic TTL release. Settled shares minus open sell holds
and pending deliveries must stay nonnegative.

For settlement amount `N`, use own cash first; draw `max(0, N-C)` from reserved
pool capacity, increasing debt. Atomically exchange cash and securities, retire
payable/receivable and corresponding holds. Seller pays `min(N, seller debt)` back
to pool before retaining remaining cash. Pool commitments already cover this draw,
including after backing receivable fails. Never grant proceeds again at settlement.

Booked net value for scoring includes `C + receivables - payables - D` plus marked
booked security position. Account holds restrict availability, not equity. A failed
receivable's scoring valuation is unresolved until explicit adjudication; no normal
final leaderboard with unreconciled debt/defaults. Existing score-v1 stays unchanged.

## Failure rules

| Trigger | Deterministic disposition |
| --- | --- |
| Order exceeds cash/collateral/account/pool cap | Persist named refusal; no matcher admission or partial hold. Retrying same ID returns same refusal; new attempt needs new ID |
| Fill references missing/insufficient hold | Integrity fault: preserve canonical match, fence affected processing and reconcile. Model's invalid-fill rejection checks fixtures; production cannot discard an already executed trade |
| Broker/SQL/API timeout after submission | Status unknown; retry stable ID. No second grant, automatic refund or hold release |
| Same ID, different payload/policy/run | Conflict; no mutation. Identity retention lasts through run closure and replay retention |
| Sell receivable fails after reuse | Remove eligibility, freeze both trade participants' new orders, retain payable/delivery/debt/pool commitments; already matched downstream trades may settle from reserved pool |
| Receivable reaches 30s age | Record expiry in authoritative sequence; freeze new orders, retain funding and permit settlement. Original overdue age survives repair/retry |
| Frozen account has resting orders | Request canonical cancellations; holds remain until ordered confirmation. In-flight authorized fills still book against existing holds |
| Repair | Explicit authorized evidence-driven repair event; no erase/reverse of downstream final trades, new funds or automatic unfreeze. Resume requires separate event and reconciled limits/exceptions |
| Pool exhaustion | Refuse new credit-consuming orders; existing funded obligations continue. Alert on concentration/utilization; no hidden increase of pool |
| Cash-only account can still trade during pool exhaustion | Permit only when its marginal exposure is zero and account/run admission is open; shared exposure cannot rise |
| Unrecoverable default by session deadline | Session stays `RECONCILIATION_REQUIRED`, ranked finalization withheld. Any loss allocation/write-off is a new versioned operator decision, outside this model |
| Corrupt/torn or committed-prefix-incomplete replay | Fail closed; recover authoritative committed prefix. Do not trim uncertain financial history and resume spending |
| Close | Stop new admission, confirm residual cancels, drain obligations/debt and reconcile to sealed cut; final score only when complete |

Logical/expiry events must be ordered facts; live wall clock is not consulted
during replay. Preserve original due time for metrics. Session drain cannot change
valuation marks or rewrite pre-cut decisions. Authentication/authorization of
repair, resume and freeze events remains a runtime requirement outside model.

## What proofs changed

[Reference model](../../scripts/dev/calcify-financial/research/arena-credit-model.mjs)
and [tests](../../scripts/dev/calcify-financial/research/arena-credit-model.test.mjs)
exercise exact integer state. Compact [verification](../evidence/calcify-arena-credit/verification.json)
records source hashes and raw archive binding.

| Counterexample or review finding | Tightened design |
| --- | --- |
| Two $20 orders each see same $25 receivable | Deduct open-order holds in one serialized decision |
| Two $25 grants compete for $40 pool, neither drawn yet | Reserve aggregate undrawn exposure at admission |
| $25 authorized purchase settles before source proceeds with $0 cash | Fund cash pool explicitly; credit number alone cannot make DvP |
| $25 proceeds settle but receivable remains counted | Retire receivable and repay debt atomically; no $50 buying power |
| Repair reset eligibility timestamp in initial model | Preserve original age; an old repaired receivable cannot regain credit before settling |
| Hash chain validates a cleanly shortened prefix | Bind recovery to authoritative committed head/coverage; hashes alone cannot detect lost tail |

First model run: 29/29 tests passed. Subsequent inspection found age/head gaps above;
expanded tests cover those gaps. Final suite: **34/34**, including **676 ordered
competing-order schedules** and **20 actual child-process SIGKILL cases** across
reservation, booking, advance, repayment and failure, each before write, during
partial write, after fsync/before reply and after reply. Torn records refuse replay;
other cutpoints preserve retry/decision parity. Both settlement orders produce
same financial balances; security holds, partial fill, cancellation, failure,
identity substitution and close checks pass. Deliberate red counterexamples are
alternative unsafe designs, not concealed failing assertions in corrected model.

This is a **single serialized owner reference**, not concurrent replica testing,
production Go/Kotlin/SQL integration, power-loss durability, distributed atomicity
or throughput qualification. Journal fsync covers process crashes on same host;
directory durability, host loss, leader fencing and commit-head recovery need real
storage-path proofs. Full-record torn-write repair remains deliberately unsupported.

## Calibration and architecture consequences

Steady approximation: credit needed ≈ trade rate × average notional × fraction
requiring an advance × advance duration. With 10k trades/s, $1k notional and 10%
credit-funded flow, exposure is about $5m at 5s, $10m at 10s and $30m at 30s.
Thus a $10m pool cannot support that mix at 30s despite satisfying a 30s maximum-age
goal. At 100% credit-funded flow, even 5s requires $50m. These are sensitivity
calculations, not workload measurements or bounds on correlated tail demand.

Record `R/P/H/D/E`, account/pool utilization, largest counterparties, credit refusal
counts/notional, oldest eligible age, debt age and draw/repay rate. For each session
size, sweep notional, credit fraction and concentration, including a hot bot and
all bots near exhaustion. Proposed operational target: p99 utilization below 80%,
no cap-driven drops in configured 10k qualification. A test must report refusals
as lost offered demand, not lower offered rate to fit pool. Tune workload/funding
or reject profile explicitly; never inflate capacity invisibly.

One domain serializes reservation, booking, settlement, cancellation, expiry and
pool ownership; production hot path uses incremental counters, not model's scans
and full-state clones. Reserve before matcher execution. Durable ingress202 still
only acknowledges durable command receipt; distinguish risk acceptance from that
receipt. Bind authorization to run, account, order incarnation, policy, quantity
and price bound; source fills consume that grant exactly once. SQL projection
cannot approve orders. Persist business outcome, journal and progress atomically.

Cross-lane/run deployment requires a proven admission/fill/cancel protocol and
fenced ownership. If global pool contention bottlenecks 10k, test static funded
domain allocations before adding shared concurrent mutation. Unused allocations
cost utilization and fairness; transfer must itself be authoritative. Keep these
costs in the same-server end-to-end proof. Neither present request-local
`PostgresAccountRiskCheck.limitViolation` nor test `FinancialKernel.settle` establishes
this integrated reservation/credit contract.

Next proof: actual API → risk reservation → matcher → Calcify booking → normal API,
then settlement/recovery with same captured trades and credit policy. Add grant
transport, order amendment, crossing lane races, ownership loss and close-barrier
tests before claiming production readiness. Existing 10k capture result remains
useful but does not qualify that path.
