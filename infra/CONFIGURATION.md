# Infrastructure Configuration Policy

Infrastructure values follow this resolution order:

1. explicit configuration or environment override;
2. provider/OpenTofu output or local discovery;
3. deterministic Reef convention;
4. a clear failure when no safe value exists.

Provider-created names, network ranges, addresses, domains, repository
identities, account IDs, and bucket names must not exist only as literals in
executable code. A conventional default is acceptable when it is documented,
safe for a new installation, and has an override. Values allocated by a
provider should be consumed through outputs rather than copied into scripts.

Exact values are appropriate in `CURRENT_STATE.md` snapshots because those
files record facts about one live deployment. General README and runbook
commands should use variables, outputs, or placeholders.

## Current Configuration Surfaces

| Area | Creation-time identity/configuration | Runtime discovery/override |
| --- | --- | --- |
| Hetzner backbone | `project`, `environment`, `resource_name_override`, `server_name`, `location`, `network_cidr`, `network_zone`, `subnet_cidr`, `server_private_ip` | OpenTofu outputs; `REEF_HETZNER_HOST`; `operator_ssh_host` |
| Tailscale access | Remote OS hostname by default; optional `REEF_TAILSCALE_HOSTNAME` during enrollment | `operator_ssh_host` output or `REEF_HETZNER_HOST` |
| Public DNS/backups | `api_domain`, Cloudflare zone/account IDs, `r2_backup_bucket` | OpenTofu `api_domain`, endpoint, and bucket outputs; explicit environment overrides |
| GitHub deploy trust | Repository derived from `REEF_GITHUB_REPOSITORY` or local `origin` | Persisted `DEPLOY_RECEIVER_EXPECTED_REPOSITORY`; receiver fails closed when absent |
| DigitalOcean worker | Tofu `droplet_name`, `region`, `size`, `image`, `ssh_user`, tags | `REEF_DO_*` overrides; simulation runner defaults worker name from run ID |
| Local Kubernetes | `KUBE_LOCAL_CLUSTER`, `KUBE_NAMESPACE`, `KUBE_CONTEXT` | Manifests are rendered into the selected namespace; runtime/engine image overrides |
| CI publication/deploy | Repository owner/name conventions | `DOCKERHUB_NAMESPACE`, `DOCS_SITE_URL`, `DOCS_SITE_BASE`, `REEF_ADMIN_PUBLIC_URL`, deploy URL/audience variables |

## Intentional Constants

Some names are protocol or within-stack contracts rather than deployment
identity: Compose service names, Kubernetes Service names, loopback ports,
database/schema names, stream subjects, OpenBao policy names, and API routes.
Changing these requires a contract-aware migration, not another generic
configuration knob.

Security automation may also carry an intentional canonical allowlist. For
example, Dependabot auto-merge is repository-bound so forks do not silently
inherit write automation. Such bindings should be explicit and reviewed as
security policy rather than generalized as deployment naming.

Before adding a literal to infrastructure code, classify it as one of:

- provider-created identity: configure or derive it;
- provider-allocated value: expose and consume an output;
- secret/security binding: require explicit configuration and fail closed;
- canonical Reef convention: allow a documented override when multiple
  installations or forks are realistic;
- protocol contract: keep stable and document the owning contract.
