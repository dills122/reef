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

Trust state rules:

- `new` is assigned automatically on first GitHub OAuth login. New users can
  sign in, view their account and linked bots, and prepare owner-managed config,
  but their bots are not run-eligible.
- `trusted` is required for bot run eligibility.
- `limited` is an operator-applied safety restriction. Limited users can view
  public pages and their own bot status, but cannot write bot config, submit or
  activate new bot versions, or become run-eligible.
- `banned` blocks Bot Arena participation, bot config management, provisioning,
  and run eligibility.

Only `operator` or `platform-admin` can move users among `new`, `trusted`, and
`limited`. Only `platform-admin` can apply or remove `banned`. Every trust-state
change requires a reason and an Admin DB audit event.

Accepted bot code still passes through a human review gate.

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

Role assignment rules:

- `participant` is assigned automatically on first GitHub OAuth login.
- `reviewer` can be assigned by `operator` or `platform-admin`.
- `operator`, `secret-admin`, and `platform-admin` can be assigned only by
  `platform-admin`.
- `platform-admin` is a bootstrap or break-glass role, not a normal
  GitHub-repository-role sync.
- Role assignment and removal require a reason and an Admin DB audit event.

The backend role ids above are the source-of-truth values. The web app may map
older or friendlier display labels for presentation, but authorization decisions
must use the canonical backend ids. For Bot Arena browser sessions, `operator`
implies arena game/run administration and `platform-admin` implies all operator
capabilities.

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
/admin/v1/arena/bots/config             -> admin
/admin/v1/arena/bot-versions            -> ci, admin
GET /admin/v1/access/users              -> admin
GET /admin/v1/access/roles              -> admin
POST /admin/v1/access/users/trust-state -> admin
POST /admin/v1/access/users/roles       -> admin
POST /admin/v1/access/users/roles/revoke -> admin
GET /admin/v1/arena/runs                -> ci, admin
GET /admin/v1/arena/run-bot-results     -> ci, admin
GET /admin/v1/arena/run-enforcement-events -> ci, admin
GET /admin/v1/arena/leaderboard         -> ci, admin
/admin/v1/analytics/run-exports         -> sim, admin
/admin/v1/analytics/run-bot-summaries   -> sim, admin
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

Access-management browser sessions require a trusted Admin DB `operator` or
`platform-admin`. The `/admin/access` page lists Admin DB users, roles, trust
state, and bot ownerships, then writes trust-state and role mutations through the
routes above. The gateway performs the coarse operator/platform-admin session
check; `AdminIdentityService` applies the finer policy: operators may assign or
revoke `reviewer` and move users among `new`, `trusted`, and `limited`, while
`platform-admin` is required for `operator`, `secret-admin`, `platform-admin`,
`participant`, and `banned` changes. Every mutation requires a reason and is
written to `admin.audit_events`.

The existing static bearer-token environment variables remain as compatibility
fallbacks:

```text
ARENA_ADMIN_API_TOKEN
ANALYTICS_EXPORT_API_TOKEN
ADMIN_API_TOKEN
ARENA_ADMIN_API_ACTOR_ID
ANALYTICS_EXPORT_API_ACTOR_ID
ADMIN_API_ACTOR_ID
```

Bot-submission provisioning uses the scoped `ci` token path from the trusted
`Bot Submission Provision` `workflow_run` workflow. The pull-request workflow
that checks out submitted bot code does not receive these secrets or OIDC token
minting permission:

```text
ARENA_ADMIN_API_URL
ARENA_ADMIN_API_TOKEN
BOT_SUBMISSION_OPENBAO_MODE=real
ACTIONS_ID_TOKEN_REQUEST_URL
ACTIONS_ID_TOKEN_REQUEST_TOKEN
```

Current hosted value:

```text
ARENA_ADMIN_API_URL=https://reef-arena-admin.shrimpworks.dev
```

Keep this as configuration only. `shrimpworks.dev` is the current Cloudflare
zone for development projects, not a permanent product domain; moving to a new
domain should require changing Cloudflare DNS, backbone Caddy `API_DOMAIN`, and
the GitHub `ARENA_ADMIN_API_URL` secret, not changing code.

`ACTIONS_ID_TOKEN_REQUEST_URL` and `ACTIONS_ID_TOKEN_REQUEST_TOKEN` are supplied
by GitHub Actions when the workflow grants `id-token: write`. Local developer
checks should leave `BOT_SUBMISSION_OPENBAO_MODE` unset or set it to `dry-run`;
real local calls must explicitly set `BOT_SUBMISSION_OPENBAO_MODE=real` and may
provide `GITHUB_OIDC_TOKEN` when not running inside GitHub Actions. The default
GitHub OIDC audience requested by the provisioner is `reef-bot-submission-ci`,
matching the OpenBao `auth/jwt/role/reef-bot-submission-ci` bound audience seeded
by `infra/hetzner-core/server/scripts/configure-openbao.sh`.

The hosted Admin API does not trust the request body alone for the OpenBao path.
For `/admin/v1/arena/bots/openbao-provision`, `submitterIdentity` must exactly
match the GitHub Actions OIDC `actor` claim before any OpenBao write/delete is
attempted. If the target bot already has ownership metadata, that actor must be
an active owner or maintainer; add flows for new bots may create only a slice
under the OIDC actor's own submitter namespace.

When the Admin DB auth layer is configured, authenticated session and service
tokens bind the internal admin actor id from the trusted token record instead of
from caller-controlled headers. Static fallback tokens also bind the internal
admin actor server-side from `ARENA_ADMIN_API_ACTOR_ID`,
`ANALYTICS_EXPORT_API_ACTOR_ID`, or `ADMIN_API_ACTOR_ID`, falling back to
`ADMIN_ACTOR_ID` when a family-specific actor is not configured. They do not
trust `X-Reef-Actor-Id` from the caller.

## Participant Bot Config

Accepted bots use a bot-scoped OpenBao slice:

```text
secret/bots/<submitter-identity>/<bot-id>
```

Participants manage that slice through the Admin app's bot config panel. The
browser talks only to `/admin/v1/arena/bots/config`; it never receives an
OpenBao token. The API returns saved config values only after the same
owner-scoped authorization used for bot config edits, so another participant
cannot read a different bot owner's config.

Supported operations:

```text
GET    /admin/v1/arena/bots/config?botId=<bot-id>
PUT    /admin/v1/arena/bots/config
DELETE /admin/v1/arena/bots/config?botId=<bot-id>
```

The `PUT` body is:

```json
{
  "botId": "sample-bot",
  "config": {
    "apiKey": "stored-in-openbao",
    "riskLimit": 1000
  }
}
```

The Admin API validates that `config` is a JSON object, caps the serialized
payload at 65 KiB, allows at most 128 top-level keys, and requires each
top-level key to match `[A-Za-z0-9_.-]{1,64}`. Values may be arbitrary JSON so
bot authors are not forced into a fixed schema. Runtime descriptor preflight
still resolves only scalar string/number/boolean keys.

OpenBao stores the object as one opaque `config_json` string field plus metadata
(`config_schema`, `config_sha256`, `updated_at`, `updated_by`). This keeps the
participant-facing blob flexible and avoids returning or audit-recording secret
values through Reef. Reef Admin audit records for replace/delete include only
the bot id, OpenBao path, and key count/path metadata.

Authorization:

- the bot owner or maintainer can manage that bot's config unless banned
- `limited` users cannot write bot config
- operators/admins must not view or edit another user's bot config values
- `secret-admin` and `platform-admin` may clear or purge bot config through a
  separate audited repair path with a reason; `operator` may clear config only
  where an explicit operator-recovery action exists
- `/admin/v1/arena/bots/config` accepts only admin-family service tokens on the
  service-token path

Normal owner config updates do not require a user-entered reason. Reef audit
records still include actor, bot id, OpenBao path metadata, key count, timestamp,
and correlation id, and must never include secret values.

OpenBao access uses the dedicated `reef-platform-admin-bot-config` AppRole from
`infra/hetzner-core/server/scripts/configure-openbao.sh`. The runtime must have
these env vars in `/opt/reef/secrets/platform-runtime.env`:

```text
BAO_BOT_CONFIG_ROLE_ID=...
BAO_BOT_CONFIG_SECRET_ID=...
```

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

The trusted provisioning workflow posts or updates a PR comment after the hosted
OpenBao step. That comment includes only non-secret metadata: bot ID, submitter
identity, provisioning flow, the OpenBao slice path, and the next user/operator
action. It must never include token values, GitHub OIDC tokens, OpenBao tokens,
or bot secret data. The slice path is an identifier, not a public URL. Normal
participants use the Reef Admin secret/config surface; direct OpenBao access is
reserved for `secret-admin`/operator workflows over a private SSH tunnel.

The same trusted workflow also publishes a `registry-diff-and-provision` commit
status on the PR head SHA. Branch protection should require that explicit
status context, plus the untrusted PR-side `validate-manifest` and
`scan-and-sandbox-test` checks, rather than requiring the trusted workflow-run
job name directly. Non-bot branches get a successful
`registry-diff-and-provision` status with no hosted Admin API call; forked bot
submission branches get a failing status because they cannot receive trusted
provisioning privileges.

After merge, `.github/workflows/bot-registry-sync.yml` syncs changed
`bots/*/bot.json` manifests into the hosted arena registry. It registers the bot
metadata through `/admin/v1/arena/bots`, builds the hosted artifact to derive the
source/artifact hashes, then registers the merged version through
`/admin/v1/arena/bot-versions`. This is deliberately post-merge: pre-merge CI
can provision the OpenBao slice and block review, while the durable registry only
records code that actually landed on `master`/`main`.

Required before run:

- bot version is accepted
- bot is enabled
- owner trust state is `trusted`
- owner is not `limited` or `banned`
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
- Browser-session authorization uses Admin DB roles and trust state as the
  source of truth. The older runtime role/permission binding remains available
  for CLI, local setup, service-token actors, and non-browser command paths, but
  GitHub-authenticated browser sessions must not require a duplicate
  `arena.admin` runtime binding.

## Open Follow-Ups

- Implement Admin DB user, role, trust-state, ownership, and audit tables.
- ~~Add GitHub OAuth login to the admin app.~~ Done: `apps/arena-admin` has a
  working GitHub login, and Caddy now reverse-proxies `/admin/auth/*`,
  `/admin/v1/*`, and `/api/v1/*` same-origin with the static app (previously
  the browser-facing surface wasn't Caddy-exposed at all, only the CI
  bearer-token paths were).
- ~~Bridge the two disconnected admin identity/permission systems for Bot Arena
  browser sessions.~~ Done: trusted Admin DB `operator` and `platform-admin`
  users satisfy `arena.admin` for GitHub-authenticated browser requests without
  a duplicate runtime role binding. Runtime role bindings remain for CLI,
  service-token actors, and non-browser command paths.
- Run the local arena-admin auth smoke with real Admin DB auth, not fixture mode.
  `platform-api` must run with:

  ```text
  REEF_ENV=local
  PLATFORM_ADMIN_AUTH_ENABLED=true
  ADMIN_SESSION_COOKIE_SECURE=false
  ADMIN_POSTGRES_JDBC_URL=<optional; defaults to RUNTIME_POSTGRES_JDBC_URL>
  ADMIN_POSTGRES_USER=<optional; defaults to RUNTIME_POSTGRES_USER>
  ADMIN_POSTGRES_PASSWORD=<optional; defaults to RUNTIME_POSTGRES_PASSWORD>
  GITHUB_OAUTH_CLIENT_ID=<local GitHub OAuth app client id>
  GITHUB_OAUTH_CLIENT_SECRET=<local GitHub OAuth app secret>
  GITHUB_OAUTH_REDIRECT_URI=http://localhost:8080/admin/auth/github/callback
  LOCAL_DEV_ADMIN_UI_BASE_URL=http://localhost:5174
  ```

  `LOCAL_DEV_ADMIN_UI_BASE_URL` is ignored unless `REEF_ENV=local`. It keeps
  `redirectPath` relative and allowlisted while sending the browser back to the
  Vite admin UI after GitHub sets the platform session cookie.

  Keep these disabled for the live auth smoke:

  ```text
  LOCAL_DEV_ADMIN_AUTH_BYPASS=false
  PUBLIC_ARENA_LOCAL_DEV_FAKE_ADMIN=false
  PUBLIC_ARENA_LOCAL_DEV_FIXTURES=false
  ```

  Test flow:

  ```text
  1. Restart platform-api with the auth environment above and Admin DB access.
  2. Open http://127.0.0.1:5173/admin and complete GitHub OAuth once.
  3. Seed that existing Admin DB user:
     REEF_ENV=local make dev-admin-auth-local-seed ARGS="--github-login=<login> --role=operator"
  4. Run:
     make dev-smoke-admin-auth-local
  5. Start arena-admin without fixture flags and open /admin, /admin/access, and /bot-admin.
  ```

  `scripts/dev/admin-auth-local-seed.mjs` refuses to run unless an explicit
  local/dev/test profile is set and talks only to the local Docker Compose
  Postgres service. `scripts/dev/arena-admin-auth-local-smoke.mjs` fails if
  `LOCAL_DEV_ADMIN_AUTH_BYPASS` or the public arena-admin fixture flags are
  enabled.
- Guard production arena-admin builds against local fixture leakage:

  ```text
  bun run --cwd apps/arena-admin build:guarded
  ```

  The guard scans the static build output for local fixture marker strings and
  the public local-dev flag names. The fixture code is wrapped in
  `import.meta.env.DEV`, so production bundles should tree-shake it out.
- Add Admin API authorization middleware that binds actor identity from the
  authenticated principal, not caller-controlled headers.
- Move CI-to-Admin API auth from scoped bearer token to GitHub Actions OIDC.
- ~~Add pre-merge OpenBao-slice verification check.~~ Done:
  `scripts/dev/bot-submission-provision-openbao.mjs` calls
  `/admin/v1/arena/bots/openbao-provision` in real CI mode and keeps explicit
  dry-run mode for local workflow checks.
- Add participant config editor backed by Admin API and OpenBao.
- Define public bot submission contract after the current bot testing bugs are
  worked out.
