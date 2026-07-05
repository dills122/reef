// Deliberately not implemented yet: real OpenBao infra exists in
// infra/hetzner-core/server/ (codex/hetzner-core-infra branch, not yet merged
// to master) but only bootstraps AppRole auth for reef-platform-runtime and
// reef-simulator to READ secret/data/bots/* at runtime - see
// configure-openbao.sh. No JWT/OIDC auth backend for CI-time provisioning
// exists yet, and no application-level OpenBao HTTP client exists anywhere in
// this repo (Kotlin or TS). See BOT_ARENA_PLAN.md's "Resolved Slice: Bot
// Submission Workflow And OpenBao Provisioning" for the target design: a
// second auth backend (auth/jwt) alongside the existing AppRole backend,
// scoped to a narrow provisioning-only policy.
//
// This script exists so the bot-submission workflow's job wiring, required
// inputs (botId, flow, submitter identity), and failure-blocks-merge behavior
// can be exercised end-to-end before that real integration is built. It
// intentionally fails unless BOT_SUBMISSION_OPENBAO_DRY_RUN=1 is set, so CI
// never silently reports success for provisioning that did not happen.
//
// Once built, this becomes a plain HTTP client (no JVM/Gradle in CI) calling
// a new route on the real, always-on hosted admin API
// (ARENA_ADMIN_API_URL, same var as bot-submission-registry-diff.mjs), e.g.
// POST /internal/admin/arena/bots/openbao-provision. The Kotlin OpenBao
// client and the GitHub OIDC -> auth/jwt exchange both run server-side; this
// script never talks to OpenBao directly.

const [, , botId, flow, submitterIdentity] = process.argv;
const validFlows = ["add", "update", "remove"];
if (!botId || !validFlows.includes(flow) || !submitterIdentity) {
  console.error(
    "usage: node scripts/dev/bot-submission-provision-openbao.mjs <botId> <add|update|remove> <submitterIdentity>",
  );
  process.exit(1);
}

const slicePath = `secret/bots/${submitterIdentity}/${botId}`;
const action = flow === "remove" ? "revoke/delete" : "provision";

if (process.env.BOT_SUBMISSION_OPENBAO_DRY_RUN !== "1") {
  console.error(
    `bot-submission-provision-openbao: not implemented - would ${action} ${slicePath} (flow=${flow}) via a new GitHub OIDC -> OpenBao JWT auth backend (alongside the existing AppRole backend), but no such backend or client integration exists yet. Set BOT_SUBMISSION_OPENBAO_DRY_RUN=1 to exercise workflow wiring without real provisioning.`,
  );
  process.exit(1);
}

console.log(`bot-submission-provision-openbao: dry-run ok, would ${action} ${slicePath} (flow=${flow})`);
