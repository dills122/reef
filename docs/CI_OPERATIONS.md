# CI Operations

## Required Merge Gate

`ci-required` is the stable branch-protection context for Reef CI. It runs with
`if: always()` and rejects failed, cancelled, or unexpectedly skipped critical
jobs. Bot-only pull requests may skip full CI; human pull requests may skip the
expensive replay job; pushes, manual runs, and Dependabot pull requests must run
replay.

After this workflow reaches the default branch, configure the `main` ruleset to
require `ci-required`. Keep the three bot-submission contexts required until
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
  first full recovery closes it with a run link.
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

## Local Verification

```bash
node scripts/dev/ci-workflow-hardening.test.mjs
node --test scripts/ci/check-required-results.test.mjs
node scripts/dev/dependabot-automation-workflow.test.mjs
node scripts/dev/script-surface-check.mjs
actionlint
make test-dev-tooling
```
