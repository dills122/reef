# Calcify arena throughput and completion targets

October 11, 2026. **Proposed measurement contract; numeric targets await product
adoption and empirical qualification.** Owner clarified required boundary:
orders enter through user/bot API, match, pass through Calcify, and become visible
as completed executions awaiting settlement. One server; larger size allowed when
measured. Sessions last 5, 15, 30 or 60 minutes.

**Scope update, October 16, 2026 (owner):** the sessions that matter now are **15 to 30 minutes** (30 is the top end; 15 for runs whose records are kept; 5 is trivial); 60 minutes is parked, so the 60m rows and the 60m proof step below are kept as arithmetic and history, not as a current target. The delivery rate is **10,000 booked trades/s**; 15 to 20k/s is later work. Component-level soaks of the finance lane and projection at 10k trades/s for 15 minutes now exist (see the [program status update](CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md#0-update-october-16-2026-capacity-track-goal-10000-booked-tradess-for-15-to-30-minutes)); the integrated boundary below (API, engine, resolver, finance, projection) has not been run end to end and nothing here is adopted or qualified.

Owner selected immediate reuse of booked unsettled proceeds under explicit
risk/credit limits for first arena profile. [Credit policy proposal](CALCIFY_ARENA_CREDIT_POLICY.md)
now defines limits and failure rules with small executable proofs. Numeric limits
and latency targets still need calibration/adoption; runtime policy is unchanged.

**Recommend 10,000 distinct API-visible, booked trades/s, with p99 visibility
within 1s; eligible settlement p99 within 5s; final session reconciliation within
30s of close.** Settlement delay may vary by policy, but technical backlog must
stay bounded. This extends earlier capture-only target; it does not retroactively
change [October 11 proofs](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md).

## Completion boundary

Count one authoritative matcher execution/trade identity once. Buyer and seller
fills are two views of one trade. Partial fills can create several trades from one
order; orders, commands, Kafka records, obligations and ledger postings are separate
units. Two fresh orders producing one trade require about 20,000 new-order commands/s
at 10,000 trades/s, before cancels, modifications or retries.

| Milestone | Required evidence | What users may infer |
| --- | --- | --- |
| Accepted | Configured durable ingress acknowledgement | Command accepted; no promise of a fill |
| Executed | Durable matching result and stable execution identity | Match happened; unfilled order remainder may remain open |
| Context ready | Calcify verified source context and provenance | Capture complete; no financial completion implied |
| Booked | Durable post-trade acceptance, obligation/exposure and applicable reservation or credit decision | Trade recognized in trading state; settlement may be pending |
| Trading view visible | Both entitled participants can read execution and consistent booked account effects through normal API | **10k goalpost**; explicit settlement state and freshness |
| Settled | Required cash/security legs, ledger and obligation finality committed | Settlement financially complete |
| Settlement visible | Corresponding finality and balances visible at same API progress cut | User-facing settlement complete |

`BOOKED` is proposed vocabulary, not an existing public enum. A fill waiting for
context or booking must remain visible as processing if exposed early; it cannot
be counted at final trading-view boundary. A trade already settled also counts;
there is no requirement to force a visible pending transition. Business exceptions
remain explicit and do not erase executed trades.

Proposed API bundle: run/session + execution/trade ID, order IDs, price, quantity,
participant scope, execution state, booking state, settlement state/pending reason,
booked positions/receivables/payables, settled balances, available-to-trade value,
policy version and committed progress token. Use domain sequence/vector and one
consistent read snapshot for coupled values; a fresh fill joined to stale buying
power is not a complete bundle. Public market tape remains a separate read model.

This extends [RFC completion vocabulary and SQL boundary](CALCIFY_SYSTEM_ARCHITECTURE_RFC.md).
It does not select Kafka versus SQL financial authority or require another service.

## Research basis

Sources reviewed October 11. They support measurement and state separation;
**none supplies Reef's numeric latency targets**.

| Primary source | Relevant practice | Application here |
| --- | --- | --- |
| [SEC settlement bulletin](https://www.investor.gov/introduction-investing/general-resources/news-alerts/alerts-bulletins/investor-bulletins/new-t1-settlement-cycle-what-investors-need-know-investor-bulletin) | Most applicable U.S. broker-dealer transactions moved to T+1 on May 28, 2024 | Scheduled settlement date differs from software processing time; T+2/T+3 is not current general U.S. baseline |
| [Alpaca trade updates](https://docs.alpaca.markets/us/docs/websocket-streaming) | Fill/partial-fill updates carry execution quantity, price and resulting position; settlement calculations can remain pending | Distinguish execution, trading position and settlement in API vocabulary; no latency promise borrowed |
| [Google SRE SLO design](https://sre.google/workbook/implementing-slos/) | Define user outcome separately from metric implementation; distinguish latency, coverage, correctness and freshness | Throughput counter alone cannot qualify user experience |
| [Google SRE pipelines](https://sre.google/workbook/data-processing/) | Measure percentage processed within deadline and oldest unprocessed age; use known expected results | Track tail age and exact correctness alongside percentiles |
| [Grafana open versus closed load](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/) and [dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/) | Waiting for responses can silently reduce offered load; unsent work must be counted | Open-loop schedule, explicit drops and scheduled-to-visible latency |
| [TigerBeetle two-phase transfers](https://docs.tigerbeetle.com/coding/two-phase-transfers/) | Pending reservations and posted transfers are distinct; reservation enforces balance invariants before final posting | Borrow explicit pending/posted accounting distinction, not a new database decision or credit policy |

Reef already distinguishes `instant-post-trade` from `ops-realistic` in
[settlement strategy](../SETTLEMENT_CLEARING_STRATEGY.md). Keep one lifecycle with
versioned timing policies. Simulated T+1 may pass quickly through deterministic
clock advancement; never equate a simulated day with an allowed day of CPU backlog.

## Proposed targets for first arena profile

Normal-operation targets below apply to fully funded, automatically eligible gross
trades with no injected business failures. Use same-server matching, broker,
Calcify booking/settlement, SQL, API and operational observation. Measure real
captured trades throughout. Separate deliberate fault and business-delay profiles.

| Measure | Proposed release gate | Reason |
| --- | --- | --- |
| Trading-view throughput | At least 10,000 distinct completed trades/s by fixed deadlines | Owner's requested product boundary |
| Order-pair schedule to trading-view visibility | p99 ≤1s; p99.9 ≤2s; no trade >5s in normal qualification | One-second feedback target with explicit tail limit |
| Eligible settlement to settlement-visible bundle | p99 ≤5s; oldest eligible unfinished trade ≤30s | Allow batching while bounding technical lag |
| Eligible settlement backlog | Warn above 50,000 trades or oldest age >5s; fail qualification above 300,000 or age >30s | Initial guardrails at 10k/s; enforce bytes/resources separately |
| Trading-view completion after admission cutoff | Every accepted pre-cutoff execution visible by close +5s | No invisible tail after game ends |
| Session finalization | By close +30s: required decisions/settlements, SQL/API and final score reconciled to sealed cut | 10% maximum extra time for a 5-minute game, below 1% for an hour |
| Correctness | Zero lost or duplicate business effects, wrong participant effects, unexplained gaps, overspending or replay mismatch | Timing error budgets never excuse financial errors |
| Serving already committed bundles | API p99 ≤250ms and ≥99.9% successful eligible reads under frozen concurrent read mix | Distinguish fast response from fresh data |

One-second visibility is an initial product choice: current
[local arena mode](../../packages/scenario-definitions/arena/equity-multi-local.v1.json)
has 1000ms ticks, but house liquidity wakes at 250ms. It does **not** guarantee
next-tick visibility or qualify a sub-second strategy. Record ticks-to-observe and
bot decision cadence; a faster ranked mode needs a tighter separate profile.
Neither observed capture speed nor another platform's example selects these SLOs.

Percentiles use all eligible items, including timeout/missing items as deadline
misses, both session-wide and in fixed 60s input cohorts. Also report per participant,
lane and instrument where sample size permits; global averages cannot hide a stuck
lane. Hard age applies to outstanding work, including work absent from completed
latency histograms. At 10k/s, p99 still permits 100 late trades/s, hence p99.9 and
oldest-age guards. Backlog thresholds are chosen limits, not a conversion of p99
to queue length; count, age, notional exposure and bytes all matter.

## Settlement clocks and gameplay

Record three independent durations:

1. **Policy wait:** simulated execution to policy due time, including calendars,
   affirmation, funding or deliberately injected failures.
2. **Technical processing:** wall time from when ordered facts make work eligible
   to authoritative settlement commit. Start at durable prerequisite availability,
   not when a slow worker finally dequeues it. Include upstream clock/admission lag.
3. **Visibility delay:** authoritative commit to corresponding SQL/API visibility.

The 5s/30s settlement targets include technical processing plus visibility.
Instant profile marks fully funded trades eligible at booked obligation creation;
also measure execution-to-settlement so slow booking cannot hide behind that clock.
Policy wait is excluded from technical lag, but reported with reason, population,
due time and age. Repeated failed attempts cannot reset original overdue age.
At any cut, reconcile booked trades across settled, eligible pending, policy-not-due
and blocked business dispositions. Netting may reduce obligation count; retain exact
coverage back to every gross trade and report both units.

**Owner-selected arena policy:** permit immediate reuse of booked unsettled proceeds
under explicit deterministic risk/credit limits. Immediate means available once
authoritative booking and credit decision commit, without waiting for settlement.
Expose settled, unsettled, reserved and available amounts separately. Risk owner
must enforce limits across open orders and fills; stale SQL cannot authorize
spending. This choice puts booking, reservations and credit accounting inside
10k path. Settlement remains asynchronous, but growing unsettled exposure can
exhaust credit and constrain further trading; monitor utilization and credit-based
admission refusals alongside settlement lag.

[Credit policy proposal](CALCIFY_ARENA_CREDIT_POLICY.md) now specifies same-run/USD
eligibility, 25% opening-cash account caps, 10% aggregate prefunded house pool,
reservation priority and failure rules. Small reference proofs cover reuse,
double spending and process crash/replay; they do not qualify runtime integration.
Use that policy's frozen version for next integrated proof. Production grants must
precede matching, consume capacity across open orders/fills and survive recovery.
No purchased-security reuse, unlimited credit or implicit adoption of current
no-credit fixture. Caps remain proposed, requiring notional/concentration sweeps.

Do not alter score-v1 economics implicitly. Existing
[arena score policy](../../packages/scenario-definitions/arena/scoring/score-v1.json)
locks policy through score publication. Freeze final valuation marks at session's
logical cutoff; wall-clock drain must not change price marks, actor ordering or
credit eligibility. Provisional results can appear earlier, with final status only
after declared score inputs and coverage reconcile.

## Session and recovery budgets

For constant gross eligible arrival rate λ, settlement service μ, session T and
post-close service μ_close, starting from an empty queue:

```text
B_end = max(0, (λ - μ) × T)
post_close_drain = B_end / μ_close
if μ_close = μ: μ_min_for_flush_D = λ × T / (T + D)
after zero-service pause P with arrivals continuing:
B_fault = λ × P
catchup_time = B_fault / (μ_recovery - λ), requiring μ_recovery > λ
```

Fluid arithmetic, not measured capacity; assumes immediately eligible homogeneous
work, constant service, no startup cost or extra finalization work. For real runs,
integrate actual arrivals/service and measure close capacity with real read/scoring
and next-session overlap. The lower bounds below do not satisfy p99 by themselves.

| Session | Trades at 10k/s | Two-fresh-order commands | Minimum μ for 30s flush if μ_close=μ | Flush at μ=9k/s |
| --- | ---: | ---: | ---: | ---: |
| 5m | 3,000,000 | 6,000,000 | 9,090.91/s | 33.33s |
| 15m | 9,000,000 | 18,000,000 | 9,677.42/s | 100s |
| 30m | 18,000,000 | 36,000,000 | 9,836.07/s | 200s |
| 60m | 36,000,000 | 72,000,000 | 9,917.36/s | 400s |

At μ=5k/s, drain equals entire session duration. At μ=10k/s, initial backlog never
shrinks while arrivals continue. Consequently, settlement may be seconds behind
but needs roughly matching long-run capacity plus **measured surplus** for recovery.
Batching/netting changes cost per trade; it does not eliminate coverage obligation.

Proposed retained-state process-restart target: fresh committed progress within
10s of fault, normal age/backlog envelope restored within 30s of fault while
10k arrivals/s continue. A 10s zero-service interval creates 100k backlog;
remaining 20s requires 15k/s service to return to zero, before safety margin.
This is a candidate recovery budget, not demonstrated capacity. Report full-run
latency misses as well as fault-specific metrics; do not delete fault windows.
If admission pauses, report reduced delivered throughput and changed game timing;
that arm cannot claim uninterrupted 10k.

Cold local-state loss remains separate destructive-state recovery profile:
proposed 60s catch-up target; ranked play becomes explicitly paused/unqualified
until freshness returns. Retained-state and cold recovery must report actual
restore/catch-up frontiers. One-server host loss is a restore/availability contract,
not covered by process-restart targets. Do not add standby or enlarge server solely
from arithmetic; first measure aged recovery service and resource deficit.

State lifetime follows session population. A 60m session is 12 times 5m population,
and fresh paired orders create roughly twice accepted-ID growth of previous
standing-maker proof. At control's observed 1,988.29 output payload bytes/trade,
36m trades would emit about 71.58GB in that encoding alone, before source logs,
changelogs, SQL indexes/WAL and recovery copies. This is encoding-volume arithmetic,
not a RAM or physical disk forecast. Freeze retention and recovery reserve before
long run. Prove consecutive sessions with certified state retirement; no ad hoc
identity eviction, order-ID reuse or deletion of audit history to pass capacity.

## Measuring the complete path

Use one run manifest with source/config hashes, server CPU/RAM/disk, actual durable
topic/DB settings, finite source profile, identities, workload, policy versions,
read mix, observer overhead, deadlines and failure budget. Distinguish external
load generator from application server; include bot compute in budget if hosted on
same server. No off-host broker, DB, financial owner or projection hidden in claim.

Measure milestones `scheduled`, `sent`, `ingressDurable`, `matchDurable`,
`contextCommitted`, `bookingCommitted`, `tradingViewCommitted`, `eligible`,
`settlementCommitted`, `settlementViewCommitted`, `apiObserved`. Correlate by stable
run/session/execution identity; store detail in bounded evidence, not high-cardinality
metric labels. Record both API participants, with completion at later observation.

Wall latency uses monotonic clocks. Client schedule and API receipt on same
observer provide direct end-to-end measurement. Server-stage subtraction requires
validated common clock or measured clock-error bounds across processes/VMs.
Business `occurredAt`/simulated `acceptedAt` is not a performance clock. For two
fresh orders use scheduled release of complete pair as primary start; separately
report each order's submission latency. For ordinary resting orders report
match-to-visible, excluding intended time waiting for a counterparty.

Full-population durable SQL bundle counts plus exact per-partition/domain coverage
prove projected throughput. For API eligibility, expose certified coverage through
normal authenticated API: every trade covered by returned frontier must have its
complete bundle committed, with exact membership and no gaps. Poll each active
scope every 100ms; receipt of covering frontier gives a conservative visibility
upper bound for every included trade. Validate this guarantee with full post-run
SQL/source parity and black-box detail reads. A SQL counter alone does not qualify
API visibility; absent certified coverage, report sampled API evidence as sampled
and leave full-population API gate unproven.

Additional detail-read fixture: deterministic 1% execution-ID sample, stratified
across lanes and participants, polled in bounded multi-ID requests every 100ms
until deadline; record both participants' first successful responses. Report sample
size, selection and observed quantiles separately from whole-cohort coverage bounds;
do not invent statistical confidence from deterministic samples. Probe API load and
production read load both belong in resource budget. Existing
bounded recent-fill lists cannot prove visibility of every trade at 10k/s: use a
cursor/history or multi-ID contract with certified prefix and gap detection.

If a trade is absent at t1 and visible at t2, visibility lies in (t1,t2]. Use t2
for conservative pass decision; wide or missing brackets yield **unproven**, not
invented exact latency. Poll intervals must resolve intended threshold. Cold reads,
cache behavior, response latency/errors and actual freshness all get separate
metrics. `RUNNING`, highest offset seen and successful HTTP 200 do not prove coverage.

Proposed qualification fixture uses 10,500 scheduled matching pairs/s, two fresh
orders per pair, no scheduler drops and no unexpected rejects/timeouts. This small
overdrive permits literal ≥10k output/s before a finite deadline despite pipeline
latency. It is not a measured maximum or 5% recovery reserve. All extra offered
work remains in latency/correctness denominators.

Let V(t) count unique **timed-cohort** trades at complete SQL/API bundle boundary
by wall time t, excluding warmup. Require V(stop)/T ≥10,000 and every rolling 60s
output window, evaluated each 10s within timed interval, ≥600,000. No post-stop
drain enters these rates. In addition, audit every expected execution and both
party effects, all accepted commands and terminal dispositions through close.
An exact-10k-arrival arm is useful for user workload: report in-window output and
final tail separately, since nonzero latency can leave final items after stop.
Never divide eventual drained count by timed input duration and call it in-window
throughput. Closed-loop real-bot sessions are additional gameplay tests, not a
substitute for open-loop capacity proof.

Every stage reports completed count, outstanding count/bytes, oldest outstanding
age, 60s arrival/service rates, backlog slope and deadline coverage. Verdict is
**pass** only when conservative bounds satisfy gates, **fail** when a violation
is established, otherwise **unproven**. Keep these states distinct in scorecard.

Initial reproducible read mix: 100 participants, each 5 authenticated reads/s
(2 own fills/history, 2 trading account/position, 1 market view), plus 10 operator
session/progress reads/s, bounded pages of 100 and measurement probes. This 510rps
mix is a proposed test fixture, not established production demand. Freeze endpoints,
indices, pagination, poll/stream fanout and query cost; change profile name when
bot count/cadence changes. Uniform 64-instrument and single hot-instrument/hot-account
variants expose different limits. Add deterministic cancels/modifies/partial fills
as separately named gameplay mix; report additional command rate and actual fills.

Close session by durably stopping new admission, recording accepted-command cut,
then recording matching/source membership, financial terminal dispositions, SQL
bundle coverage and score cut. Finalize only when all required cuts reconcile.
For instant fully funded capacity arm, no unsettled work may remain. For realistic
or failure scenarios, final report may include explicitly expected not-due/blocked
obligations, or advance deterministic clock via normal commands as policy directs;
never force-settle or relabel pending as settled to satisfy flush. Independent
full replay may run later as qualification audit; runtime close must already have
complete coverage/reconciliation, not defer its own proof. Physical compaction
may continue within next-session resource reserve; logical close is not log deletion.

## Current evidence and implementation gaps

Source baseline `b9a8756e8d0efc5e8b665a5c62da1ab064d01539` plus preserved local
research changes; no production edits during this investigation. Structural graph
generation `2026-10-11T16:34:40Z`, exact cited source paths metadata-match; source
reads verified material claims. Graph edges are discovery aids, not complete wiring
proof. Inspect concurrent branches separately before implementation.

| Finding | Evidence | Consequence |
| --- | --- | --- |
| Context throughput passes without cold restart | [Matched control](../research/CALCIFY_MATCHED_CONTROL_2026-10-11.md): 10,321.21–10,349.52/s, 300s, 64 standing makers; financial input independent | Useful component pass; new trading-view gate **unqualified** |
| Cold recovery misses capture target | [Treatment](../research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md): 8,106.99–8,145.11/s; 29.12s drain upper bound | Neither exact per-trade latency nor integrated session flush evidence |
| Existing trade JSON carries execution facts | [PlatformApi](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt), `TradeCreated.toMap`, `orderWithStatus`; [model](../../services/platform-runtime/src/main/kotlin/com/reef/platform/domain/Models.kt) | No booking/settlement/progress fields in this trade shape; define versioned bundle |
| Participant fills exist, global recent tape is bounded | [HTTP routes](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt), `/api/v1/orders/fills`; [SQL](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt), `recentTrades` | Reuse public auth/scope, add provable history/progress semantics; global recent tape alone cannot qualify |
| Settlement score distinguishes pending, failed and settled | [SettlementScoreProjection](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementScoreProjection.kt) | Useful state vocabulary; not evidence this projection or arena scoring sustains 10k |
| Financial proof remains separate and below goal | [Financial checkpoint](../evidence/calcify-financial-sprint1/README.md): prior empirical 1,051.73 settled/s versus 2,500/s target under different profile | New near-10k settlement service is a substantial open gate, not justified by synthetic contender totals |

## Proof order and decision points

1. Freeze completion fields, selected reuse policy's limits/rules and proposed targets. Add exact
   booking/SQL/API milestones and bounded observer to existing harness. Test small
   real order → capture → booking → settlement → SQL/API path with full replay.
2. Run paired-order integrated rate ladder 1k, 2.5k, 5k, 10k arrivals/s; then
   10.5k qualification fixture for 5m. Keep standing-maker arm as diagnostic control.
   Record service and age at every boundary to locate first growing queue.
3. Prove 15m and 30m (60m parked by the owner on October 16) only after state/disk budgets support exact population.
   Repeat qualification on unchanged profile, retaining failures; at least three
   runs at 5m and maximum claimed duration before release claim. Prove consecutive
   sessions and explicit close/read load. Earlier 10k/600s and 7.5k/900s targets
   remain historical/diagnostic; duration names cannot imply untested hour capacity.
4. Keep planned retained-state restart comparison as useful capture diagnostic;
   repeat recovery on integrated profile with declared age, 10k continuing arrivals,
   start/progress/caught-up frontiers and matched no-fault control. Test SQL
   projector interruption and settlement-worker interruption separately before
   combined interference. Separate business-failure scenarios from capacity fixture.

Diagnosis: rising match queue → ingress/matcher; context gap → capture/resolver;
booked gap → financial admission/owner; SQL gap → materializer/storage; fresh SQL
but slow API → serving/read contention; eligible settlement age → financial service;
post-close-only delay → barriers, scoring or state retirement. Diagnose per-domain
skew before totals or hardware. Fail unsupported profiles explicitly rather than
silently lowering arrival rate, weakening durable acceptance or increasing thresholds.

Completion/retention pass: proposal, RFC, work board, navigation, settlement strategy,
performance learnings and throughput ledger updated. Arithmetic above checked with Python; no new load
run or runtime qualification. Existing originals and archive receipts unchanged.
No archive move: current proposal adds an operating contract; earlier reports remain
needed evidence for their narrower scopes. Owner's arena reuse direction is recorded;
accepted ADRs, public contracts, runtime behavior, deployed score/credit policy and
production authority remain unchanged.
