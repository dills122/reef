# Bot Arena Release Readiness

## Purpose

This is the current release gate for accepting external Bot Arena submissions.
It separates implemented capability from repository or hosted configuration and
from evidence that has actually been observed.

Last verified: 2026-07-21.

## Release Call

Bot Arena is ready for an operator-controlled preview with built-in bots and
same-repository test submissions. The chosen next release is an **invite-only,
fork-based preview**. The code path is implemented locally, but Bot Arena is
**not ready to advertise open or self-service external submissions** until the
named external-fork proof is complete.

The venue, SDK, isolation gate, registry, hosted admin surface, public
leaderboard read, same-repository submission proof, and corrected local
economic attribution are substantial. The remaining blockers are external
intake proof and hosted rehearsal:

- fork submissions need a named external-account proof through approval,
  provisioning, merge, and registry sync
- repository protection requires the trusted `registry-diff-and-provision`
  status. The workflow publishes a successful no-op status for ordinary PRs,
  so this does not block non-bot work.
- GitHub's branch-level approval requirement is not conditional on bot branch
  conventions. During preview, bot-specific human review is enforced by the
  trusted maintainer-only approval workflow, which binds the reviewer identity
  and exact SHA before provisioning.
- no external/fork submission has completed the full lifecycle; the only merged
  proof is the owner-controlled `bots/add/dsteele-smoke-bot` PR
- the separation promotion did not include a hosted deployment rehearsal; the
  Hetzner Compose default remains the Reef-core image and must explicitly select
  the Arena-enabled image before the next Arena cutover
- the corrected local three-policy matrix passed on 2026-07-21 with immutable
  roster bindings, 30 run-scoped fills per policy, complete reconciliation,
  zero command-accounting gap, and no unspecified execution roles. The reports
  retain a global crossed-book warning because market-data reads are not yet
  venue-session scoped; hosted economic and deployment proof remains pending.

Do not solve the fork problem by giving PR-controlled workflows hosted secrets,
OIDC minting permission, or a privileged checkout. Keep untrusted validation
separate from trusted provisioning.

## Verified Readiness Matrix

| Area | State | Evidence | Release consequence |
| --- | --- | --- | --- |
| Bot authoring contract | Ready for preview | `ReefBotV1`, examples, fixtures, typecheck and qualification scripts under `packages/bot-sdk/` and `scripts/dev/` | Authors can build and qualify TypeScript bots from a repository checkout. |
| Untrusted test isolation | Ready for preview | Bot submission CI uses `--isolation=container`; container defaults to no network, read-only repository mount, tmpfs, and CPU/memory/PID caps | Suitable for a controlled preview; continue treating the sandbox as hostile-code infrastructure. |
| Manifest and dependency policy | Ready for preview | `bot-submission-validate.mjs`, approved-package manifest, hosted artifact build, import scan, and negative fixtures | The automated pre-merge gate is real, but policy and issue messaging remain versioned pre-release contracts. |
| Same-repository submission | Proven | PR #177 passed manifest validation, sandbox testing, trusted provisioning, merge, and later registry sync | Collaborator/invite-only submissions are viable. |
| External fork submission | Locally verified; external proof pending | Forks persist `pending_invite_review`; trusted approval binds maintainer GitHub identity and exact SHA, then automatically dispatches base-branch provisioning; changed SHA resets approval | Do not advertise the flow until a named external account completes it. |
| Required human review | Implemented for bot submissions | `Bot Submission Invite Approval` requires a `maintain` or `admin` GitHub actor; Arena additionally requires a trusted operator reviewer and trusted participant identity before recording approval | This is intentionally bot-specific; do not apply a global PR approval count solely for this preview gate. |
| Required trusted provisioning | Enforced | Live `master` protection requires `registry-diff-and-provision`; it is pending until a fork is approved, succeeds after trusted provisioning, and succeeds as a no-op for non-bot PRs | Bot submissions cannot merge without the trusted provisioning outcome; ordinary PRs remain unblocked. |
| Hosted credentials | Configured | Repository secret names include `ARENA_ADMIN_API_URL` and `ARENA_ADMIN_API_TOKEN`; values were not inspected | Hosted workflow prerequisites exist. |
| Post-merge registry sync | Proven for internal smoke | `Bot Registry Sync` passed after the smoke bot merged and has subsequent green runs | Durable registration path exists for merged manifests. |
| Admin identity and ownership | Implemented | Admin DB migrations and Kotlin stores cover GitHub-backed users, roles, trust state, limits, ownership, audit, OAuth state, and sessions | The earlier “implement Admin DB tables” follow-up is stale. |
| Participant config surface | Implemented, needs named external-user proof | Arena admin exposes owner-scoped bot config through the Admin API/OpenBao boundary | Keep as a preview claim until a non-operator participant completes it. |
| Bot-version venue risk gate | Implemented and tested | `ArenaBotVersionRiskCheck` and boundary tests reject disabled/non-active versions before acceptance | Strong release foundation. |
| Public leaderboard | Deployed, empty | Hosted `/leaderboard` loaded without console errors and used `/api/v1/arena/leaderboard`; no public scored runs were present | Do not imply an active competition yet. Seed or promote one clearly labelled preview run before launch. |
| Run correctness and capacity | Corrected local economic evidence; hosted proof pending | Local positive/negative gates, deterministic score-v1 proof, hosted 15-minute arena run, and short pacing gates are green. `reports/arena-economic-policy-matrix/20260721-role-corrected-v4/manifest.json` passes all three economic policies with 30 scoped fills each, complete reconciliation, zero accounting gap, and preserved role attribution. | Local non-zero economic attribution is proven; require hosted matrix/rehearsal evidence before preview promotion. |
| Projection capacity | Adequate for preview, not final target | Venue core has a `10k` materializer baseline; full projection passed `5k/60s`, with write amplification still high | Optimize before longer/high-rate public seasons, but this is not the first external-submission blocker. |
| Public onboarding | Blocked | No contribution guide, PR template, submission checklist, or copyable bot scaffold is exposed as the authoritative external flow | Even after trust-flow resolution, users do not yet have a safe, complete submission path. |
| Product messaging | Needs alignment | Hosted landing invites users to submit; public docs say submissions are closed | Use “limited preview” language until the gates in this document are green. |

## Chosen Preview Intake

Accept fork PRs, but keep admission invite-only:

1. A fork PR runs untrusted validation and enters `pending_invite_review`.
2. A maintainer reviews the user, ownership intent, and immutable
   head SHA. No review-time SLA is promised during the preview.
3. Approval triggers trusted provisioning from base-branch code and immutable
   PR metadata. The trusted job never executes fork-controlled code.
4. The participant can complete required config before merge.
5. The maintainer merges only after checks, provisioning, and config readiness
   are green.
6. Post-merge registry sync rebuilds from `master`, verifies hashes, and makes
   the version eligible for a future locked roster.

This preserves the normal fork contribution model without exposing hosted
secrets or allowing an unreviewed submission to mutate hosted state. Full task
ordering and acceptance criteria live in
[`BOT_ARENA_INVITE_PREVIEW_SPRINT.md`](./BOT_ARENA_INVITE_PREVIEW_SPRINT.md).
Implementation begins only after the product/artifact boundary in
[`REEF_BOT_ARENA_SEPARATION_SPRINT.md`](./REEF_BOT_ARENA_SEPARATION_SPRINT.md)
is promoted.

The design must prove:

- no PR-controlled code runs with hosted Admin API credentials or OIDC
- provisioning cannot be spammed merely by opening a fork PR
- GitHub immutable user identity, bot ownership, trust state, and limits are
  checked before mutation
- the exact reviewed commit is the commit registered and later run
- retries are idempotent and failure leaves no accepted-but-untracked bot

Do not open the invite preview until this path has a named fork-based smoke.

## Admission Window

Each scheduled game is anchored to `T0` and uses UTC persisted timestamps:

| Relative time | Required outcome |
| --- | --- |
| `T-72h` | Invitation decision complete. |
| `T-48h` | Approved SHA, untrusted checks, provisioning, config readiness, and human review are green. |
| `T-24h` | Merge and post-merge registry/hash verification are complete; immutable roster locks. |
| `T-2h` | Hosted readiness, capacity, config, backups, and kill switches pass. |
| `T0` | Only the locked roster starts. |
| `T+2h` target | Operator-validated preview result is published; this is not a participant SLA. |

A bot merged or made ready after `T-24h` rolls to the next game. No version,
config, seed, persona, scoring policy, or economic policy is substituted after
roster lock. The sprint doc defines the complete eligibility facts, reason
codes, emergency-removal rules, and evidence envelope.

## Launch Gates

### Required For Invite-Only Fork Preview

- Reef-only and Arena-enabled separation gates are promoted from the same commit (passed; see [`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./REEF_BOT_ARENA_SEPARATION_PROMOTION.md))
- the hosted backbone is rehearsed with the Arena-enabled image, Arena routes
  and storage readiness pass, and rollback to the last known-good Arena image is documented
- branch protection requires `registry-diff-and-provision`; ordinary PRs receive a successful no-op result, while bot submissions remain pending until trusted admission/provisioning completes
- fork-safe pending, approval, trusted provisioning, and denial paths are drilled
- fork-based add, update, and remove lifecycle is drilled with an external test account
- owner config write and run eligibility are verified with a participant role
- the implemented and locally tested `T-72h`/`T-48h`/`T-24h` admission,
  roster-lock, and `T-30m`/`T0` run-binding policy has fresh hosted evidence
- the corrected three-policy economic matrix is repeated on the promoted hosted
  Arena profile; local evidence is recorded under
  `reports/arena-economic-policy-matrix/20260721-role-corrected-v4/`
- one labelled preview run appears on the public leaderboard
- hosted backup/restore and operator quarantine/recovery runbooks are current
- public copy says limited preview and simulated, not real-money trading

### Additional Requirements For Open Public Submission

- fork-safe trusted handoff is implemented and tested
- external contributor documentation and PR template are published
- user/bot/version rate limits and CI abuse controls are enforced
- approval, rejection, platform-failure, and retry messages are understandable
- a forked external test account completes sign-in, submission, review,
  provisioning, merge, config, run, quarantine, and recovery
- dependency/license policy is explicit for every allowed package
- a security review covers artifact build, sandbox escape, secret access,
  resource exhaustion, and supply-chain boundaries

## Next Engineering Priority

If external bot submission is the release objective, the Reef/Arena separation
prerequisite is now promoted; prioritize intake implementation and repository
enforcement above further projection throughput tuning. Current projection
evidence is sufficient for a bounded preview. Write amplification remains
important for longer and higher-rate seasons, but it does not explain why an
external user cannot submit today.

## Talking Points

- What recurring game cadence should replace the relative `T0` preview window
  after the recorded test campaign?
- What is the first preview game mode, run duration, bot count, and worker cap?
- Which scoring policy is launch-stable: current `score-v1` or a later version?
- Which economic policy should be the preview default after zero-fee,
  balanced-fee, and liquidity-subsidy calibration?
- What support channel owns user-fixable versus platform-fixable failures?
- What evidence and retention policy must exist before a bot can be restored
  after quarantine or archive?

## Evidence Sources

- `.github/workflows/bot-submission.yml`
- `.github/workflows/bot-submission-invite-approve.yml`
- `.github/workflows/bot-submission-provision.yml`
- `.github/workflows/bot-registry-sync.yml`
- `scripts/dev/bot-sdk-test-bot.mjs`
- `scripts/dev/lib/bot-isolation.mjs`
- `scripts/dev/run-arena-economic-policy-matrix.mjs`
- `reports/arena-economic-policy-matrix/20260721-role-corrected-v4/manifest.json`
- `services/arena-control-plane/`
- `scripts/dev/db/migrations/arena/`
- `apps/arena-admin/`
- GitHub PR #177 and its checks
- live `master` branch-protection response inspected 2026-07-19
- hosted Arena landing and leaderboard inspected 2026-07-18
