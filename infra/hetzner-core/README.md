# Hetzner Core Deployment

This stack is the low-cost always-on core for Reef. It can still run the full
Compose bundle for smoke tests and admin workflows, but high-throughput
simulations should run on ephemeral compute and push artifacts back here.
OpenTofu owns durable cloud resources only. Secrets, OpenBao root material, R2
credentials, AppRole credentials, and local environment files stay outside
OpenTofu state and outside git.

Operator references:

- [`CURRENT_STATE.md`](./CURRENT_STATE.md) records the current live backbone
  state, DNS, hardening, backup, and smoke evidence.
- [`SECRETS_CHECKLIST.md`](./SECRETS_CHECKLIST.md) records external tokens,
  host-generated secrets, offline recovery material, and integration secrets.
- [`OPERATIONS_RUNBOOK.md`](./OPERATIONS_RUNBOOK.md) is the step-by-step
  setup, monitoring, verification, and recovery runbook.
- [`BRINGUP_NOTES.md`](./BRINGUP_NOTES.md) records first bring-up issues and
  the repo changes that now cover them.

## Shape

- Hetzner CX33 by default
- Ubuntu 24.04
- Hetzner firewall with SSH restricted to `admin_cidrs`
- no public HTTP/HTTPS by default
- private Hetzner network reserved for future simulation workers
- Docker Compose on the host for Postgres, OpenBao, lightweight platform
  runtime/admin workflows, and optional smoke-test matching/simulator services
- platform runtime and OpenBao bind to host loopback only for SSH tunnels
- encrypted Postgres/OpenBao dumps uploaded to Cloudflare R2 by a host-side job

For budget control, this host should not be treated as the sustained simulation
compute tier. Use [`../simulation-runner`](../simulation-runner) for
DigitalOcean workers that are created for a run, fetch/push debug artifacts,
and then destroy themselves.

## Compose Ownership

The Hetzner backbone does not use the repository root local Compose files as a
base. It owns server-local Compose files under
[`server/`](./server/):

- `docker-compose.yml` for the always-on backbone services
- `docker-compose.stream-ack.yml` for the hosted stream-ack overlay

Root `compose.base.yml` and `compose.local.yml` are for local developer
workflows and for the current DigitalOcean bridge harness only.

## Provision

```bash
cd infra/hetzner-core/tofu
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars
```

The helper target loads `.env` from the repository root and maps
`HETZNER_TOKEN` to the provider variable `HCLOUD_TOKEN`, so this is enough:

```bash
HETZNER_TOKEN="..." # in .env, not committed

make hetzner-core-tofu ARGS=init
make hetzner-core-tofu ARGS="plan -out=tfplan"
make hetzner-core-tofu ARGS="apply tfplan"
```

Do not commit `terraform.tfvars`, state, plans, `.terraform/`, or generated
secrets.

## Deploy Server Files

The Compose file defaults to Docker Hub image names published by
`.github/workflows/container-images.yml`. Override them in `/opt/reef/.env` if
you publish from a fork or want to pin a specific tag:

```bash
cp /opt/reef/.env.example /opt/reef/.env
# edit image tags if needed
```

Before deploying the application containers, publish the images and confirm
their visibility. See [`DOCKERHUB.md`](./DOCKERHUB.md).

After the server exists:

```bash
IP="$(cd infra/hetzner-core/tofu && tofu output -raw core_ipv4)"

rsync -av infra/hetzner-core/server/ "ops@$IP:/opt/reef/"

ssh "ops@$IP" '
  chmod +x /opt/reef/scripts/*.sh
  cd /opt/reef
  ./scripts/generate-local-secrets.sh
  docker compose up -d postgres postgres-admin postgres-analytics openbao
'
```

For repeatable deploys after the first server is provisioned:

```bash
make hetzner-core ARGS=deploy
make hetzner-core ARGS=status
```

## Local Backbone Stack

The root Compose files are for local simulation/runtime development. The
backbone has its own local stack using the deploy-shaped files under
`infra/hetzner-core/server/`, with a separate Compose project and non-conflicting
host ports.

```bash
make backbone-local-up-infra
make backbone-local-init-openbao
make backbone-local-up
make backbone-local-status
make backbone-local-down
```

Defaults:

```text
COMPOSE_PROJECT_NAME=reef-backbone-local
REEF_BACKBONE_PLATFORM_HOST_PORT=18180
REEF_BACKBONE_OPENBAO_HOST_PORT=18200
```

That allows the backbone Admin API and OpenBao to run beside a simulation stack
using the root `make dev-up` workflow. The local helper generates ignored
secrets under `infra/hetzner-core/server/secrets/`, builds local service images
through `docker-compose.local.yml`, applies migrations to the main, admin, and
analytics Postgres containers, and runs the backbone runtime verifier.

For local development only, `make backbone-local-up-infra` and
`make backbone-local-up` also initialize and unseal OpenBao when needed. The
one-shot local root token and unseal key are saved to ignored files under
`infra/hetzner-core/server/secrets/`, then the normal Reef OpenBao bootstrap
runs to configure the `secret/` KV v2 mount, runtime AppRole policies, and
GitHub Actions JWT provisioning policy. OpenBao 2.5 audit logging is configured
declaratively in `server/openbao/config/openbao.hcl`; the bootstrap script keeps
the older API-managed audit attempt as a compatibility fallback and reports the
expected 2.5 skip as non-fatal. Run `make backbone-local-init-openbao` directly
if the Bao container was started separately or restarted sealed.

Hosted OpenBao must still use the manual threshold init flow below. Do not use
the local single-key init script for hosted deployments.

`deploy` syncs the server bundle, syncs SQL migrations from
`scripts/dev/db/migrations`, generates missing local env files, starts
Postgres/OpenBao/matching-engine, applies migrations, and then starts the full
Compose stack.

Admin and analytics data live in two dedicated Postgres containers
(`postgres-admin`, `postgres-analytics`), separate from the main `reef`
database used for trading/runtime state and separate from each other - see
[D-046](../../docs/DECISIONS.md). Migrations for those domains still live under
`scripts/dev/db/migrations/`; `deploy` applies `admin` and `arena` domains to
`postgres-admin`, and `analytics` to `postgres-analytics`, through
`REEF_MIGRATION_DOMAINS`/`REEF_POSTGRES_SERVICE`/`REEF_POSTGRES_DB` overrides to
`apply-migrations.sh`.

If Docker Hub images are not public or ready yet, build the images on the Hetzner
host and use local image tags:

```bash
make hetzner-core ARGS=build-local-images
make hetzner-core ARGS=deploy
```

Verify the running stack after deployment:

```bash
make hetzner-core ARGS=verify
```

Run the quick operator check for DNS, public exposure, container status,
platform health, OpenBao seal state, backup timer state, and encrypted backup
presence:

```bash
make hetzner-core ARGS=ops-check
```

When public ingress is enabled, expect 80/443 to be open:

```bash
PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
```

Start the JetStream-backed stream-ack profile used by the local and
DigitalOcean throughput work:

```bash
make hetzner-core ARGS=stream-ack
```

The platform runtime is private by default. Use an SSH tunnel for operator
access:

```bash
ssh -L 8080:127.0.0.1:8080 "ops@$IP"
curl http://127.0.0.1:8080/health
```

For public API/admin ingress, set `api_domain` and `cloudflare_zone_id` so
OpenTofu manages the Cloudflare `A` record for the server IPv4 address. Keep
`enable_public_web = false` only for internal-only deployments or before the
host is ready to expose Caddy; DNS can be provisioned ahead of public ingress.

Current development domain target:

```text
api_domain = "reef-arena-admin.shrimpworks.dev"
ARENA_ADMIN_API_URL=https://reef-arena-admin.shrimpworks.dev
```

Cloudflare DNS management requires `CLOUDFLARE_API_TOKEN` in the repository
root `.env` or `infra/hetzner-core/tofu/.env`. `CLOUDFLARE_TOKEN` is also
accepted by the wrapper and mapped to `CLOUDFLARE_API_TOKEN`.

Cloudflare token permissions:

| Use | Scope | Required permissions |
| --- | --- | --- |
| DNS record only | Zone `shrimpworks.dev` | `Zone:Zone:Read`, `Zone:DNS:Write` |
| DNS plus OpenTofu-managed R2 bucket | Zone `shrimpworks.dev` and account `21f7217c1f4e4a4da99f20b12c85a463` | DNS permissions plus account-level `Workers R2 Storage Write` |
| Host backup object upload | Bucket `reef-backups` | Separate R2 S3 access key with `Object Read & Write`; store as `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` |

Keep the OpenTofu Cloudflare token separate from the R2 object upload key. The
Tofu token manages Cloudflare infrastructure; the backup key only writes
encrypted backup objects into `reef-backups`.

When ready to expose the public API, set `enable_public_web = true` and apply
OpenTofu so the Hetzner firewall opens 80/443. Then start or stop the host-side
Caddy ingress with:

```bash
make hetzner-core ARGS=public-up
make hetzner-core ARGS=public-down
```

`public-up` syncs the server bundle and admin static app, sets `API_DOMAIN`,
opens host UFW 80/443, and starts the Compose `public` profile. It force
recreates Caddy after syncing so the container remounts the current
bind-mounted `Caddyfile`; a plain reload can keep seeing the old file inode
after `rsync`. `public-down` stops/removes the Caddy container and closes host
UFW 80/443.

Treat `shrimpworks.dev` as a configurable development zone. If the public
control-plane domain changes, update the OpenTofu `api_domain`, Caddy
`API_DOMAIN`, and the GitHub `ARENA_ADMIN_API_URL` secret together. Do not
hardcode this host in scripts.

### Admin UI auto-deploy

`.github/workflows/admin-ui-deploy.yml` deploys only the static
`apps/arena-admin` build to the backbone host. It runs on pushes to
`master`/`main` that touch the admin app, the Hetzner deploy helper, or the
workflow itself, and it can also be run manually with `workflow_dispatch`.

The workflow calls the same operator command used locally:

```bash
bun scripts/deploy/hetzner-core.mjs arena-admin
```

That command builds the SvelteKit static app with an empty
`PUBLIC_ARENA_API_BASE_URL` so browser requests stay same-origin behind Caddy,
then rsyncs `apps/arena-admin/build/` to `/opt/reef/arena-admin/`. It does not
restart `platform-runtime`, OpenBao, Postgres, or Caddy; API/runtime deploys
remain manual until a separate promoted runtime deploy workflow exists.

Required GitHub repository secrets:

| Name | Purpose |
| --- | --- |
| `REEF_HETZNER_HOST` | Backbone server IPv4 or DNS name. |
| `REEF_HETZNER_SSH_PRIVATE_KEY` | Private key for a deploy-capable SSH principal on the backbone host. Prefer a dedicated key scoped to the `ops` account. |
| `REEF_HETZNER_SSH_KNOWN_HOSTS` | Pinned host key line(s), for example from `ssh-keyscan -H <host>` after verifying the fingerprint out of band. |

Optional GitHub repository/environment variables:

| Name | Default | Purpose |
| --- | --- | --- |
| `REEF_HETZNER_OPS_USER` | `ops` | SSH user for the deploy command. |
| `REEF_HETZNER_DEPLOY_DIR` | `/opt/reef` | Host deploy directory. |

Use the GitHub Actions `backbone-admin` environment for deployment protection
rules if you want human approval before publishing admin UI changes.

When public Caddy is enabled, only narrow bearer-token admin gateway routes are exposed:

- `GET|POST /admin/v1/arena/bots`
- `POST /admin/v1/arena/bots/openbao-provision`
- `GET /admin/v1/arena/runs`
- `GET /admin/v1/arena/run-bot-results`
- `GET /admin/v1/arena/run-enforcement-events`
- `GET /admin/v1/arena/leaderboard`
- `POST /admin/v1/analytics/run-exports`
- `GET /admin/v1/analytics/run-bot-summaries`

The analytics export route uses `ANALYTICS_EXPORT_API_TOKEN` from
`/opt/reef/secrets/caddy.env`. Keep raw export read/list access tunnel-only.

Initialize and unseal OpenBao manually through an SSH tunnel. Store unseal keys
and the root token in an offline vault or password manager, not on the server.

```bash
ssh -L 8200:127.0.0.1:8200 "ops@$IP"
export BAO_ADDR="http://127.0.0.1:8200"
bao operator init -key-shares=5 -key-threshold=3
```

After OpenBao is initialized, unsealed, and you have a valid root/admin token:

```bash
BAO_TOKEN="..." /opt/reef/scripts/configure-openbao.sh
BAO_TOKEN="..." /opt/reef/scripts/print-openbao-approle.sh reef-platform-runtime
```

Append the printed `BAO_ROLE_ID` and `BAO_SECRET_ID` to
`/opt/reef/secrets/platform-runtime.env`, then restart the platform runtime.
Future deploys preserve existing platform-runtime and simulator AppRole env
entries when regenerating local service secret files. Generate simulator
AppRole credentials only when needed:

```bash
BAO_TOKEN="..." /opt/reef/scripts/print-openbao-approle.sh reef-simulator
```

### OpenBao Unseal After Restart

Hosted OpenBao uses Shamir unseal keys. After a host reboot, Docker restart, or
OpenBao container recreation, check seal state:

```bash
ssh "ops@$IP"
cd /opt/reef
curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed}'
```

If `sealed` is `true`, retrieve any three unseal keys from the offline vault and
run:

```bash
docker compose exec openbao bao operator unseal
docker compose exec openbao bao operator unseal
docker compose exec openbao bao operator unseal
curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed}'
```

Do not keep root tokens or unseal keys on the host. Use the root/admin token
only for deliberate OpenBao administration, then return it to the offline vault.

## Backup

Create `/opt/reef/secrets/backup.env` on the server. `AGE_RECIPIENT` is
required. R2 settings are optional but strongly recommended for off-host
scheduled backups; when they are omitted, the script still writes an encrypted
archive under `/opt/reef/backups`.

```bash
R2_ENDPOINT="https://<account-id>.r2.cloudflarestorage.com"
R2_BUCKET="reef-backups"
AWS_ACCESS_KEY_ID="..."
AWS_SECRET_ACCESS_KEY="..."
AWS_DEFAULT_REGION="auto"
AGE_RECIPIENT="age1..."
# For restore checks only; private identity path stays on operator-controlled host.
AGE_IDENTITY_FILE="/opt/reef/secrets/age-identity.txt"
```

Then run one backup:

```bash
/opt/reef/scripts/backup-dbs.sh
```

Install daily systemd backup timer:

```bash
make hetzner-core ARGS=backup-timer
```

For the normal hosted path, automate the local age identity, server
`backup.env`, first encrypted backup, local archive copy, and timer install:

```bash
make hetzner-core ARGS=backup-bootstrap
```

Defaults:

```text
REEF_BACKUP_AGE_IDENTITY_PATH=~/Documents/reef-backups-age-identity.txt
REEF_BACKUP_ARCHIVE_DIR=~/Documents
```

Set `R2_ENDPOINT`, `R2_BUCKET`, `AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, and optionally `AWS_DEFAULT_REGION` in the local
environment before running `backup-bootstrap` when R2 upload should be enabled.
When `r2_backup_bucket` and `cloudflare_account_id` are configured in
OpenTofu, `backup-bootstrap` can derive `R2_ENDPOINT` and `R2_BUCKET` from
Tofu outputs; the R2 access key id and secret still stay in operator secrets,
not Tofu state.

R2 storage must stay under the `10 GB` free-tier target. `backup-dbs.sh`
defaults to `R2_MAX_BYTES=8589934592` (`8 GiB`) and `R2_MAX_BACKUPS=7` for the
remote `db/reef-db-*.tar.gz.age` backup objects. Before upload it lists existing
R2 backup objects, deletes oldest matching backups until the incoming archive
fits both limits, and refuses upload if it cannot verify the remote usage
budget.

Backups are compressed before they touch R2: each database is dumped with
PostgreSQL custom-format compression, the dump set is packed as
`reef-db-<timestamp>.tar.gz`, and only then age-encrypted to
`reef-db-<timestamp>.tar.gz.age`.

Run non-destructive restore-list check against latest local encrypted archive:

```bash
make hetzner-core ARGS=backup-restore-check
```

This copies only the encrypted archive from the host, decrypts locally with the
operator-held age identity, and runs `pg_restore --list` for each dump. It
requires local `age` and `pg_restore` binaries. Do not treat backups as reliable
until this check passes with the real private age identity.

## Soak Runs

Run deployment-shaped simulator traffic from the server's private Docker
network:

```bash
RATE=10000 DURATION=3m WORKERS=384 make hetzner-core ARGS=soak
```

The hosted runtime uses `EXTERNAL_API_AUTH_MODE=static-token`. The secret
generator creates a simulator bearer token, maps `sim-client-*` entries into
`EXTERNAL_API_TOKENS`, and writes the matching `REEF_API_BEARER_TOKEN` into
`secrets/simulator.env`; do not replace those with hardcoded values.

Run the default hosted smoke gate:

```bash
make hetzner-core ARGS=hosted-smoke
```

The hosted smoke temporarily enables the tunnel-only legacy setup route needed
for simulator reference-data seeding, waits for runtime health, runs the gate,
and disables that route again before returning.

The gate fails if `systemFailureCount` is nonzero, valid-intent success is below
`100`, or any trace check fails. Override `RATE`, `DURATION`, `WORKERS`, or
`TRACE_CHECK_LIMIT` only when intentionally changing the gate.

## Hosted Admin Auth

After creating the GitHub OAuth app and setting local
`GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`, and
`GITHUB_OAUTH_REDIRECT_URI`, persist the hosted admin auth config with:

```bash
make hetzner-core ARGS=admin-auth-up
```

To grant the first hosted admin user, use the authenticated GitHub CLI user or
set `ADMIN_GITHUB_USER_ID` explicitly, then run:

```bash
make hetzner-core ARGS=admin-role-grant
```

`admin-role-grant` temporarily enables the tunnel-only legacy mutation route,
grants `arena.admin` to `user-gh-<numeric-id>`, then disables the route again.
Verify it stays disabled with a host-local POST to `/auth/roles` returning
`403`.

Run the hosted auth smoke after public ingress, Caddy, or admin auth changes:

```bash
make hetzner-core ARGS=admin-auth-smoke
```

For stream-ack submit-spread probes, target the stream-ack API container and
generate the same 64-instrument submit-only session shape used locally:

```bash
RATE=1000 \
DURATION=30s \
WORKERS=256 \
BASE_URL=http://platform-api:8080 \
HEALTH_URL=none \
COMPOSE_STREAM_ACK=1 \
DIRECT_DOCKER_RUN=1 \
SUBMIT_PCT=100 \
MODIFY_PCT=0 \
CANCEL_PCT=0 \
STREAM_ACK_SPREAD_INSTRUMENTS=64 \
TRACE_CHECK_LIMIT=0 \
make hetzner-core ARGS=soak
```

The host script writes reports to `/opt/reef/reports/soak` and prints a compact
JSON summary. Gate promotion runs on completed throughput, trace checks, system
failure rate, and accounting evidence, not only process exit status.

Bring-up issues and the scripts added to cover them are tracked in
[`BRINGUP_NOTES.md`](./BRINGUP_NOTES.md).
