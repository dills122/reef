# Hetzner Core Current State

Snapshot date: 2026-07-21

This file records the live backbone state after the first permanent Hetzner
bring-up, hardening pass, backup bootstrap, smoke gate, and Cloudflare DNS
apply. It intentionally avoids secret values.

## Infrastructure

| Item | State |
| --- | --- |
| Branch used for latest access hardening | `codex/docs-direction-checkpoint` |
| Hetzner server | `reef-prod-core-1` |
| Public IPv4 | `167.233.82.255` |
| Private IPv4 | `10.70.1.10` |
| Tailscale IPv4 | `100.82.251.32` |
| Tailscale MagicDNS | `reef-prod-core-1.taild20cb3.ts.net` |
| SSH user | `ops` |
| Routine SSH command | `ssh ops@reef-prod-core-1.taild20cb3.ts.net` |
| Runtime tunnel | `ssh -L 8080:127.0.0.1:8080 ops@reef-prod-core-1.taild20cb3.ts.net` |
| OpenBao tunnel | `ssh -L 8200:127.0.0.1:8200 ops@reef-prod-core-1.taild20cb3.ts.net` |
| Public SSH | Disabled in the Hetzner firewall |
| Break-glass CIDR | Current operator `/32`, activated only with `enable_public_ssh=true` |

OpenTofu currently owns the Hetzner server, Hetzner network, Hetzner firewall,
SSH key reference, and Cloudflare DNS record. A normal `make hetzner-core-tofu
ARGS="plan -no-color"` reported no changes after the DNS and public-firewall
apply.

`REEF_HETZNER_HOST=reef-prod-core-1.taild20cb3.ts.net
PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check` passed on
2026-07-21. It verified DNS, public TCP exposure, private SSH, Compose service
state, platform health, OpenBao seal state, backup timer state, and encrypted
backup archive presence. Public TCP 22 was closed; public 80/443 remained open;
8080/8200 remained closed.

## DNS And Public Exposure

| Item | State |
| --- | --- |
| Domain | `reef-arena-admin.shrimpworks.dev` |
| DNS record | Cloudflare `A` record -> `167.233.82.255` |
| Cloudflare proxied | `false` |
| TTL | `300` |
| OpenTofu resource | `cloudflare_dns_record.api[0]` |
| Public HTTP/HTTPS firewall | Enabled |
| Caddy public profile | Running |

DNS is live and resolves publicly. Public ingress was enabled on 2026-07-10
through OpenTofu Hetzner firewall rules plus `make hetzner-core ARGS=public-up`.

Public ingress verification on 2026-07-10:

- `PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check` passed.
- Public TCP `80` and `443` are open.
- Public TCP `8080` and `8200` remain closed.
- `http://reef-arena-admin.shrimpworks.dev/` redirects to HTTPS with `308`.
- `https://reef-arena-admin.shrimpworks.dev/` returns the static admin app with
  security headers.
- `https://reef-arena-admin.shrimpworks.dev/admin` returns the admin route
  shell, not the landing page shell.
- unauthenticated `GET /admin/v1/arena/bots` returns `401`.
- unauthenticated `POST /admin/v1/analytics/run-exports` returns `401`.
- Caddy obtained a Let's Encrypt certificate for
  `reef-arena-admin.shrimpworks.dev`.

`make hetzner-core ARGS=public-up` recreates the Caddy container after syncing
the server bundle. This is intentional: the file bind mount for `Caddyfile` can
remain pinned to the old inode after `rsync`, so a plain Caddy reload may not
see a changed fallback rule.

## Running Backbone Shape

The host Compose stack is deployment-shaped and owned by
`infra/hetzner-core/server`, not the repository root local Compose files.

Always-on services:

- main Postgres for runtime/OpenBao storage
- dedicated admin Postgres
- dedicated analytics Postgres
- OpenBao on host loopback
- matching engine
- platform runtime on host loopback

Optional/profiled services:

- Caddy public proxy and arena admin static site under `public` profile
- simulator under `manual` profile
- JetStream-backed stream-ack overlay through
  `docker-compose.stream-ack.yml`

## Hardening Applied

Host/container hardening applied during bring-up:

- Routine SSH uses standard OpenSSH over Tailscale; Tailscale SSH is not enabled.
- Hetzner has no public SSH/ICMP rule during normal operation.
- Host UFW permits SSH on `tailscale0` only; stale residential `/32` rules were
  removed after private access was verified.
- Platform runtime and OpenBao publish only on host loopback.
- Public HTTP/HTTPS are open only through Caddy; raw runtime/OpenBao host ports
  remain closed publicly.
- Docker daemon uses `live-restore=true`.
- Docker daemon uses `local` log driver with rotation.
- Compose services use `init: true`, `pids_limit`, and
  `no-new-privileges`.
- App/proxy containers use read-only root filesystems and `/tmp` tmpfs.
- Simulator image runs as non-root UID/GID `10001`.
- OpenBao audit logging is configured to file output under
  `/opt/reef/openbao/logs/audit.log`.

## OpenBao

| Item | State |
| --- | --- |
| Initialization | Completed |
| Unseal material | Stored offline/vault, not on host |
| Root/admin token | Stored offline/vault, not on host |
| Runtime AppRole | Configured and preserved in host runtime env |
| Simulator AppRole | Configured only when needed and preserved by generator |
| Audit logging | Enabled through OpenBao config |

After host restart or container recreation, check `sealed` state and unseal with
any three offline unseal keys if needed.

The reboot/unseal drill passed on 2026-07-10:

- host rebooted and SSH returned;
- Docker services auto-started;
- Postgres containers returned healthy;
- OpenBao returned sealed, as expected;
- OpenBao was unsealed with three operator-held unseal keys;
- post-unseal `make hetzner-core ARGS=ops-check` passed;
- post-unseal `make hetzner-core ARGS=verify` passed.

## Backups

| Item | State |
| --- | --- |
| Backup script | `/opt/reef/scripts/backup-dbs.sh` |
| Timer | `reef-backup.timer` installed |
| Compression/encryption | PostgreSQL custom-format dumps, gzip tar archive, age recipient from local identity |
| Local private identity | `~/Documents/reef-backups-age-identity.txt` |
| Local encrypted archive copies | `~/Documents/reef-db-*.tar.gz.age`; legacy `.tar.age` copies exist from first bring-up |
| Off-host R2 upload | Enabled and verified |
| R2 bucket OpenTofu config | Applied; bucket `reef-backups` exists |
| R2 storage budget guard | Remote `db/reef-db-*.tar.gz.age` backup objects capped at `8 GiB` and `7` objects by `backup-dbs.sh` defaults |

The R2 bucket was created through OpenTofu on 2026-07-10. The free-tier target
is to remain under `10 GB`; the host backup script uses a lower default hard
cap of `8 GiB` (`R2_MAX_BYTES=8589934592`) plus `R2_MAX_BACKUPS=7` for margin.
If the script cannot list/delete remote backups to prove it can stay under the
cap, it refuses R2 upload rather than allowing unbounded growth.

New backup archives are compressed before encryption and R2 upload:
`reef-db-<timestamp>.tar.gz.age`.

R2 upload was verified on 2026-07-10:

- uploaded object: `db/reef-db-20260710T152530Z.tar.gz.age`
- uploaded size: `2532800` bytes, about `2.4 MiB`
- remote budget status during upload: `existing=0 bytes`, `incoming=2532800`
  bytes, `backups_after_upload=1/7`

Known local encrypted archive checksums from bring-up:

- `reef-db-20260710T033918Z.tar.age`: `e0dc9fe033a2f586b2164149e045454c61fddeac7823f8c86fa6caf372e4ec0b`
- `reef-db-20260710T035933Z.tar.age`: `650250be54cacc483fa22bc658aa55808131cc6d9281b98f05ebc6ee2f811f25`
- `reef-db-20260710T134152Z.tar.age`: `ca203e679b22afd43da4071966d5edd141a03d9116c15a2a4dfa1cefdf1ba338`
- `reef-db-20260710T151710Z.tar.gz.age`: `19ea0d8bb3eb2edb8cf8088e317244388fed5906c803372d4f2a0e21e9cdfb08`
- `reef-db-20260710T152530Z.tar.gz.age`: `a1ad58bb0b1b7ca3ff83b7a4d6821c21dc44c9f8f7eb12c4836f5b85c578d9cb`

`make hetzner-core ARGS=backup-restore-check` passed on 2026-07-10 against
`/opt/reef/backups/reef-db-20260710T035933Z.tar.age`. The check decrypted the
archive locally with the operator-held age identity and ran `pg_restore --list`
successfully for:

- `openbao.dump`
- `reef.dump`
- `admin.dump`
- `analytics.dump`

The same restore-list check also passed on 2026-07-10 against the fresh
`/opt/reef/backups/reef-db-20260710T134152Z.tar.age` archive created by
`make hetzner-core ARGS=backup-bootstrap`.

After switching new backups to gzip-compressed archives before age encryption,
the restore-list check passed again on 2026-07-10 against
`/opt/reef/backups/reef-db-20260710T151710Z.tar.gz.age`.

After enabling R2 object credentials, the restore-list check passed again on
2026-07-10 against the R2-uploaded archive
`/opt/reef/backups/reef-db-20260710T152530Z.tar.gz.age`.

## Smoke Evidence

Hosted smoke was run through `make hetzner-core ARGS=hosted-smoke` after the
hardening and backup automation work.

Observed result:

- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

The hosted smoke gate now fails on system failures, valid-intent success below
`100`, or trace failures.

The gate also passed on 2026-07-10 with run id
`hosted-smoke-50-30s-w16-20260710T133210Z`:

- throughput about `49.9 rps`
- accepted business ops about `29.8 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After the reboot/unseal drill, the gate passed again with run id
`hosted-smoke-50-30s-w16-20260710T135346Z`:

- throughput about `49.6 rps`
- accepted business ops about `27.8 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After public Caddy ingress was enabled, the gate passed again with run id
`hosted-smoke-50-30s-w16-20260710T140125Z`:

- throughput about `49.9 rps`
- accepted business ops about `27.3 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After the documentation cleanup and final public-ingress verification, the gate
passed again with run id `hosted-smoke-50-30s-w16-20260710T140605Z`:

- throughput about `49.9 rps`
- accepted business ops about `27.2 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After GitHub OAuth enablement and the temporary-seed-route hosted smoke wrapper,
the gate passed again with run id `hosted-smoke-50-30s-w16-20260710T143229Z`:

- throughput about `49.8 rps`
- accepted business ops about `26.7 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After the admin route/session fixes, the gate passed again with run id
`hosted-smoke-50-30s-w16-20260710T145320Z`:

- throughput about `49.8 rps`
- accepted business ops about `26.5 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

After R2 backup upload enablement and final backbone verification, the gate
passed again with run id `hosted-smoke-50-30s-w16-20260710T154139Z`:

- throughput about `49.8 rps`
- accepted business ops about `26.9 rps`
- `systemFailureCount=0`
- valid-intent success rate `100%`
- trace checks `20/20`

## Hosted Admin Auth

GitHub OAuth admin auth is enabled on the host through
`make hetzner-core ARGS=admin-auth-up`.

Verification on 2026-07-10:

- `GET /admin` returns the admin shell with `checking session...`.
- unauthenticated `GET /admin/auth/session` returns `401`.
- `GET /admin/auth/github/start?redirectPath=/admin` returns `302` to GitHub.
- authenticated session responses include the GitHub login, display name, trust
  state, and role list expected by the admin UI.
- Human browser GitHub login and hosted admin session were verified working.
- `make hetzner-core ARGS=admin-auth-smoke` passes and verifies the admin
  shell, unauthenticated session `401`, GitHub OAuth `302`, Caddy clean-url
  fallback, and legacy mutation-route cleanup.
- `arena.admin` was granted to `user-gh-15662762` through
  `make hetzner-core ARGS=admin-role-grant`.
- `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=false` is set in the running
  `platform-runtime` container after the grant.
- Host-local `/auth/roles` mutation with the internal marker returns `403`
  after cleanup.

## Next Operator Steps

1. Copy generated `ARENA_ADMIN_API_TOKEN` into GitHub Actions only when the
   bot-submission provisioning workflow is ready to run against the hosted API.
2. Run `make hetzner-core ARGS=verify`, `make hetzner-core
   ARGS=admin-auth-smoke`, and `make hetzner-core ARGS=hosted-smoke` after
   each public-ingress or auth change.
