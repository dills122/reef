# O2 Kotlin alignment

Read-only alignment at accepted O1 `5730b6be1ef1fe702be5f3d5bf752e5233ab9016`.
No product edits, Git mutations, builds or live services. Parent graph Verify used
stale September28 deleted checkout; relevant paths lacked freshness. Current source
fallback used; no exhaustive absence claim. Twelve source hashes in
`kotlin/source-receipt.json`. Manager decisions/canonical binding research remain
proposals until this session freezes seams.

## Recommended bounded shape

Dedicated opt-in finite API/capture composition in single JVM. `Main.kt:10` currently
routes Calcify away from HTTP; normal `defaultBoundary()` builds unrelated runtime,
admin/settlement persistence. Avoid full normal bootstrap for isolated fixture.
Serve same canonical `/api/v1/orders/{submit,modify,cancel}` contracts through focused
finite routes, reuse `StreamCommandEnvelopeBuilder` and `StreamCommandResponse`, and
require real participant-scoped external credentials. All other writes refuse.
Finite profile cannot use normal command-log/accepted-async/direct-engine paths.
Do not describe a fixture-only route as simulator shortcut: simulator uses canonical
public mutation URLs and payloads. Normal composition remains unchanged OFF.

Dedicated startup witness route uses dedicated service token only, even loopback.
Do not reuse `AdminSessionAuth`: current admin paths support cookies/local bypass.
No credential fallback, route/header attestation or registrar secret in runtime.
Route auth runs before SQL/broker query. Temporary private local HTTP seam needs
explicit owner-doc migration posture; inter-service steering otherwise prefers
protobuf/gRPC. Existing manager direction permits this bounded local seam.

## Durable ownership and migration

Place new migration `scripts/dev/db/migrations/boundary/0013_calcify_finite_ingress.sql`.
Registry/attempt/publication accounting belongs to API durable ingress domain, not
`runtime.calcify_source_generations`: existing source registration lazily binds
UUID and `verifyTopic` inserts missing rows, incompatible with immutable finite
opening and restricted runtime. Preserve existing legacy registration behavior.

Proposed tables: `boundary.calcify_finite_bindings` (immutable opening bytes/hash),
`boundary.calcify_finite_runs` (owner/state/counters/epochs/stage and shutdown
receipts), `boundary.calcify_finite_attempts` (<=32 bounded audit rows), and
`boundary.calcify_finite_publications` (<=16 immutable send identities, monotonic
delivery state/position). Existing `boundary.stream_command_intake` row inserted
atomically with finite accepted reservation. No cross-domain FK or runtime DDL.
Identifiers validated, all SQL schema-qualified/indexed; keyed run lock, no history
scan per request. Registration/out-of-band reconciliation may scan bounded history.

Table owner NOLOGIN; migration owner separate. Registrar role may register/release
through dedicated functions; finite runtime gets SELECT snapshots plus EXECUTE
restricted accounting/receipt functions, no direct UPDATE/INSERT on registry/run,
no table ownership/DDL/release privileges. SECURITY DEFINER functions owned by
trusted NOLOGIN role, fixed safe search_path, PUBLIC EXECUTE revoked; roles/grants
fixture-provisioned explicitly, no cluster role creation hidden in normal migration.
Registrar credentials absent runtime env/files. SQL privilege tests must prove
runtime cannot change opening, limits, identity or ACTIVE state. Existing legacy
intake store cannot bypass finite registry/journal; dedicated finite bootstrap
must instantiate only finite adapter. Additional normal processes denied isolated
command topic by actual broker ACL, not naming convention.

## Shared Go/Kotlin seam to freeze

Use exact `canonical-binding-spec.md`: P0 hash `1016a4bf…`, budget hash `6d49b6dd…`,
binding786 bytes/hash `1fe1749dba2b35803eabb41fc1f9106477147a2ad9966ecf2087eb5f78dc2c8a`.
Real opening values come from registrar broker reads, not synthetic fixture.
New shared fixtures under `contracts/calcify/finite-binding-v1/`: opening JSON,
canonical hex, maximum16-entry startup JSON and invalid vectors. Owners each
recompute identities from decoded fields. Go comparator uses Java unsigned UTF16
code-unit order; valid Unicode scalars only; no normalization. Topic UUID canonical
22-char Kafka spelling, no zero/padding. All large quantities decimal strings.

GET `/internal/calcify/finite-bindings/{digest}?nonce=...`: exact root
`schema,nonce,bindingDigest,opening,run,cuts,journal`; no raw command payload/key/
subject/route. Headers <=8192 measured bytes, each journal object <=1024, <=16 rows,
wire <=32768, SQL opening <=16384. Single object plus EOF; duplicate/unknown/missing
fields, lone surrogates, malformed UTF8, wrong numeric types refuse. Generic
`JsonCodec.parseObject` has no strict duplicate/trailing detection; finite adapter
must strict-validate bytes before shared canonical envelope builder. No shared
JsonCodec semantic change needed.

`FiniteOpening.toCaptureBinding()` constructs existing O1 `FiniteLifecycleBinding`
with actual full opening digest. Model digest is opaque today; do not relabel old
O1 fixture or alter frozen budget/P0 bytes. Startup cuts queried from actual broker
and stopped SQL snapshot; this observation alone is not permanent ownership or
stage readiness. Operator release separately CASes same binding+owner epoch,
actual engine/capture receipt hashes/cuts, no unresolved publication; stale receipt
fails. No automatic activation/config boolean.

## Intake state machine and ACK boundary

Current `StreamCommandIntake.kt:204` insert autocommit; conflict lookup and mark
separate. HTTP `PlatformHttpServer.kt:2638` unpublished Replay falls through into
publisher. `:2659` timeout does not cancel send, and late ACK marks asynchronously.
Finite implementation must not reuse that retry policy.

Authenticated syntactically scoped request: one SQL keyed transaction charges
eligible attempt, records rejection/retry/conflict, validates bound parties/lane/
policy/payload bytes, reserves intake/publication row and closure capacity. Scoped
guardrail refusals bounded inside finite ledger, no separate unbounded rejection
audit. Auth/syntax failures before known scope append no finite history. At cap
STOPPED latches; later bounded refusal appends/sends nothing. Published known
retry after STOPPED is read-only receipt lookup, no new audit charge.

Before producer invocation: atomically verify current owner epoch, ACTIVE state,
reserve possible-publication bytes and persist PUBLISHING. Every actual application
send exactly once. Kafka internal idempotent retries remain same send operation.
Only exact positive ACK plus durable PUBLISHED marker on same request permits202.
Marker failure => fail closed, never success via asynchronous marker queue.
Timeout/general producer failure => durable UNKNOWN+STOPPED before503. Failure to
persist stop also latches local fence and refuses further reservations. Late exact
ACK changes UNKNOWN/PUBLISHING to PUBLISHED monotonically, same exact position;
conflicting ACK faults. No refund/resend/reopen. Partition0 offset0 is valid ACK,
encoded streamSequence1. SQLepoch fences not-yet-authorized operations; not Kafka
epoch or revocation of previously authorized sends.

Clean restart: STOPPED, drain every authorized send/callback and marker, bounded
flush/close, exact journal/exit receipt; actual old JVM exits before replacement.
Cold crash positive unique retained record may repair PUBLISHED without send;
absence stays UNKNOWN+STOPPED. New owner/producer cannot certify old absence.

## Managed capture restore

O1 processor reads real store inside `init` but receives externally supplied
`FiniteLifecycleStartCut.Restore(checkpoint)`; wrapper must obtain restored store
checkpoint there instead of fabricating external checkpoint. Add explicit verified
startup hook consumed at init, preserving existing model topology/cut behavior.
Genesis only immutable stopped fresh opening, actual empty five topics, seeded
source group0 read back. Restart never seed/reset application/group/topic or infer
missing state as genesis. Previous activation/nonempty history + missing store
refuses even empty RocksDB iterator.

Verify retained source from genesis through max(resume,retained suffix ends), actual
group cut and every output receipt. Physical holes/control/aborted positions legal;
groupcut greater than checkpoint needs bounded read_committed scan proving no
unrepresented committed record. Groupcut below represented history refuses.
Restored semantic state validation mandatory. Readiness only actual task0 init,
Streams RUNNING and valid nonfault state; RUNNING health alone insufficient. Fault
stops ingress but known admitted source may drain within reserved suffix bounds.

O1 model producer max request132096 cannot transmit maximum state6307840. New live
properties must set producer/changelog/fetch/topic/broker limits to actual maximum
state+overhead, disable auto topic creation, EOSv2/one thread/cache0, bounded buffers
and native memory. Metadata/string checks do not prove restore transactions/fit.

## Narrow proposed leases

Kotlin contract/intake developer first: new `calcify/FiniteBindingContract.kt`,
`calcify/FiniteBindingStore.kt`, `calcify/FiniteIngress.kt`; migration0013; matching
focused tests `FiniteBindingContractTest`, `FiniteBindingStorePostgresTest`,
`FiniteIngressTest`. Shared fixtures manager or separately leased owner only.

Kotlin composition/capture developer next after seam freeze: new
`api/FiniteCalcifyRoutes.kt`, `calcify/FiniteCalcifyBootstrap.kt`,
`calcify/FiniteCaptureStartupVerifier.kt`; Main opt-in dispatch; narrowly leased
capture processor init/topology hook and runtime live properties; focused auth/
bootstrap/history/restore tests. Do not edit reducer/P0/model fixtures. Dedicated
finite publisher may reuse current public interface but needs close/flush/auth/
no-topic-create capability; choose focused new adapter rather than broad legacy
transport edits unless manager leases exact seam. No PlatformHttpServer,
AdminSessionAuth or CalcifySourceRegistration edits recommended initially.

Combined O2 self-audit/test matrix: canonical goldens+Unicode/strict parsing/caps;
real SQL last-token concurrency, immutable opening, privilege/release/epoch tests;
same-request202 gating/timeout-lateACK/marker failure/no blindretry; auth+alternate
route refusals; missing/recreated history and actual group cut holes; managed state
missing/output mismatch; known source closure after stop. Live proof requires real
Postgres/broker auth/ACL/transactions and actual process restart after compile.
No resource/build/live lease currently held; no claimed test pass.

## Decisions and flags

Manager freeze required: shared fixture paths/owner, exact SQL function/interface,
dedicated public finite composition and temporary private startup HTTP posture,
finite transport ownership and restored-store hook. These are bounded owner choices
under prior direction, not user permission requests.

Concerns: O2 includes registry/intake/auth/brokerACL/matcher/capture/restart/resource
proof;120-minute target optimistic. Keep complete scoped gates; never call contract
module alone O2 complete. Real Streams restore timing/cut semantics and native
memory fit are potential research triggers. Automatic distributed release,
absence-resend/transactional command pivot or inability to enforce credentials/
permissions require bounded spike and manager replan before product alternative.
O3 remains blocked. O2 combined review cap3, O1 cap exhausted; do not redesign
accepted O1 semantics or silently turn targeted hook into fourth O1 review.

Retention no-op: active alignment needed for implementation; manager owns eventual
documentation/board/evidence retention pass.
