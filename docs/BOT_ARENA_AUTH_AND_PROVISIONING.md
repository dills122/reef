# Bot Arena Auth And Provisioning

## Purpose

This document fixes the target authentication and provisioning model for public
Bot Arena participation. It covers the normal user path, operator path, CI path,
OpenBao secret path, and the pre-merge gates around bot acceptance.

The core rule is separation of responsibility:

- GitHub proves human identity.
- Reef Admin DB owns local users, roles, trust state, bot ownership, limits, and
  audit records.
- Reef Admin API mediates durable control-plane operations.
- OpenBao stores bot and service secrets.
- Runtime services read OpenBao through service credentials, not through user
  credentials.

## Actors

### Participant

A participant is a GitHub-authenticated user who can submit bots, own accepted
bots, edit their bot config through the admin app, and view their own results.

Participants do not normally log into OpenBao. The admin app is the participant
secret/config surface.

### Reviewer

A reviewer can inspect bot submissions and approve PRs. A reviewer role can be
seeded from GitHub repository access, but Reef roles are the final authorization
source.

### Operator

An operator can manage runs, game settings, user trust state, bot enablement,
and operational recovery.

### Secret Admin

A secret admin can perform sensitive OpenBao-backed actions such as manual
secret repair or rotation. This role must be explicit in Reef and must not be
granted only because a user has GitHub repository write access.

### Platform Admin

A platform admin is a break-glass role for full control-plane administration.
GitHub repository owner/admin status can seed this role during bootstrap, but
Reef authorization owns the continuing assignment.

## Identity Model

GitHub is the first human identity provider because a GitHub account is already
required to submit bots through pull requests.

Reef must key users by GitHub's immutable numeric user id, not by username or
email. GitHub login and email are display/contact attributes only and may
change.

Minimum user fields:

```text
reef_user_id
github_user_id
github_login
display_name
trust_state
created_at
last_seen_at
```

Initial Admin DB tables:

```text
admin.users
admin.roles
admin.user_roles
admin.user_bot_limits
admin.user_bot_ownerships
admin.audit_events
admin.oauth_states
admin.sessions
admin.service_tokens
```

OAuth states, browser session tokens, and service tokens must be stored as
server-side hashes only. Raw token values are returned once at issue time and
must not be persisted in Admin DB audit records or logs.

Trust states:

```text
new
trusted
limited
banned
```

Trust state changes review friction and allowed actions, but accepted bot code
still passes through a human review gate.

## Role Model

Reef roles are the source of truth. GitHub repository role can seed role
assignment at login or during bootstrap, but local roles are what the Admin API
enforces.

Suggested seed mapping:

```text
GitHub repo owner/admin       -> platform-admin candidate
GitHub repo maintainer/admin  -> operator/reviewer candidate
GitHub collaborator with write -> reviewer candidate
normal GitHub user            -> participant
```

Baseline Reef roles:

```text
participant
reviewer
operator
secret-admin
platform-admin
```

## Communication Channels

### User Facing

```text
user -> web admin app
user -> GitHub PR/review flow
user or bot -> /api/v1 trading API
operator -> OpenBao UI/CLI, usually through SSH tunnel
```

Normal participants should not need direct OpenBao access. Direct OpenBao login
is an operator escape hatch.

### Service To Service

```text
CI -> Admin API
Admin API -> OpenBao
platform-runtime -> OpenBao
simulator/run-plane -> OpenBao
simulator/run-plane -> Admin API
run-plane export/cleanup -> Analytics/Admin API
Admin API -> Admin DB
Admin API -> Analytics DB
platform-runtime -> Runtime DB
platform-runtime -> matching-engine
```

### Admin And Ops

```text
operator -> SSH tunnel -> Admin API
operator -> SSH tunnel -> OpenBao
operator -> deploy scripts -> backbone host
backup job -> Postgres/OpenBao -> R2
```

### Future GitHub Automation

GitHub App support is deferred for MVP. If it is added later, it should own
webhooks, check runs, PR comments, and installation-token based repository
automation.

Until then, GitHub Actions and `GITHUB_TOKEN` can post statuses/comments, while
CI authenticates to the Admin API through the existing scoped token and later
through GitHub Actions OIDC.

## Auth By Channel

```text
user -> web admin app: GitHub OAuth session
participant -> OpenBao: not a normal product path
operator -> OpenBao: OIDC or SSH tunnel plus manual/admin token during early ops
CI -> Admin API: scoped bearer token first, GitHub Actions OIDC later
Admin API -> OpenBao: server-side OpenBao client flow
platform-runtime -> OpenBao: AppRole
simulator -> OpenBao: AppRole
run-plane -> Admin/Analytics API: scoped bearer token first, workload identity later
operator -> host: SSH key
backup -> R2: R2 access key scoped to backup bucket
```

## Admin HTTP Auth Boundary

The platform runtime exposes the first admin auth HTTP boundary when
`PLATFORM_ADMIN_AUTH_ENABLED=true`.

Required environment:

```text
PLATFORM_ADMIN_AUTH_ENABLED=true
ADMIN_POSTGRES_JDBC_URL or RUNTIME_POSTGRES_JDBC_URL
ADMIN_POSTGRES_USER or RUNTIME_POSTGRES_USER
ADMIN_POSTGRES_PASSWORD or RUNTIME_POSTGRES_PASSWORD
GITHUB_OAUTH_CLIENT_ID
GITHUB_OAUTH_CLIENT_SECRET
GITHUB_OAUTH_REDIRECT_URI
```

Optional environment:

```text
ADMIN_SESSION_COOKIE_NAME=reef_admin_session
ADMIN_SESSION_COOKIE_SECURE=true
```

`GITHUB_OAUTH_REDIRECT_URI` must be HTTPS, except `http://localhost...` is
allowed for local development. Local browser development over plain HTTP should
set `ADMIN_SESSION_COOKIE_SECURE=false`; hosted environments should keep secure
cookies enabled.

Browser routes:

```text
GET  /admin/auth/github/start?redirectPath=/admin
GET  /admin/auth/github/callback
GET  /admin/auth/session
POST /admin/auth/logout
```

The GitHub callback consumes the one-time OAuth state, upserts the GitHub-backed
Reef user, issues a server-side session, and sets the admin session cookie. Raw
session tokens and OAuth state tokens are stored as hashes only.

Admin API gateway routes now accept either the admin session cookie or a scoped
server-side service token. Scoped service token families are route-specific:

```text
/admin/v1/arena/bots                    -> ci, admin
/admin/v1/arena/bots/openbao-provision  -> ci, admin
/admin/v1/analytics/run-exports         -> sim, admin
/admin/v1/risk/account-controls         -> admin
/admin/v1/risk/circuit-breakers         -> admin
/admin/v1/risk/price-collars            -> admin
/admin/v1/settlement/facts              -> admin
/admin/v1/settlement/repairs/cash       -> admin
/admin/v1/settlement/repairs/security   -> admin
/admin/v1/settlement/force-settle       -> admin
/admin/v1/settlement/reverse-ledger-entry -> admin
/admin/v1/settlement/obligations/materialize -> admin
```

The existing static bearer-token environment variables remain as compatibility
fallbacks:

```text
ARENA_ADMIN_API_TOKEN
ANALYTICS_EXPORT_API_TOKEN
ADMIN_API_TOKEN
```

When the Admin DB auth layer is configured, authenticated session and service
tokens bind the internal admin actor id from the trusted token record instead of
from caller-controlled headers. The static fallback preserves the older header
behavior for legacy scripts until those callers move to service tokens.

## Bot Submission Flow

### New Bot

```text
1. User signs into the admin app with GitHub.
2. User forks or branches the repository and creates a bot submission.
3. User opens a PR.
4. CI validates manifest, static/security checks, sandbox behavior, and resource limits.
5. Human reviewer approves the submission intent.
6. CI/Admin API provisions or verifies the Reef user and OpenBao bot slice.
7. PR check confirms the slice exists.
8. Maintainer merges the PR.
9. Bot version becomes accepted.
10. User enters or updates config blob in the admin app.
11. Run eligibility turns green only after required runtime config is ready.
```

OpenBao slice provisioning should happen before merge. Secret/config completeness
should gate run eligibility, not merge, because config values are user-managed
opaque data and should not be exposed to CI or reviewers.

### Existing Bot Update

```text
1. User opens PR for a new version of an owned bot.
2. CI validates the new version.
3. Human reviewer approves.
4. Admin API verifies owner, trust state, limits, and existing OpenBao slice.
5. PR merges.
6. New bot version becomes accepted.
```

Accepted users may have a faster review path, but follow-up versions still keep
a human gate unless an explicit future decision changes that policy.

### Removed Or Disabled Bot

Removal and disablement are separate concepts:

- disablement prevents future runs without deleting registry history or secrets
- removal is an explicit administrative action with stronger audit and secret
  lifecycle consequences

Secret deletion should not be automatic on ordinary code removal until the
retention and recovery policy is explicit.

## Ownership And Limits

A GitHub user may own multiple bots, subject to configurable limits.

Recommended config:

```text
MAX_BOTS_PER_USER
MAX_ACTIVE_BOTS_PER_USER
MAX_BOT_VERSION_SUBMISSIONS_PER_DAY
```

Bot versions are not lifetime-limited, but version submission rate should be
bounded to protect CI and review capacity.

Team-owned bots and ownership transfer are deferred. The registry should avoid
schema choices that make those impossible later.

## OpenBao Secret Model

OpenBao stores an opaque config blob per bot secret slice.

```text
secret/bots/<submitter-identity>/<bot-id>
```

The config blob is not versioned. Runtime loads the current blob when a run is
prepared, validates only the platform-known descriptor shape where declared,
and passes the data to the bot as `ctx.config`.

Bots own application-level parsing and validation of their config blob. Public
bot files must not contain secrets; anything committed in a bot file is public
by definition.

For replay and audit, run records should store metadata, not secret values:

```text
openbao_path
config_sha256
loaded_at
```

## Merge And Run Gates

Required before merge:

- bot manifest and contract checks pass
- sandbox/security/resource checks pass
- human reviewer approval exists
- user is not banned
- configured bot ownership limits pass
- OpenBao slice exists or has been provisioned

Required before run:

- bot version is accepted
- bot is enabled
- user is not banned
- required config descriptors are resolvable
- config blob passes platform-known descriptor checks where declared
- simulation safety switches and runtime guardrails pass

## Failure Handling

PR feedback should classify failures by who can fix them.

User-fixable failures:

```text
manifest invalid
bot tests fail
sandbox/resource gate fails
config descriptor invalid
bot ownership or limit problem
```

Maintainer/platform failures:

```text
Admin API unavailable
OpenBao unavailable
OpenBao provisioning failed
CI cannot authenticate to Admin API
registry read/write failure
unexpected identity mismatch
```

Platform failures should post a PR comment with the failed subsystem, shortest
actionable error, and maintainer tag. The first implementation can tag the
primary maintainer; later this should move to a configured maintainer group.

## Audit Events

The control plane should append audit events for identity, review, provisioning,
secret, and run actions.

Initial event names:

```text
user.login
user.trust_state.updated
bot.created
bot.version.submitted
bot.version.approved
bot.openbao_slice.provisioned
bot.config.updated
bot.disabled
user.banned
run.started
run.finished
run.result.published
```

Audit records should include actor, subject, timestamp, correlation id, source,
and relevant object ids. Audit records must not contain secret values.

## MVP Decisions

- GitHub is the main human identity provider.
- GitHub OAuth is enough for the web admin app MVP.
- GitHub App is deferred.
- GitHub Actions plus a scoped Admin API token remain acceptable first.
- GitHub Actions OIDC is the preferred later replacement for CI-to-Admin API
  shared tokens.
- OpenBao remains mostly hidden from participants.
- AppRole remains the service-to-OpenBao auth method.
- OpenBao slice provisioning is a pre-merge requirement.
- Config completeness is a pre-run requirement.
- Reef roles and trust state are stored in the Admin DB and enforced by the
  Admin API.

## Open Follow-Ups

- Implement Admin DB user, role, trust-state, ownership, and audit tables.
- ~~Add GitHub OAuth login to the admin app.~~ Done: `apps/arena-admin` has a
  working GitHub login, and Caddy now reverse-proxies `/admin/auth/*`,
  `/admin/v1/*`, and `/api/v1/*` same-origin with the static app (previously
  the browser-facing surface wasn't Caddy-exposed at all, only the CI
  bearer-token paths were).
- Bridge the two disconnected admin identity/permission systems: GitHub OAuth
  login (`AdminIdentityService`) grants a baseline `participant` role in its
  own table, but `AdminApplicationService.requirePermission` checks a
  completely separate `runtimePersistence` role-binding table that only the
  hardcoded bootstrap actor (`admin-cli`/`ADMIN_ACTOR_ID`) has any role in.
  A fresh GitHub login today has zero permission to call any `/admin/v1/...`
  route, and there's no HTTP-reachable grant path — only CLI
  `role-assign`/`role-upsert`. Needs a real design decision (see the arena
  admin UI plan, D-052) before the admin app's data panels can be wired to
  live routes.
- Add Admin API authorization middleware that binds actor identity from the
  authenticated principal, not caller-controlled headers.
- Move CI-to-Admin API auth from scoped bearer token to GitHub Actions OIDC.
- Add pre-merge OpenBao-slice verification check.
- Add participant config editor backed by Admin API and OpenBao.
- Define public bot submission contract after the current bot testing bugs are
  worked out.
