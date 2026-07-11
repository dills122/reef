# Hetzner Core Operations Runbook

This is the operator checklist for the permanent Reef backbone. It is written
so a future operator can rebuild, verify, monitor, and recover the stack without
guessing. It intentionally avoids secret values.

For the live snapshot, see [`CURRENT_STATE.md`](./CURRENT_STATE.md). For
required tokens and recovery material, see
[`SECRETS_CHECKLIST.md`](./SECRETS_CHECKLIST.md).

## Ownership Boundaries

OpenTofu owns durable cloud resources:

- Hetzner server, network, and firewall
- Cloudflare DNS record
- Cloudflare R2 backup bucket, once the Cloudflare token has R2 permission

The Hetzner host owns runtime state:

- `/opt/reef`
- Docker Compose services
- host-generated secrets under `/opt/reef/secrets`
- OpenBao storage and audit logs
- encrypted backup archives under `/opt/reef/backups`
- smoke and soak reports under `/opt/reef/reports/soak`

Operator-held recovery material stays off-host:

- OpenBao unseal keys
- OpenBao root/admin token
- local age private identity
- R2 object write credentials
- GitHub OAuth client secret

Do not commit `terraform.tfvars`, `.env`, `.terraform/`, state files, plans,
host secrets, unseal material, or backup private keys.

## Critical Paths

Local operator paths:

- repository: `/Users/dsteele/repos/reef`
- Hetzner Tofu config: `infra/hetzner-core/tofu`
- ignored Tofu vars: `infra/hetzner-core/tofu/terraform.tfvars`
- local provider/env tokens: repository root `.env`
- local backup age identity: `~/Documents/reef-backups-age-identity.txt`
- local encrypted backup copies: `~/Documents/reef-db-*.tar.gz.age`; legacy
  first-bring-up copies may use `.tar.age`

Host paths:

- deploy root: `/opt/reef`
- Compose bundle: `/opt/reef/docker-compose.yml`
- generated runtime secrets: `/opt/reef/secrets`
- OpenBao config: `/opt/reef/openbao/config/openbao.hcl`
- OpenBao audit log: `/opt/reef/openbao/logs/audit.log`
- encrypted backups: `/opt/reef/backups`
- smoke/soak reports: `/opt/reef/reports/soak`
- backup timer unit: `reef-backup.timer`

## Command Catalog

Infrastructure:

```bash
make hetzner-core-tofu ARGS=init
make hetzner-core-tofu ARGS="plan -no-color"
make hetzner-core-tofu ARGS="apply -auto-approve -no-color"
```

Deployment and status:

```bash
make hetzner-core ARGS=deploy
make hetzner-core ARGS=status
make hetzner-core ARGS=verify
make hetzner-core ARGS=ops-check
PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
```

Post-merge Admin bot-config/OpenBao setup:

```bash
BAO_TOKEN="..." make hetzner-core ARGS=bot-config-upgrade
# or, without exporting the token:
REEF_OPENBAO_INIT_JSON=~/Documents/reef-openbao-init-167.233.82.255.json make hetzner-core ARGS=bot-config-upgrade
```

This one-time helper syncs server files, rebuilds/syncs the Admin UI, updates
the OpenBao `reef-platform-admin-bot-config` policy/AppRole, writes
`BAO_BOT_CONFIG_ROLE_ID` and `BAO_BOT_CONFIG_SECRET_ID` into
`/opt/reef/secrets/platform-runtime.env`, and restarts `platform-runtime`.
It binds the GitHub Actions OIDC role to `REEF_GITHUB_REPOSITORY`, deriving it
from the local `origin` remote when the variable is not set. If `BAO_TOKEN` and
`REEF_OPENBAO_INIT_JSON` are both omitted, it still syncs deploy assets and
prints the remaining manual OpenBao steps.

Public ingress:

```bash
make hetzner-core ARGS=public-up
make hetzner-core ARGS=public-down
```

Backups:

```bash
make hetzner-core ARGS=backup-bootstrap
make hetzner-core ARGS=backup-timer
make hetzner-core ARGS=backup-restore-check
```

Hosted admin auth:

```bash
make hetzner-core ARGS=admin-auth-up
make hetzner-core ARGS=admin-auth-down
make hetzner-core ARGS=admin-role-grant
make hetzner-core ARGS=admin-auth-smoke
```

Simulation gates:

```bash
make hetzner-core ARGS=hosted-smoke
make hetzner-core ARGS=soak
make hetzner-core ARGS=stream-ack
```

Build/deploy fallback:

```bash
make hetzner-core ARGS=build-local-images
make hetzner-core ARGS=arena-admin
make hetzner-core ARGS=deploy-receiver-up
make hetzner-core ARGS=sync
make hetzner-core ARGS=migrations
```

Admin UI auto-deploy uses the public OIDC deploy receiver, not SSH from GitHub
runners. After receiver or Caddy changes, apply the host-side service update
before expecting the workflow to pass:

```bash
make hetzner-core ARGS=deploy-receiver-up
```

## Bootstrap Order

Use this order for a clean rebuild or new permanent host.

1. Prepare local `.env` with provider tokens:

   ```text
   HETZNER_TOKEN=...
   CLOUDFLARE_TOKEN=...
   ```

   `CLOUDFLARE_API_TOKEN` is also accepted. The wrapper maps
   `HETZNER_TOKEN` to `HCLOUD_TOKEN` and `CLOUDFLARE_TOKEN` to
   `CLOUDFLARE_API_TOKEN`. For DNS-only Tofu work, the Cloudflare token needs
   `Zone:Zone:Read` and `Zone:DNS:Write` scoped to `shrimpworks.dev`. For R2
   bucket creation, add account-level `Workers R2 Storage Write` scoped to the
   Cloudflare account.

2. Prepare `infra/hetzner-core/tofu/terraform.tfvars`:

   ```text
   ssh_public_key_path = "~/.ssh/id_ed25519.pub"
   admin_cidrs         = ["<operator-public-ip>/32"]
   api_domain          = "reef-arena-admin.shrimpworks.dev"
   cloudflare_zone_id  = "<zone-id>"
   enable_public_web   = true
   ```

   Add R2 fields when the Cloudflare token can manage R2:

   ```text
   cloudflare_account_id = "<account-id>"
   r2_backup_bucket      = "reef-backups"
   ```

3. Provision infrastructure:

   ```bash
   make hetzner-core-tofu ARGS=init
   make hetzner-core-tofu ARGS="plan -out=tfplan"
   make hetzner-core-tofu ARGS="apply tfplan"
   ```

4. Deploy runtime files and containers:

   ```bash
   make hetzner-core ARGS=deploy
   make hetzner-core ARGS=verify
   ```

5. Initialize OpenBao through an SSH tunnel:

   ```bash
   IP="$(cd infra/hetzner-core/tofu && tofu output -raw core_ipv4)"
   ssh -L 8200:127.0.0.1:8200 "ops@$IP"
   export BAO_ADDR=http://127.0.0.1:8200
   bao operator init -key-shares=5 -key-threshold=3
   ```

   Store the unseal keys and root/admin token in the offline vault. Do not leave
   them on the host.

6. Configure OpenBao with a root/admin token:

   ```bash
   ssh "ops@$IP"
   cd /opt/reef
   REEF_GITHUB_REPOSITORY=dills122/reef BAO_TOKEN="..." ./scripts/configure-openbao.sh
   BAO_TOKEN="..." ./scripts/print-openbao-approle.sh reef-platform-runtime
   BAO_TOKEN="..." ./scripts/print-openbao-approle.sh reef-platform-admin-bot-config
   ```

   Append the printed runtime AppRole values as `BAO_ROLE_ID` /
   `BAO_SECRET_ID`, and the printed Admin bot-config values as
   `BAO_BOT_CONFIG_ROLE_ID` / `BAO_BOT_CONFIG_SECRET_ID`, to
   `/opt/reef/secrets/platform-runtime.env`, then restart:

   ```bash
   docker compose up -d --force-recreate platform-runtime
   ```

   For post-merge upgrades on an already initialized host, prefer the scripted
   form from the command catalog:

   ```bash
   BAO_TOKEN="..." make hetzner-core ARGS=bot-config-upgrade
   REEF_OPENBAO_INIT_JSON=~/Documents/reef-openbao-init-167.233.82.255.json make hetzner-core ARGS=bot-config-upgrade
   ```

7. Enable public Caddy after DNS and firewall are intentional:

   ```bash
   make hetzner-core ARGS=public-up
   PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
   ```

8. Bootstrap backups:

   ```bash
   make hetzner-core ARGS=backup-bootstrap
   make hetzner-core ARGS=backup-restore-check
   ```

9. Enable hosted GitHub OAuth after the OAuth app exists:

   ```bash
   make hetzner-core ARGS=admin-auth-up
   make hetzner-core ARGS=admin-role-grant
   ```

10. Prove the runtime path:

    ```bash
    make hetzner-core ARGS=hosted-smoke
    PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
    ```

## Health Monitoring

There is not yet a separate alerting stack. Until one exists, treat this table
as the minimum monitor set to run manually or wire into an external checker.

| Check | Command or Probe | Expected |
| --- | --- | --- |
| Infrastructure drift | `make hetzner-core-tofu ARGS="plan -no-color"` | No changes |
| Operator summary | `PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check` | Exit `0`; DNS matches server IP; 22/80/443 open; 8080/8200 closed; Bao unsealed; backup timer active |
| Runtime verify | `make hetzner-core ARGS=verify` | Exit `0`; DB schemas visible; platform health OK |
| Hosted smoke | `make hetzner-core ARGS=hosted-smoke` | `systemFailureCount=0`, valid-intent success `100`, trace checks pass |
| Hosted admin auth smoke | `make hetzner-core ARGS=admin-auth-smoke` | Exit `0`; `/admin` shell, unauth session `401`, OAuth GitHub `302`, Caddy fallback, and legacy route cleanup all pass |
| HTTPS app | `curl -fsS -o /dev/null -w "%{http_code}\n" https://reef-arena-admin.shrimpworks.dev/` | `200` |
| Admin shell | `curl -fsS https://reef-arena-admin.shrimpworks.dev/admin` | HTML contains the admin route shell, not the landing page content |
| Admin session gate | `curl -fsS -o /dev/null -w "%{http_code}\n" https://reef-arena-admin.shrimpworks.dev/admin/auth/session` | `401` without a session cookie |
| HTTP redirect | `curl -fsS -o /dev/null -w "%{http_code} %{redirect_url}\n" http://reef-arena-admin.shrimpworks.dev/` | `308 https://reef-arena-admin.shrimpworks.dev/` |
| OAuth start | `curl -fsS -o /dev/null -w "%{http_code} %{redirect_url}\n" "https://reef-arena-admin.shrimpworks.dev/admin/auth/github/start?redirectPath=/admin"` | `302` to `github.com/login/oauth/authorize` |
| Public admin gate | `curl -fsS -o /dev/null -w "%{http_code}\n" https://reef-arena-admin.shrimpworks.dev/admin/v1/arena/bots` | `401` without bearer token |
| Admin deploy receiver gate | `curl -fsS -o /dev/null -w "%{http_code}\n" -X POST https://reef-arena-admin.shrimpworks.dev/admin/deploy/arena-admin` | `401` without GitHub OIDC bearer token |
| Runtime private port | public TCP probe to `167.233.82.255:8080` | Closed |
| OpenBao private port | public TCP probe to `167.233.82.255:8200` | Closed |
| Legacy mutation route | host-local POST to `/auth/roles` with internal marker | `403` outside scripted temporary windows |
| Backup timer | `ssh ops@167.233.82.255 'systemctl is-enabled reef-backup.timer && systemctl is-active reef-backup.timer'` | `enabled`, `active` |
| Backup restore-list | `make hetzner-core ARGS=backup-restore-check` | `restore-list ok` for `openbao`, `reef`, `admin`, `analytics` |

Recommended cadence:

- every deploy: `ops-check`, `verify`, hosted smoke
- daily: backup timer status and latest archive presence
- weekly: `backup-restore-check`
- after reboot: SSH reachability, Bao seal state, unseal if needed, `ops-check`
- after any token/auth/Caddy/firewall change: public probes and hosted smoke

## Service Inspection

SSH to the host:

```bash
ssh ops@167.233.82.255
cd /opt/reef
```

Common service commands:

```bash
docker compose ps
docker compose logs --tail=200 platform-runtime
docker compose logs --tail=200 caddy
docker compose logs --tail=200 openbao
docker compose stats --no-stream
```

Host services:

```bash
systemctl status reef-backup.timer --no-pager
systemctl status reef-backup.service --no-pager
journalctl -u reef-backup.service -n 200 --no-pager
```

OpenBao state:

```bash
curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed,type,t,n,version,storage_type}'
```

## OpenBao Runbook

OpenBao is initialized with Shamir `5` shares and threshold `3`.

After host reboot or OpenBao container recreation:

```bash
ssh ops@167.233.82.255
cd /opt/reef
curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed}'
```

If sealed:

```bash
docker compose exec openbao bao operator unseal
docker compose exec openbao bao operator unseal
docker compose exec openbao bao operator unseal
curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed}'
```

Only use the root/admin token for deliberate Bao administration. Return it to
the offline vault afterward. Runtime services should use AppRole credentials.

## Public Ingress Runbook

Public web exposure has two layers:

- Hetzner firewall via OpenTofu `enable_public_web=true`
- host UFW and Caddy via `make hetzner-core ARGS=public-up`

Enable:

```bash
make hetzner-core-tofu ARGS="apply -auto-approve -no-color"
make hetzner-core ARGS=public-up
PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
```

Disable:

```bash
make hetzner-core ARGS=public-down
# then set enable_public_web=false in terraform.tfvars and apply
make hetzner-core-tofu ARGS="apply -auto-approve -no-color"
make hetzner-core ARGS=ops-check
```

Caddy should expose only the narrow public routes listed in
[`README.md`](./README.md). Raw platform runtime and OpenBao host ports must
remain loopback-only and closed publicly.

`public-up` deliberately recreates the Caddy container after syncing
`/opt/reef/Caddyfile`. The Caddyfile is a bind-mounted file, and `rsync` can
replace the host inode while the running container still sees the old one. If
`/admin` serves landing-page content after a Caddyfile change, rerun
`make hetzner-core ARGS=public-up` and confirm the container-visible file:

```bash
ssh ops@167.233.82.255 \
  "cd /opt/reef && docker compose exec -T caddy sed -n '60,68p' /etc/caddy/Caddyfile"
```

## GitHub OAuth Runbook

GitHub OAuth app settings:

```text
Homepage URL: https://reef-arena-admin.shrimpworks.dev
Authorization callback URL: https://reef-arena-admin.shrimpworks.dev/admin/auth/github/callback
```

Local operator env:

```text
PLATFORM_ADMIN_AUTH_ENABLED=true
GITHUB_OAUTH_CLIENT_ID=...
GITHUB_OAUTH_CLIENT_SECRET=...
GITHUB_OAUTH_REDIRECT_URI=https://reef-arena-admin.shrimpworks.dev/admin/auth/github/callback
```

Apply hosted OAuth config:

```bash
make hetzner-core ARGS=admin-auth-up
```

Grant the first admin role:

```bash
# optional override if gh is authenticated as the wrong account
ADMIN_GITHUB_USER_ID=<numeric-github-user-id> make hetzner-core ARGS=admin-role-grant
```

`admin-role-grant` temporarily enables the tunnel-only legacy mutation route,
creates `arena-operator` with `arena.admin`, assigns it to
`user-gh-<numeric-id>`, then disables the route again.

Grant the bot-submission CI service actor before enabling the real hosted
OpenBao provisioning workflow:

```bash
ADMIN_ACTOR_ID=bot-submission-ci make hetzner-core ARGS=admin-actor-role-grant
```

`admin-actor-role-grant` uses the same temporary legacy-route window as
`admin-role-grant`, creates or updates the target role, assigns it to the
service actor, then disables the legacy route again. The default actor is
`bot-submission-ci`, default role is `arena-operator`, and default permission is
`arena.admin`.

Verify:

```bash
make hetzner-core ARGS=admin-auth-smoke

curl -fsS -o /dev/null -w "%{http_code} %{redirect_url}\n" \
  "https://reef-arena-admin.shrimpworks.dev/admin/auth/github/start?redirectPath=/admin"

curl -fsS -o /dev/null -w "%{http_code}\n" \
  https://reef-arena-admin.shrimpworks.dev/admin/auth/session

ssh ops@167.233.82.255 \
  "cd /opt/reef && docker compose exec -T platform-runtime env | grep '^PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED='"
```

Expected:

- `admin-auth-smoke` passes
- OAuth start returns `302` to GitHub
- unauthenticated session returns `401`
- authenticated session JSON includes `githubLogin`, `roles`, `displayName`,
  and `trustState`
- `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=false`
- host-local legacy mutation route returns `403` after cleanup

## Backup And R2 Runbook

Local encrypted host backups work without R2. Off-host upload requires:

- Cloudflare R2 bucket, intended name `reef-backups`
- R2 object access key id and secret scoped to that bucket
- local age identity under the operator-controlled machine

Use separate Cloudflare credentials for infrastructure and backup object
upload:

| Credential | Used by | Scope | Permissions |
| --- | --- | --- | --- |
| `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_TOKEN` | OpenTofu DNS | Zone `shrimpworks.dev` | `Zone:Zone:Read`, `Zone:DNS:Write` |
| same OpenTofu token, if Tofu should create R2 bucket | OpenTofu R2 bucket resource | Cloudflare account `21f7217c1f4e4a4da99f20b12c85a463` | `Workers R2 Storage Write` |
| R2 S3 access key pair | Host backup upload job | Bucket `reef-backups` | `Object Read & Write` |

The DNS-only token is enough for `cloudflare_dns_record.api`. It is not enough
for `cloudflare_r2_bucket.backups`.

Cloudflare R2 must also be enabled once in the dashboard for account
`21f7217c1f4e4a4da99f20b12c85a463`. If OpenTofu can plan
`cloudflare_r2_bucket.backups` but apply fails with Cloudflare error `10042`
and `Please enable R2 through the Cloudflare Dashboard`, enable R2 for the
account in Cloudflare, then rerun the apply.

Apply bucket once the Cloudflare token has permission and R2 is enabled:

```bash
make hetzner-core-tofu ARGS="plan -no-color"
make hetzner-core-tofu ARGS="apply -auto-approve -no-color"
```

Create an R2 object token in Cloudflare scoped to `reef-backups` with
`Object Read & Write`, then set:

```text
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_DEFAULT_REGION=auto
```

`R2_ENDPOINT` and `R2_BUCKET` can come from OpenTofu outputs if the bucket is
managed there.

R2 free-tier storage budget is treated as a hard cap. The backup script defaults
to:

```text
R2_BACKUP_PREFIX=db/
R2_MAX_BACKUPS=7
R2_MAX_BYTES=8589934592
```

`R2_MAX_BYTES` is `8 GiB`, leaving margin under the `10 GB` free-tier target.
Database dumps are first written with PostgreSQL custom-format compression,
then packed into a gzip-compressed tar archive, then age-encrypted as
`reef-db-<timestamp>.tar.gz.age`. Before every upload, `backup-dbs.sh` lists
existing `reef-db-*.tar.gz.age` backup objects under `R2_BACKUP_PREFIX`, deletes
oldest matching backups until both the object-count and byte budgets can fit the
incoming encrypted archive, and refuses upload if it cannot prove the bucket
will remain inside budget.

Bootstrap and verify:

```bash
make hetzner-core ARGS=backup-bootstrap
make hetzner-core ARGS=backup-restore-check
```

Do not send the age private identity to the server. Restore-list checks decrypt
locally and validate dump catalogs only; a full restore drill is a separate
future exercise.

## Hosted Smoke And Soak Runbook

Default smoke:

```bash
make hetzner-core ARGS=hosted-smoke
```

The smoke wrapper temporarily enables the host-local legacy setup route for
reference-data seeding, waits for runtime health, runs the simulator, then
disables the route again even if the run fails.

Successful smoke requires:

- `systemFailureCount=0`
- valid-intent success rate `100`
- trace checks all pass

Soak:

```bash
RATE=10000 DURATION=3m WORKERS=384 make hetzner-core ARGS=soak
```

Reports are written to `/opt/reef/reports/soak`.

## Reboot Drill

Before reboot:

```bash
PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
```

Reboot:

```bash
ssh ops@167.233.82.255 'sudo systemctl reboot'
```

After SSH returns:

```bash
ssh ops@167.233.82.255 'cd /opt/reef && docker compose ps'
```

Then:

1. check OpenBao seal state;
2. unseal with three keys if sealed;
3. run `PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check`;
4. run `make hetzner-core ARGS=verify`;
5. run `make hetzner-core ARGS=hosted-smoke`.

## Failure Triage

DNS mismatch:

- check `tofu output -raw api_domain`
- check Cloudflare `A` record in OpenTofu state
- run `make hetzner-core-tofu ARGS="plan -no-color"`

HTTPS fails:

- check Caddy container status and logs
- verify Hetzner firewall and UFW have 80/443 open
- run `make hetzner-core ARGS=public-up`

`/admin` serves the landing page:

- run `make hetzner-core ARGS=public-up` so Caddy is recreated and remounts the
  current Caddyfile
- confirm `try_files {path} {path}/index.html {path}.html /index.html` is
  visible from inside the Caddy container
- recheck `curl -fsS https://reef-arena-admin.shrimpworks.dev/admin`

Runtime health fails:

- check `docker compose ps`
- check `docker compose logs --tail=200 platform-runtime`
- verify Postgres health and migrations with `make hetzner-core ARGS=verify`

OpenBao sealed:

- unseal with three offline keys
- rerun `ops-check`

Hosted smoke seed fails with `legacy mutation route disabled`:

- use `make hetzner-core ARGS=hosted-smoke`, not direct host script execution
- verify cleanup after the run:

  ```bash
  ssh ops@167.233.82.255 \
    "cd /opt/reef && docker compose exec -T platform-runtime env | grep '^PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED='"
  ```

R2 upload skipped:

- confirm R2 bucket exists
- confirm `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are set locally before
  `backup-bootstrap`
- check `/opt/reef/secrets/backup.env` on host without printing secrets
- check `journalctl -u reef-backup.service -n 200 --no-pager`

Cloudflare R2 bucket apply returns `403`:

- if the error includes code `10042`, enable R2 in the Cloudflare dashboard for
  account `21f7217c1f4e4a4da99f20b12c85a463`
- otherwise, the token can manage DNS but not R2
- add account-level `Workers R2 Storage Write` to the OpenTofu token, then
  rerun the Tofu apply

## Promotion Checklist

Treat the backbone as healthy only when all of these are true:

- OpenTofu plan has no unexpected drift
- `PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check` passes
- `make hetzner-core ARGS=verify` passes
- `make hetzner-core ARGS=backup-restore-check` passes against a recent archive
- `make hetzner-core ARGS=hosted-smoke` passes
- public HTTPS returns `200`
- unauthenticated public admin API routes return `401`
- OAuth start returns `302` to GitHub
- legacy mutation route is disabled after smoke/admin bootstrap windows
- OpenBao root token and unseal material are not on the host
- current state and secrets checklist are updated after any material change
