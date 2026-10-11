# O2 fixture alignment

October 10, 2026. Alignment plus manager-authorized no-network Java packaging
spike only. No tracked edits, broker/SQL/ACL mutation, credentials, live startup
or independent review. O2 acceptance remains unproved. Source base:
`5730b6be1ef1fe702be5f3d5bf752e5233ab9016`.

Inputs: accepted finite source contract; prior O2 manager choices, canonical
binding, activation and nontransactional-fence research; broker-isolation
preflight. Manager graph Verify evidence points to stale September 28 Reef
index/deleted checkout with freshness missing. Current-source fallback used;
no graph absence or exhaustive claim.

## Smallest runnable fixture

New standalone Compose project, one private network, exact four services:
Redpanda, Postgres, finite API/capture JVM, matcher. No shared Compose overlays,
default dev stack, additional capture JVM, Gradle daemon or broker reset.
Controller Bun process and static Go probes sequential; no extra Java probe.

| Resource | Frozen ceiling / implementation |
| --- | --- |
| Broker | 2048 MiB container; 2 CPU; allocator 1 GiB, `--smp 1`, RF1 |
| Postgres | 512 MiB; 1 CPU; shared buffers 64 MB, work memory 4 MB, max connections 32; WAL min 80 MB/max 256 MB; synchronous durability retained |
| JVM | 1024 MiB container; heap `-Xms128m -Xmx768m`; one capture thread, cache zero, bounded direct buffers/fetch/native/thread configuration |
| Matcher | 128 MiB container; static Linux arm64 build; bounded Go allocations measured |
| Controller/probes | 128 MiB aggregate; sequential; no test/build processes during live lease |
| Total | 3840 MiB proposed ceilings, 256 MiB margin below 4 GiB; measured use still required |

Broker digest supplied/rechecked by manager:
`redpandadata/redpanda@sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9`.
PG digest:
`postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685`.
No application image tag accepted without immutable image ID and fresh build
receipt. JVM packaging spike verified cached Linux arm64 nonroot runtime image
`sha256:212c08152521449235b365368902f8bfacf4d8310056f033701d0ea97dd9f2f3`:
one `java -version`, network none, 128 MiB/1 CPU, removed on exit, returned
Temurin **21.0.12+8-LTS**, exit 0. Existing application classes in that image
remain historical and must never run as current O2 proof.

Preferred packaging: fresh `installDist` compiled/tested before lease, mounted
read-only at `/fixture/platform-runtime`; explicit entrypoint invokes that fresh
launcher with cached JRE. Immutable package manifest hashes all launcher/jar/
resource bytes and source revision. Existing image application directory is not
on classpath. Equivalent new fixture Dockerfile may COPY same frozen distribution
onto pinned cached runtime base, without Gradle/network during live lease.

All topics one partition and precreated exact names: command/source/capture,
ownership, Streams changelog `<applicationId>-finite-lifecycle-changelog`, plus
separate empty admin-denial canary. Disable auto creation, compression and write
caching; pin small 8 MiB segment geometry and retention that cannot remove proof
prefix during fixture. Topic/producer/fetch/broker/changelog limits must admit
actual maximum managed-state serialization (default 6307840 bytes) plus protocol
overhead. Merely admitting 131072-byte output envelope does not satisfy this gate.
RF1 remains local correctness scope, no inherited RF3 or capacity claim.

No published broker/PG/admin/direct-engine ports. Optional finite API loopback
port only if manager freezes manual external path; otherwise static helper inside
owned network exercises exact API. Internal DNS names/ports broker:9092,
broker:9644, postgres:5432, runtime:8080, matcher:8081. Probe direct HTTP application
refusal rather than using unreachable endpoint as substitute. Default gRPC
disabled; prove listener inaccessible, plus existing unary/stream finite-fence
unit tests. If gRPC enabled, actual mutation denials become required.

## Requested implementation ownership

- `scripts/dev/calcify-financial/finite-o2-proof.mjs`: Bun supervisor, deterministic
  finite commands, phase control, receipts, exact owned resources and teardown.
- `scripts/dev/calcify-financial/finite-o2-proof.test.mjs`: meaningful supervision
  tests (wrong ownership, missing/raw/stale evidence, secret spill, memory/disk
  abort, UNKNOWN no-resend, bounded output, cleanup errors).
- `scripts/dev/calcify-financial/finite-o2/compose.yml`: standalone four-service
  fixture, digest/image ID inputs, resource caps, private network, volumes,
  read-only fresh artifacts/secrets and bounded logs.
- `scripts/dev/calcify-financial/finite-o2/redpanda.yaml`: authenticated listeners,
  bounded geometry and operator-only admin requirements; no credential bytes.
- `scripts/dev/calcify-financial/finite-o2/fixture.json`: deterministic 12-command
  template/budgets; uniquely substituted run/topic/app IDs before registration.
- `services/matching-engine/cmd/calcify-finite-probe/main.go` and focused tests:
  independent static fixture-only Admin/Kafka/HTTP probe. No normal matcher main,
  app, streamdirect or Kotlin ownership requested. Shared Sarama security seam
  agreed with Go owner; helper owns its restricted adapter, not matching behavior.

SQL migrations/functions remain Kotlin owner's lease. Manager owns plan/ledger,
all Git, source lease, live fixture lease and operator release credential. No
thin Make wrapper needed unless manager requests established user-facing command.

Suggested commands, **proposed, not implemented**:

```
bun scripts/dev/calcify-financial/finite-o2-proof.mjs plan --output <proof-root>
bun scripts/dev/calcify-financial/finite-o2-proof.mjs bootstrap --lease <manager-lease.json> --proof <proof-root> --secrets <private-root>
bun scripts/dev/calcify-financial/finite-o2-proof.mjs prove --lease <manager-lease.json> --proof <proof-root> --secrets <private-root>
bun scripts/dev/calcify-financial/finite-o2-proof.mjs stop --lease <manager-lease.json> --proof <proof-root>
```

No phase starts resources without exact lease + free resource/build inventory.
STOPPED and actual UUID/cut rechecks required before registration/release.

## Secrets and restricted probe transport

Private random fixture secrets only, root under `/private/tmp/`, directory 0700,
files 0600, outside proof/archive. Service receives only own readonly credentials;
operator/registrar never runtime mount/environment. No inherited account secret,
credential argv, `docker inspect Config.Env`, shell trace or config dump.
Setup removes operator mount/access from runtime before activation. Controller
never persists secret-bearing stdin or full helper request. Errors must redact
credential echoes before retaining bounded output; secret scan rejects spill.

Official v26.2 `rpk security user create` documents password as argument, no
password-file/stdin support. Do not use that interface with real secrets. Static
Go helper accepts bounded secret input through stdin and creates users via Admin
HTTP JSON in memory; returns public principal IDs/status only. Broker authentication
bootstrap method and Admin HTTP schema checked against actual pinned image during
leased setup; fail closed if bundled behavior differs. `rpk --config` protected
file remains suitable for non-secret ACL/topic flags/readbacks, but no assumption
that rpk alone can provision users without exposing password argv.

Minimal probe input: version, declared mode, registered project/run/resource IDs,
approved network endpoints, operation-specific bounded fields, auth object from
stdin. Strict duplicate/unknown/trailing-field rejection; input cap 32768 bytes,
<=16 records/members where applicable. Secret-bearing objects never reflected.
Modes limited to fixture creation/readback, bounded topic/history/offset queries,
ACL positive/negative controls, canonical API request, authenticated readiness,
owned-canary admin denial. No arbitrary shell/SQL/query or arbitrary topic mutation.
Canonical topic mutations only bootstrap while STOPPED, no runtime delete/reset.
Default public response <=65536 bytes; explicit history mode up to16 bound source
records permits declared <=2 MiB artifact output. Bun checks raw byte bounds,
deadline, exit status, expected semantic broker/API error, and unchanged UUID/cuts.
Authentication failure and authorization denial distinct; generic timeout never
counts as negative-control success.

## Proof order and required interfaces

1. Before live lease: source-fresh build/tests, exact artifact hashes, current
   Docker inventory, cached digests, port/network ownership and disk floor.
2. Start only own broker/PG. Record sanitized identity/resources; configure SASL
   SCRAM-SHA-256 and authorization before runtime writers exist. Create dedicated
   operator/ingress/matcher/capture/observer/denied identities, exact least ACLs for
   topics/groups/transaction IDs, no wildcard principal permissions. Privileged
   user may bootstrap; applications never superuser.
3. Positive outsider describe succeeds; outsider publish and canary DELETE/ALTER
   fail authorization; canary config/UUID unchanged. Wrong/anonymous auth fails;
   ingress cannot write source/capture, matcher/capture cannot write commands.
   Operator admin read succeeds; denied admin mutation fails 401/403 and unchanged
   readback. Denials precede admission release.
4. Migrate as owner NOLOGIN/controlled fixture installer. Real runtime role denied
   immutable UPDATE/DELETE/TRUNCATE/re-register and release; valid narrow reserve/
   mark controls succeed. Runtime schema-validate only. Registrar commits real
   UUID/cluster/empty-history opening and counters zero while STOPPED. Seed exact
   source group offset0 only for fresh genesis, retain actual readback.
5. Start sole finite JVM, acquire durable owner STOPPED, verify metadata and real
   managed capture initialization. Start matcher, verify same opening and broker
   history, acquire ownership/source transaction and exact command replay cut.
   Authentication wrong/blank/cookie/loopback bypass denied on bootstrap route.
   Direct/alternate mutation routes refuse before admission/book/source changes.
6. Manager collects nonce-bound fresh readiness; operator calls dedicated SQL
   release function using registrar-only secret. Exact epoch/binding/counters/cuts
   CAS; stale epoch/receipt release actually denied. No checked=true attestation.
7. Canonical 12 commands through same API/manual boundary: 12 outcomes, eight
   successful commands, four business rejections, three trades/six units, 15
   capture members. Exact retained raw commands/source/capture/hash/positions and
   SQL budget journal reconcile. Identical retry and changed-payload conflict
   consume eligible attempts but do not add application publication; no202 before
   actual broker ACK. Separate finite scenarios test exact attempt/publication
   limits and one over, drain after stop, future-window/fault retention.
8. Clean shutdown proves SQL STOPPED -> authorized queue/callback markers drained
   -> bounded producer flush/close -> exact final journal -> old process exit.
   Restart same opening/counters/topics/group/app/state; matcher full command
   replay does not republish source; actual Streams changelog restore and retained
   output equal. Fresh stage receipts/operator release only after revalidation.
9. Deterministic API timeout/late ACK: durable UNKNOWN+STOPPED before503, one
   application send, late PUBLISHED retains position, no refund/release/resend.
   Append-before-mark crash: contain old process, repair only unique positive
   retained matching record, no republish. Empty UNKNOWN scan remains STOPPED.
   ACL/process kill alone is not broker absence certificate. Fresh new run cannot
   disguise incomplete original run.

Needed frozen owner interfaces: Kotlin finite entrypoint/route DTOs, credential
file settings, narrow SQL register/acquire/stop/release/proof signatures, typed
before-send/after-ACK fault seams, clean producer lifecycle receipt; Go verifier
and authenticated readiness endpoint fields. Readiness minimum: nonce, digest,
owner/activation epoch, actual assigned partition/owned state, replay cut/checksum
for matcher; initialized partition, real state hash/resume/frontier/fault for
capture, Streams state. SQL release validates source/capture cuts and unresolved
journal against those observations. Supervisor does not invent accepted receipts.

Manager granted Kotlin finite module while alignment was prepared. Agreed function
names `calcify_finite_{snapshot,begin_attempt,authorize_send,ack,unknown,stop,claim_owner}`;
registrar-only register/release, exact signatures forthcoming. Shared goldens flat
under `contracts/calcify/finite-binding-v1.json`, `.hex`, `finite-startup-v1.json`,
`finite-binding-v1-manifest.json`. Fixture seeds required existing
`boundary.stream_command_intake` from scoped 0005+0011 DDL/minimum dependencies
before new 0013 migration; inspect actual dependencies instead of broad migrations.
Current Kotlin Gradle build lease prevents concurrent heavy Go/helper build;
fixture source lease does not imply build/live lease.

## Resource evidence and teardown

Sample initial/live/stopped every five seconds: exact labeled container IDs,
image IDs, limits/cgroup current+peak where available, process RSS, probe/controller
RSS, owned data volume `du -sk`, JVM state, proof/log paths, guest free disk. Stop
on sample/ownership failure, allocation >=3 GiB, raw proof >=64 MiB, budget breach
or missing telemetry. Disk ceiling 4 GiB remains observed scope, not hard quota
or continuous peak. Startup/stopped samples and Docker writable layer/log-category
readbacks recorded separately; `SizeRw` is logical bytes, not allocated bytes.
Missing complete daemon/kernel/log allocation remains explicit limitation, never
hidden inside project-volume total. Readonly app roots, bounded tmpfs/log drivers
reduce unmeasured categories but do not establish hard total physical disk bound.

First signal SQL stop where reachable, bounded producer shutdown, stop/reap JVM
and matcher, capture final receipts before broker/PG stop. Stop only frozen owned
container IDs with verified labels/image/mount/network, preserve own volumes and
all proof; no global down/prune/reset or `down -v`. Any cleanup failure retained
and qualified as unresolved, not swallowed. Never delete/recreate canonical topics
or reset groups to make restart pass. Distinct fault fixtures/runs explicitly
registered; old failed run remains separate evidence.

Replan flags: sole JVM/native usage cannot fit; actual SCRAM/ACL or SQL grants
cannot separate roles; Streams restore/cuts cannot support chosen proof; maximum
state transport cannot fit; hard disk guarantee demanded but unavailable. Run
bounded research spike, return scope/evidence before alternative implementation.
No source or live resource lease assumed by this note.

## Primary references

- [Redpanda v26.2 user-create flags](https://docs.redpanda.com/streaming/current/reference/rpk/rpk-security/rpk-security-user-create/)
- [Redpanda v26.2 ACL creation](https://docs.redpanda.com/streaming/current/reference/rpk/rpk-security/rpk-security-acl-create/)
- [Accepted finite source contract](../../../docs/work/CALCIFY_FINITE_P3_SOURCE_CONTRACT.md)
- [Prior activation protocol](../../calcify-p3-overnight/o2/activation-protocol-research.md)
- [Prior manager decisions](../../calcify-p3-overnight/o2/manager-decisions.md)
- [Prior broker-isolation preflight](../../calcify-p3-overnight/preflight/broker-isolation.md)
