# Hetzner Core Deployment

This stack is the low-cost always-on core for Reef. It can still run the full
Compose bundle for smoke tests and admin workflows, but high-throughput
simulations should run on ephemeral compute and push artifacts back here.
OpenTofu owns durable cloud resources only. Secrets, OpenBao root material, R2
credentials, AppRole credentials, and local environment files stay outside
OpenTofu state and outside git.

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
GitHub Actions JWT provisioning policy. It also enables API-managed file audit
logging when the OpenBao image supports it; OpenBao 2.5 requires declarative
audit devices and the local helper reports that as a non-fatal skip. Run
`make backbone-local-init-openbao` directly if the Bao container was started
separately or restarted sealed.

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

If a public API is intentionally needed later, set `enable_public_web = true`,
set `api_domain`, open the matching host UFW ports, and run Compose with the
`public` profile so Caddy starts.

Current development domain target:

```text
api_domain = "reef-arena-admin.shrimpworks.dev"
ARENA_ADMIN_API_URL=https://reef-arena-admin.shrimpworks.dev
```

Treat `shrimpworks.dev` as a configurable development zone. If the public
control-plane domain changes, update Cloudflare DNS, `api_domain`/Caddy
`API_DOMAIN`, and the GitHub `ARENA_ADMIN_API_URL` secret together. Do not
hardcode this host in scripts.

When public Caddy is enabled, only narrow bearer-token admin gateway routes are exposed:

- `GET|POST /admin/v1/arena/bots`
- `POST /admin/v1/arena/bots/openbao-provision`
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
Generate simulator AppRole credentials only when needed:

```bash
BAO_TOKEN="..." /opt/reef/scripts/print-openbao-approle.sh reef-simulator
```

## Backup

Create `/opt/reef/secrets/backup.env` on the server:

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

Run non-destructive restore-list check against latest local encrypted archive:

```bash
/opt/reef/scripts/restore-backup-check.sh
```

Do not treat backups as reliable until this check passes with the real private
age identity.

## Soak Runs

Run deployment-shaped simulator traffic from the server's private Docker
network:

```bash
RATE=10000 DURATION=3m WORKERS=384 make hetzner-core ARGS=soak
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
