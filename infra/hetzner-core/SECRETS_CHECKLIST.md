# Hetzner Core Secrets Checklist

This checklist records the external credentials, generated service secrets, and
operator-held recovery material needed for the permanent Reef backbone.

Do not commit real secret values. Keep external provider tokens in local `.env`
files, GitHub Actions secrets, or an offline/password-manager vault. Keep
host-generated runtime secrets under `/opt/reef/secrets` on the Hetzner host.

## Operator-Owned External Secrets

| Secret | Current need | Where it belongs | Notes |
| --- | --- | --- | --- |
| `HETZNER_TOKEN` | Required for OpenTofu server/firewall/network changes | Repository root `.env` on operator machine | The wrapper maps it to `HCLOUD_TOKEN`. |
| `CLOUDFLARE_API_TOKEN` | Required for OpenTofu Cloudflare DNS and optional R2 bucket changes | Repository root `.env` or `infra/hetzner-core/tofu/.env` | `CLOUDFLARE_TOKEN` is accepted as an alias. See the permission matrix below; do not use this token for backup object upload. |
| `R2_ENDPOINT` | Optional but recommended for off-host backups | Tofu output or operator environment before `backup-bootstrap` | Example: `https://<account-id>.r2.cloudflarestorage.com`. |
| `R2_BUCKET` | Optional but recommended for off-host backups | Tofu output or operator environment before `backup-bootstrap` | Current intended bucket name is `reef-backups`. |
| `AWS_ACCESS_KEY_ID` | Optional R2 upload credential | Operator environment before `backup-bootstrap` | R2 S3-compatible access key from a separate R2 token scoped to `reef-backups`. |
| `AWS_SECRET_ACCESS_KEY` | Optional R2 upload credential | Operator environment before `backup-bootstrap` | R2 S3-compatible secret key from the separate R2 token. |
| `AWS_DEFAULT_REGION` | Optional R2 setting | Operator environment before `backup-bootstrap` | Use `auto` for Cloudflare R2 unless there is a reason to override. |
| `DOCKERHUB_USERNAME` | Needed if publishing deployment images through GitHub Actions | GitHub Actions secret | See `DOCKERHUB.md`. |
| `DOCKERHUB_TOKEN` | Needed if publishing deployment images through GitHub Actions | GitHub Actions secret | Docker Hub token with Read & Write for image publishing. |
| `GITHUB_OAUTH_CLIENT_ID` | Needed when hosted admin browser login is enabled | Host runtime env, generated from local env by `generate-local-secrets.sh` | Only required when `PLATFORM_ADMIN_AUTH_ENABLED=true`. |
| `GITHUB_OAUTH_CLIENT_SECRET` | Needed when hosted admin browser login is enabled | Host runtime env, generated from local env by `generate-local-secrets.sh` | Only required when `PLATFORM_ADMIN_AUTH_ENABLED=true`. |
| `GITHUB_OAUTH_REDIRECT_URI` | Needed when hosted admin browser login is enabled | Host runtime env, generated from local env by `generate-local-secrets.sh` | For public domain: `https://reef-arena-admin.shrimpworks.dev/admin/auth/github/callback`. |
| `TIINGO_API_TOKEN` | Optional market-data seed provider | Local/operator env for stock-data workflows | Not required for backbone DNS/runtime bring-up. |

## Non-Secret External Configuration

| Value | Current value | Where it belongs | Notes |
| --- | --- | --- | --- |
| Hetzner server IPv4 | `167.233.82.255` | OpenTofu state/output | Output `core_ipv4`. |
| Hetzner private IPv4 | `10.70.1.10` | OpenTofu state/output | Output `core_private_ipv4`. |
| Public API/admin domain | `reef-arena-admin.shrimpworks.dev` | `infra/hetzner-core/tofu/terraform.tfvars` and public runtime config | DNS and public Caddy ingress are live. |
| Cloudflare zone ID | `96ffdfe35a4fee86fa0b4067eb0408d5` | `infra/hetzner-core/tofu/terraform.tfvars` | Non-secret zone id for `shrimpworks.dev`. |
| Cloudflare account ID | `21f7217c1f4e4a4da99f20b12c85a463` | `infra/hetzner-core/tofu/terraform.tfvars` | Non-secret account id used for R2 bucket management. |
| R2 backup bucket | `reef-backups` | OpenTofu state | Bucket creation is complete and OpenTofu plan is clean. |
| Admin CIDR | `71.173.194.78/32` | `infra/hetzner-core/tofu/terraform.tfvars` | Controls SSH/ICMP access through the Hetzner firewall. |

## Cloudflare Token Permission Matrix

Use two separate Cloudflare credentials:

| Credential | Purpose | Cloudflare scope | Required permissions |
| --- | --- | --- | --- |
| `CLOUDFLARE_API_TOKEN` | OpenTofu DNS only | Zone `shrimpworks.dev` | `Zone:Zone:Read` and `Zone:DNS:Write`. If the dashboard/API token UI shows legacy names, use the equivalent `Zone Read` and `DNS Edit/Write`. |
| `CLOUDFLARE_API_TOKEN` | OpenTofu DNS plus R2 bucket creation | Zone `shrimpworks.dev` and Cloudflare account `21f7217c1f4e4a4da99f20b12c85a463` | DNS permissions above plus account-level `Workers R2 Storage Write`. This can create/list/delete R2 buckets and edit bucket configuration. |
| R2 S3 access key pair | Host backup uploads to existing bucket | Bucket `reef-backups` only | `Object Read & Write`, scoped to bucket `reef-backups`. Store as `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`; do not put it in Tofu vars or git. |

R2 must also be enabled once in the Cloudflare dashboard for the account before
OpenTofu can create `cloudflare_r2_bucket.backups`. If apply fails with
Cloudflare error `10042` and message `Please enable R2 through the Cloudflare
Dashboard`, enable R2 for account `21f7217c1f4e4a4da99f20b12c85a463`, then
rerun the Tofu apply.

Avoid using a single broad R2 admin token for scheduled backups. The Tofu token
can be account-scoped because it manages bucket infrastructure; the backup job
only needs object read/write in the one backup bucket.

R2 storage is budgeted below the `10 GB` free-tier target. The backup script
defaults to `R2_MAX_BYTES=8589934592` (`8 GiB`) and `R2_MAX_BACKUPS=7` for
`db/reef-db-*.tar.gz.age` backup objects. Keep these defaults unless there is a
deliberate budget change.

## Host-Generated Secrets

These are created or preserved by `/opt/reef/scripts/generate-local-secrets.sh`
on the Hetzner host.

| File | Generated values | Consumer |
| --- | --- | --- |
| `/opt/reef/secrets/db.env` | Main Postgres superuser password, OpenBao DB password, runtime app DB password | `postgres`, OpenBao, platform runtime |
| `/opt/reef/secrets/postgres-admin.env` | Admin Postgres superuser password, admin app DB password | `postgres-admin`, platform runtime admin/arena modules |
| `/opt/reef/secrets/postgres-analytics.env` | Analytics Postgres superuser password, analytics app DB password | `postgres-analytics`, analytics export module |
| `/opt/reef/secrets/openbao.env` | Bao address and Postgres storage connection URL | OpenBao |
| `/opt/reef/secrets/platform-runtime.env` | Runtime DB settings, auth mode, static external API tokens, admin/arena/analytics DB settings, optional Bao AppRole credentials | Platform runtime |
| `/opt/reef/secrets/admin-auth.env` | Hosted GitHub OAuth config and temporary legacy-route flag | Sourced by `generate-local-secrets.sh` when present |
| `/opt/reef/secrets/matching-engine.env` | Matching engine bind settings | Matching engine |
| `/opt/reef/secrets/simulator.env` | Simulator API bearer token, client id prefix, optional Bao AppRole credentials | Hosted smoke/soak simulator |
| `/opt/reef/secrets/caddy.env` | `ARENA_ADMIN_API_TOKEN`, `ANALYTICS_EXPORT_API_TOKEN` | Caddy public admin gateway and platform runtime route checks |
| `/opt/reef/secrets/backup.env` | `AGE_RECIPIENT` and optional R2 settings | Host backup job |
| `/opt/reef/secrets/platform-runtime.env` | `BAO_BOT_CONFIG_ROLE_ID`, `BAO_BOT_CONFIG_SECRET_ID` | Dedicated OpenBao AppRole used by the Admin API for participant bot config writes |

Do not replace generated simulator/static API tokens with hand-written values.
The generator maps `sim-client-*` identities into `EXTERNAL_API_TOKENS` and
writes the matching `REEF_API_BEARER_TOKEN` for hosted smoke and soak runs.

## Offline Recovery Material

| Material | Current handling | Notes |
| --- | --- | --- |
| OpenBao unseal keys | Offline vault/password manager | Hosted Bao uses Shamir keys; any three keys unseal the server. |
| OpenBao root/admin token | Offline vault/password manager | Use only for deliberate Bao administration, then return it to vault. |
| Local age identity | `~/Documents/reef-backups-age-identity.txt` | Private key for decrypting backup archives copied to local Documents. |
| Encrypted backup archives | `~/Documents/reef-db-*.tar.gz.age` and host `/opt/reef/backups` | New archives are gzip-compressed before age encryption. Legacy `.tar.age` archives may still exist from first bring-up. |

Run `make hetzner-core ARGS=backup-restore-check` from the operator machine to
copy the latest encrypted host archive locally, decrypt it with the local age
identity, and run `pg_restore --list` for each dump. This requires local native
`age` and `pg_restore` tools and does not send the private age identity to the
server.

## GitHub Actions / Integration Secrets

| Secret | Purpose | Source |
| --- | --- | --- |
| `ARENA_ADMIN_API_URL` | Bot-submission workflow target | `https://reef-arena-admin.shrimpworks.dev` once public ingress is enabled. |
| `ARENA_ADMIN_API_TOKEN` | Bot-submission CI admin gateway bearer token | Generated in `/opt/reef/secrets/caddy.env`; copy to GitHub Actions secret when CI path is enabled. |
| `BOT_SUBMISSION_OPENBAO_MODE` | Enables real OpenBao provisioning in CI | Set to `real` only for the hosted provisioning workflow. |
| `ACTIONS_ID_TOKEN_REQUEST_URL` | GitHub OIDC URL | Supplied by GitHub Actions when workflow grants `id-token: write`. |
| `ACTIONS_ID_TOKEN_REQUEST_TOKEN` | GitHub OIDC request token | Supplied by GitHub Actions when workflow grants `id-token: write`. |
| `REEF_ADMIN_DEPLOY_URL` | Optional admin UI deploy receiver URL | GitHub Actions variable; defaults to `https://reef-arena-admin.shrimpworks.dev/admin/deploy/arena-admin`. |
| `REEF_ADMIN_DEPLOY_AUDIENCE` | Optional admin UI deploy OIDC audience | GitHub Actions variable; defaults to `reef-backbone-admin-deploy`. Must match `/opt/reef/secrets/deploy-receiver.env`. |

Admin UI auto-deploy does not require a GitHub SSH private key, Hetzner API
token, or long-lived deploy bearer token. It uses GitHub OIDC and the
host-side `deploy-receiver` service.

After
`REEF_GITHUB_REPOSITORY=dills122/reef BAO_TOKEN="..." ./scripts/configure-openbao.sh`,
generate the Admin API bot-config AppRole with:

```bash
BAO_TOKEN="..." ./scripts/print-openbao-approle.sh reef-platform-admin-bot-config
```

Append the printed `BAO_ROLE_ID` and `BAO_SECRET_ID` as
`BAO_BOT_CONFIG_ROLE_ID` and `BAO_BOT_CONFIG_SECRET_ID` in
`/opt/reef/secrets/platform-runtime.env`, then redeploy or restart
`platform-runtime`.

Preferred one-time hosted upgrade command:

```bash
BAO_TOKEN="..." make hetzner-core ARGS=bot-config-upgrade
# or, without exporting the token:
REEF_OPENBAO_INIT_JSON=~/Documents/reef-openbao-init-167.233.82.255.json make hetzner-core ARGS=bot-config-upgrade
```

It performs the OpenBao policy/AppRole update, writes the two
`BAO_BOT_CONFIG_*` values without printing them, restarts `platform-runtime`,
and prints anything still requiring a human check. The helper derives
`REEF_GITHUB_REPOSITORY` from the local `origin` remote unless it is set
explicitly, and can read `root_token` from `REEF_OPENBAO_INIT_JSON` when
`BAO_TOKEN` is not set.

The provisioner requests GitHub OIDC audience `reef-bot-submission-ci` by
default. Keep that aligned with OpenBao role
`auth/jwt/role/reef-bot-submission-ci` and override
`BOT_SUBMISSION_OPENBAO_OIDC_AUDIENCE` only if the role's `bound_audiences`
changes.

The bot-submission workflow authenticates as service actor `bot-submission-ci`
through `ARENA_ADMIN_API_TOKEN`. That actor must have `arena.admin` in runtime
role bindings before registry diff and OpenBao provisioning can pass:

```bash
ADMIN_ACTOR_ID=bot-submission-ci make hetzner-core ARGS=admin-actor-role-grant
```

## Current Gaps

- R2 bucket creation and encrypted object upload have succeeded for
  `reef-backups`; keep the object key scoped to this bucket and IP-gated to the
  Hetzner server.
- Hosted GitHub OAuth credentials are configured on the host, `arena.admin` was
  granted to `user-gh-15662762`, and browser login has been verified.
- Public Caddy ingress is enabled; keep `PUBLIC_INGRESS_EXPECTED=1 make
  hetzner-core ARGS=ops-check` in the verification loop after public route or
  firewall changes.
