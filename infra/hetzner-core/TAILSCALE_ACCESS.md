# Tailscale Operator Access

Reef uses standard OpenSSH over a Tailscale private network for routine
operator access to the Hetzner backbone. Tailscale supplies the private network
path; the existing `ops` account, SSH key, and host authorization remain the
SSH security boundary. Tailscale SSH is intentionally not enabled.

This design keeps public TCP 22 closed during normal operation while preserving
two recovery paths: the Hetzner web console and a temporary public `/32`
firewall rule.

## One-Time Migration

1. Install the Tailscale client on the operator workstation and sign in to the
   intended tailnet. Follow the official client instructions at
   <https://tailscale.com/download>.

2. Bootstrap or restore temporary public SSH access. In
   `tofu/terraform.tfvars`, set the current workstation public address and keep
   public SSH enabled:

   ```hcl
   admin_cidrs         = ["<current-public-ip>/32"]
   enable_public_ssh   = true
   ```

   Apply a reviewed OpenTofu plan. The firewall change should affect only the
   public SSH/ICMP source during migration.

   On an existing host, UFW may still contain the previous residential `/32`
   because cloud-init does not rerun when `terraform.tfvars` changes. If SSH
   still times out, use the Hetzner web console and add the current source
   before continuing:

   ```bash
   ufw allow from <current-public-ip> to any port 22 proto tcp
   ```

   The Hetzner firewall remains the outer restriction, so this does not expose
   SSH beyond the same reviewed `/32`.

3. Install and authenticate Tailscale on the host:

   ```bash
   make hetzner-core ARGS=tailscale-bootstrap
   ```

   Open the login URL printed by `tailscale up` and authorize the server in the
   same tailnet as the operator workstation. The command does not accept, write,
   or persist a Tailscale auth key in the repository or OpenTofu state.

4. Verify the private route in a second terminal before changing either
   firewall:

   ```bash
   export REEF_HETZNER_HOST="<tailscale-magicdns-name-or-ip>"
   ssh "ops@$REEF_HETZNER_HOST"
   make hetzner-core ARGS=tailscale-status
   make hetzner-core ARGS=status
   ```

   Prefer the fully qualified MagicDNS name because macOS may not resolve the
   short hostname consistently. If MagicDNS is disabled, use the `100.x.y.z`
   address printed by `tailscale-status` as `REEF_HETZNER_HOST`.

5. Close public SSH in `tofu/terraform.tfvars` and apply a reviewed plan:

   ```hcl
   operator_ssh_host = "<tailscale-magicdns-name-or-ip>"
   enable_public_ssh = false
   ```

   The plan should remove the Hetzner inbound TCP 22 and admin ICMP rules. It
   must not replace the server. Then verify:

   ```bash
   PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check
   ```

   Public TCP 22 should be closed while SSH over Tailscale still succeeds.

## Normal Operation

Set the private host once in the ignored repository `.env` or in the operator
shell:

```text
REEF_HETZNER_HOST=<tailscale-magicdns-name-or-ip>
```

All existing `make hetzner-core ARGS=...` commands then use the private route.
No application ports, OpenBao ports, or database ports need to be exposed to
the tailnet; operators continue to reach loopback services with SSH tunnels.

The host setup permits TCP 22 only on the `tailscale0` interface in UFW. The
Hetzner firewall has no public SSH rule when `enable_public_ssh=false`.
Tailscale normally needs only outbound connectivity; do not add a public UDP
rule solely for Tailscale unless diagnostics establish that direct peer
connectivity is worth the additional exposure.

Before another person is added to the tailnet, configure a Tailscale access
policy that restricts the Reef host and TCP 22 to an explicit operator group.
That policy is owned by the tailnet, not this repository, and can evolve
without changing the server or Reef deployment scripts.

## Break-Glass Recovery

If the private route fails:

1. Use the Hetzner web console to inspect `tailscaled`, networking, SSH, and
   UFW. This path does not depend on Tailscale or port 22. If the host was
   created with an SSH key, Hetzner can generate a console-only root password
   under **Rescue > Reset Root Password**; resetting the password does not
   require enabling the rescue system.
2. If console repair is inconvenient, update `admin_cidrs` to the current
   workstation `/32`, set `enable_public_ssh=true`, review the plan, and apply.
3. Repair and verify Tailscale, set `enable_public_ssh=false`, and apply again.

Never use `0.0.0.0/0` or `::/0` for public SSH, and never commit
`terraform.tfvars`, Tailscale auth keys, or tailnet credentials.
