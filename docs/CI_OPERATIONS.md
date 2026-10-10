# CI Operations

## Required Merge Gate

`ci-required` is the stable branch-protection context for Reef CI. It runs with
`if: always()` and rejects failed, cancelled, or unexpectedly skipped critical
jobs. Bot-only pull requests may skip full CI; human pull requests may skip the
expensive replay job; pushes, manual runs, and Dependabot pull requests must run
replay.

The workflow landed on `master` in PR #349. Verify the ruleset targeting the
default branch (`master`) requires `ci-required`; the ruleset's display name
may differ from the branch name. Repository files alone do not prove the live
required-check configuration. Keep the three bot-submission contexts required until
their separate trusted workflow is folded into an equivalent aggregate gate:

- `validate-manifest`
- `scan-and-sandbox-test`
- `registry-diff-and-provision`

Do not require individual full-CI job names alongside `ci-required`. Matrix and
job-name changes would recreate the drift that the aggregate context prevents.

## Scheduled Health Checks

- Runtime Stress Sanity runs Monday, Wednesday, and Friday at 08:17 UTC. Both
  no-persistence and DB-backed lanes run twice. First scheduled failure opens
  one `Runtime Stress Sanity is failing` issue; repeated failures remain quiet;
  first full recovery closes it with a run link. Lifecycle lanes gate
  valid-intent success so expected business rejects such as self-trade
  prevention remain visible without being misclassified as system failures.
- Materializer 10k Gate runs its non-destructive `plan` command Tuesday at
  09:31 UTC. Infrastructure-provisioning `run-destroy` remains manual and still
  requires explicit confirmation plus provider credentials.

Treat a scheduled failure as same-day triage work. Do not normalize recurring
red runs by disabling their schedule or weakening thresholds.

## Dependabot Merge Verification

Dependabot merge automation verifies author and exact successful CI head, asks
Dependabot to rebase stale branches, then squash-merges the checked head. Since
GitHub suppresses most recursive workflow events created with `GITHUB_TOKEN`,
the automation explicitly dispatches CI on the default branch after merge. It
also dispatches existing container, docs, or admin delivery workflows only when
their path scopes changed.

## Workflow Maintenance Rules

`container-builds` uses `node scripts/ci/build-container.mjs` for all matrix
images. It streams Docker output and retries only Gradle dependency requests
to Maven Central or Gradle Plugin Portal that return HTTP 429. Maximum three
attempts, with 15s then 30s backoff; successful Docker layers remain cached
within that job. Compiler errors, other build failures and interrupted builds
fail immediately. Persistent throttling still fails `ci-required`; this does
not waive or lower merge checks.

October 2, 2026 incident: [PR #470](https://github.com/dills122/reef/pull/470)
passed [pre-merge CI](https://github.com/dills122/reef/actions/runs/37089823096),
including stock-data image build, before merging as `2af704d7`.
[Master CI attempt 1](https://github.com/dills122/reef/actions/runs/37090920609/attempts/1)
failed fetching `kotlin-gradle-plugins-bom:2.4.20` from Maven Central with
HTTP 429; stock-data container build was the sole originating failure, and
`ci-required` correctly rejected it. [Attempt 2](https://github.com/dills122/reef/actions/runs/37090920609/attempts/2)
reran failed jobs on unchanged code and passed. Live default-branch ruleset
`18541941` required `ci-required`, was active and had no bypass actors when
checked; PR head included then-current master `f3d42d31`. Stale-base merges
were permitted by configuration, but did not cause this incident. External
dependency availability can differ between pre-merge and post-merge runs;
merge protection cannot guarantee downstream service uptime.

- Pin external actions to full commit SHAs. Keep release tags in comments so
  Dependabot can propose reviewed SHA updates.
- Give every job a finite timeout.
- Declare least-privilege workflow permissions and grant writes only per job or
  workflow where required.
- Upload diagnostic and coverage artifacts with `if: always()` when earlier
  steps can fail.
- Set `cache-dependency-path` for nested Go modules.
- Update `scripts/dev/ci-workflow-hardening.test.mjs` when adding workflows,
  jobs, or external actions.

## Opt-in OpenCodeReview pilot

[Workflow](../.github/workflows/open-code-review-pilot.yml) runs only for ready PRs carrying `ocr-pilot`; unrelated label additions do not trigger review. Later pushes, reopening and ready-for-review events run opted-in PRs. Trusted base workflow/rules control review; PR files must not execute here.

Checkpoint ranges narrow later pushes after completed trusted review. Missing/untrusted checkpoints, base/config changes and non-ancestor pushes cause full review; reopening or becoming ready requests full review. Request full review when unchanged-file interactions matter. Inspect range summary and skipped/partial results; budget/timeout do not guarantee complete review. Humans validate findings.

[Project rules](../.opencodereview/rule.json) include changed service tests, exclude docs/reports and generated Calcify Java protobuf classes, and leave language rules at defaults. Handwritten Go/Kotlin, tests and contract schemas remain reviewable; generated artifacts still require regeneration/additive/drift and golden checks. Includes bypass default filters rather than restrict review to whitelist. Workflow keeps medium effort, concurrency 2 and 1,500,000-token budget; budget/timeout can stop review before full coverage, so verify selected/completed/failed counts rather than infer completion from exit status. Workflow owns model and limits; changing trusted rule/budget forces full checkpoint-range review. [Dated pilot measurements](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/records/reef/docs/work/open-code-review-pilot.md) establish historical scope only.

## Local Verification

`calcify-tests` also runs boundary idempotency renewal integration tests against
real PostgreSQL with `RUNTIME_DB_URL_TEST`, `RUNTIME_DB_USER_TEST` and
`RUNTIME_DB_PASSWORD_TEST` configured. Missing local DB configuration reports
skipped tests; configured URL with missing credentials fails. Renewal tests cover
expiry, concurrent replacement and preservation of live results.

```bash
node scripts/dev/ci-workflow-hardening.test.mjs
node --test scripts/ci/check-required-results.test.mjs
node scripts/dev/dependabot-automation-workflow.test.mjs
node scripts/dev/script-surface-check.mjs
actionlint
make test-dev-tooling
```

## Historical-record retention

`Records retention` workflow runs `bun run repo:check:records` and
`bun run repo:test:records` without product dependencies or cross-repository access.
It checks relocation metadata, active links, required fixtures, complete retained
evidence bundles and original checksum companions. [Retention policy](RECORDS_RETENTION.md)
defines latest-bundle selection and immutable archive lookup.
