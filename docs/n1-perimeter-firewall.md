# N1 — pfSense Perimeter Firewall

**Status:** ✅ deployed and accepted (2026-05-14)
**Owner:** CurlyRed (Serhii)
**Pillar:** Network / N1 — perimeter firewall + WireGuard remote access + Suricata IDS
**Related issue:** [#8](https://github.com/Guts1313/CYBERGROUP/issues/8)

This doc is for **team members onboarding to the project**. It explains what was built, how it's configured, and how to work with it day-to-day. It bakes in the NetLab-specific corrections we found during deployment — you don't have to repeat our mistakes.

If you want the full session log with debug history and discovery of corrections, see [`n1-pfsense-session-handoff.md`](./n1-pfsense-session-handoff.md). For the actual config dump, see [`pfsense-baseline.xml`](../pfsense-baseline.xml).

---

## 1. What this is

The pfSense perimeter firewall is the entry point to our IAM lab environment. Everything flows through it:

```
   Fontys AnyConnect VPN
            │
            ▼
  ┌────────────────────┐
  │  pfSense pf-iam-n1 │
  │  WAN: 192.168.189.16 (NetLab "internet")
  │  LAN: 10.0.20.1/24  (IdP VLAN, where Keycloak lives)
  │  WG:  10.99.100.1/24 (remote-admin tunnel)
  └─────────┬──────────┘
            │  (PVlanA — VLAN 20)
            ▼
       Keycloak + KC-Postgres
        (Angel's pillar, N2)
```

**Phase 1 (now):** pfSense's LAN side IS the IdP VLAN. Keycloak attaches directly to PVlanA.

**Phase 2 (later, when N2 lands):** Real DMZ goes in front (NGINX edge at 10.0.10.10), pfSense LAN becomes the IdP VLAN behind an internal firewall, traffic flows internet → pfSense WAN → DMZ → internal FW → IdP. The disabled NAT rule on WAN (TCP 443 → 10.0.10.10:443) is already in place for that future state — flip it on when D1 is built.

---

## 2. Quick facts

| What | Value |
|---|---|
| **VM name** | `pf-iam-n1` |
| **vCenter location** | Cluster `Netlab-Cluster-B`, Resource pool `PRO07`, Datastore `DataCluster-Students` |
| **pfSense version** | 2.8.1-RELEASE amd64 |
| **Template used** | `Templ_pfSense_2.8.1_firewall` |
| **WAN interface** | `vmx0` on port group `0189_Internet-DHCP-192.168.189.0_24`, DHCP, currently `192.168.189.16/24` |
| **LAN interface** | `vmx1` on port group `0861_PRO07_PVlanA`, static `10.0.20.1/24`, DHCP pool `.100–.150` |
| **WG tunnel interface** | `WG_TUN`, static `10.99.100.1/24`, listen UDP `51820` |
| **Hostname / FQDN** | `pf-iam-n1.iam.lab.local` |
| **Admin GUI** | `https://10.0.20.1` (LAN-side) or via WG tunnel from outside |
| **Admin password** | Held in CurlyRed's password manager. NOT in repo. Ask in Discord if you need it. |
| **WG server public key** | `bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=` |
| **Suricata mode** | Alert-only (IDS, not IPS) on WAN, ETOpen rules |

### PRO07 PVlan reservation (team convention)

| PVlan | Port group | VLAN | Purpose | Status |
|-------|------------|------|---------|--------|
| A | `0861_PRO07_PVlanA` | 20 | IdP (Keycloak + KC-Postgres) | claimed (N1) |
| B | `0862_PRO07_PVlanB` | 10 | DMZ (NGINX edge) | reserved for N2 |
| C | `0863_PRO07_PVlanC` | 30 | Services (app backend, app-postgres, Loki, Grafana, Prometheus) | reserved |
| D | `0864_PRO07_PVlanD` | 99 | Management | reserved |

VLAN 40/50 (Users) are unmapped — decide in N2. Either 802.1Q-tagged on an existing PVlan or request more port groups from NetLab.

### IP plan (locked, all /24)

| VLAN | Subnet | Gateway | Notes |
|------|--------|---------|-------|
| 10 (DMZ) | `10.0.10.0/24` | `.1` | nginx-edge at `.10` (future) |
| 20 (IdP) | `10.0.20.0/24` | `.1` | Keycloak `.10` (HTTP 8080, mgmt 9000), KC-Postgres `.20` |
| 30 (Services) | `10.0.30.0/24` | `.1` | app-backend `.10`, app-postgres `.20`, Loki `.30`, Grafana `.31`, Prometheus `.32` |
| 40 (User-Admin) | `10.0.40.0/24` | `.1` | future |
| 50 (User-General) | `10.0.50.0/24` | `.1` | future |
| 99 (Management) | `10.0.99.0/24` | `.1` | future |
| WG tunnel | `10.99.100.0/24` | server `.1` | peers `/32` from `.2` up |

> Why WG is `10.99.100.0/24` and not `10.0.100.0/24` as originally planned: NetLab's Cisco AnyConnect VPN routes `10.0.100.0/24` over the campus VPN tunnel. Using the same /24 for WG would create routing ambiguity on every team member's laptop. We bumped the second octet to avoid the collision.

---

## 3. How to access the firewall

Three ways, depending on where you are:

### From the Kali jump host (`iam-lan-test01`, inside PVlanA)
Just browse to `https://10.0.20.1` from Firefox/Chromium on Kali. Login with admin creds. This is the day-to-day path while doing in-lab work.

### From your own laptop, with WireGuard
For remote admin (working from home, etc.). One-time setup:

1. Install [WireGuard for Windows / macOS / Linux](https://www.wireguard.com/install/)
2. Generate a keypair (the apps do this automatically; on CLI: `wg genkey | tee private.key | wg pubkey > public.key`)
3. Send your public key to CurlyRed (Discord or PR) — they'll add you as a peer in pfSense
4. Get the assigned peer IP back (`10.99.100.X/32`)
5. Configure your WG client with:
   ```
   [Interface]
   PrivateKey = <your generated private key>
   Address = 10.99.100.X/32
   DNS = 10.0.20.1
   MTU = 1420

   [Peer]
   PublicKey = bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=
   AllowedIPs = 10.0.0.0/16, 10.99.100.0/24
   Endpoint = 192.168.189.16:51820
   PersistentKeepalive = 25
   ```
6. **You must be on Fontys AnyConnect VPN** for this to work — the endpoint IP `192.168.189.16` is NetLab-internal. Without AnyConnect, your laptop has no route there.
7. Activate the tunnel. Verify with `ping 10.0.20.1` and `https://10.0.20.1` in your browser.

> Why both VPN+WG? AnyConnect gets you into NetLab's network. WG gets you specifically into our pod's IdP VLAN, with proper firewall-controlled access. AnyConnect alone won't reach `10.0.20.1` — that's inside our pod, isolated from the campus VPN's routes.

### From the vCenter VM console
Last resort, when both other methods fail (e.g., you broke your own firewall rules and locked yourself out). vCenter → VMs → `pf-iam-n1` → Launch Web Console. Login at the console gets you the option menu (1–16). Option 8 is shell.

---

## 4. What was built (deployment summary)

A step-by-step recap. NOT a click-through walkthrough — for that, see the session handoff doc. This is a "here's the architecture and config logic" view.

### 4.1 VM provisioning
Deployed from `Templ_pfSense_2.8.1_firewall` (a pre-installed pfSense image NetLab provides). 2 vCPU, 2 GB RAM, 16 GB thin disk. Two NICs: WAN-side on `0189_Internet-DHCP` (simulated internet upstream), LAN-side on `0861_PRO07_PVlanA` (our IdP VLAN segment).

Template uses VMXNET3 (paravirtualized) NICs → pfSense sees them as `vmx0` (WAN) and `vmx1` (LAN). Not Intel E1000 (`em0`/`em1`).

### 4.2 Initial config via Setup Wizard
Ran from a Kali jump host on PVlanA (`iam-lan-test01`) because pfSense GUI is bound to LAN only — you can't reach it from your laptop directly.

Set hostname (`pf-iam-n1`), domain (`iam.lab.local`), seclab DNS (`192.168.200.14`, `.13`), Europe/Amsterdam timezone, changed admin password from default `pfsense`. WAN stayed on DHCP.

> **NetLab gotcha #1 — Block RFC1918 / Block bogons must be OFF on WAN.**
> The pfSense wizard offers these as security defaults. They make sense in production (block private-IP traffic claiming to come from the internet — likely spoofing). But seclab's "internet" *is* RFC1918 — our WAN sits at `192.168.189.16`, a private IP. Enabling these blocks every legitimate inbound packet.
> Even after unchecking in the wizard, **verify at Interfaces → WAN → Reserved Networks** that both checkboxes are off. The wizard doesn't always honor its own setting. If you see auto-generated "Block private networks" or "Block bogon networks" rules on the Firewall → Rules → WAN tab, the toggles are still on.

### 4.3 LAN re-IP from template default to 10.0.20.0/24
Template LAN ships at `172.16.2.1/24`. We re-IPed to `10.0.20.1/24` per the team's IP plan. Used pfSense console option `2) Set interface(s) IP address` — atomic LAN IP change + DHCP scope update in one step (cleaner than doing each separately in the GUI).

### 4.4 Baseline firewall ruleset

**WAN inbound** (top to bottom, first match wins):
1. **Pass UDP 51820** → `This Firewall (self)` — WireGuard listen port
2. **NAT port-forward TCP 443 → 10.0.10.10:443** — DISABLED placeholder for N2's DMZ NGINX. When DMZ is built, enable this rule.
3. (Implicit default-deny with logging)

> **NetLab gotcha #2 — no RFC1918 or bogon block rules.** The plan originally specified these, but they're inappropriate in seclab (see 4.2). We documented this deviation explicitly because production deploys would include them.

**LAN inbound:**
1. Anti-Lockout Rule (system, can't be removed) — prevents you from locking yourself out of GUI from LAN
2. **Pass LAN subnets → LAN address TCP 443** — admin GUI access
3. **Pass LAN subnets → LAN address TCP 22** — SSH (note: SSH service isn't enabled yet; rule is a placeholder)
4. **Pass LAN subnets → LAN address UDP 53** — DNS resolver
5. **Pass LAN subnets → LAN address UDP 123** — NTP
6. **Pass LAN subnets → !LAN subnets** (any protocol, LOGGED) — egress to anywhere not LAN
7. (Implicit default-deny — note: template's "Default allow LAN to any" rules were explicitly removed)

**WG_TUN inbound** (remote admin via WireGuard):
1. **Pass WG_TUN subnets → LAN address TCP 443** — admin GUI access from VPN
2. **Pass WG_TUN subnets → LAN address TCP 22** — SSH access (when enabled)
3. **Pass WG_TUN subnets → 10.0.10.10 TCP 443** — DISABLED placeholder for N2's DMZ
4. (Implicit default-deny)

Default-deny logging is enabled (Status → System Logs → Settings → "Log packets matched from the default block rules").

### 4.5 WireGuard
Installed `pfSense-pkg-WireGuard` (official Netgate package — not pre-installed in the template). Created tunnel `tun_wg0` listening on UDP 51820 with `10.99.100.1/24` as the server's tunnel IP. Assigned the tunnel as a proper pfSense interface (`WG_TUN`) so we get a dedicated firewall rules tab (versus the unassigned-tunnel mode which uses the WireGuard Interface Group — less granular).

First peer registered: `wg-peer-curlyred` at `10.99.100.2/32`. Other team members add themselves as peers (`.3`, `.4`, …) — each generates their own keypair locally, hands the public key to CurlyRed, gets a peer slot.

### 4.6 Suricata
Installed `pfSense-pkg-suricata`. ETOpen rules downloaded (~50 MB). Enabled on WAN in alert-only mode (NOT inline IPS — that would risk blocking legitimate traffic). All ETOpen categories active.

Verified end-to-end by sending a crafted UDP packet from Kali with payload `uid=0(root)` (the classic Linux command-injection footprint). SID `1:2100498` ("GPL ATTACK_RESPONSE id check returned root") fired. Evidence in the alert log.

---

## 5. Verification (acceptance — 7 boxes)

If you're auditing N1 or want to confirm it's still working:

| # | Check | How to verify |
|---|-------|---------------|
| 1 | pfSense reachable on LAN, NOT on WAN | From Kali: `curl -k https://10.0.20.1` → 200. From laptop without WG: `ping 192.168.189.16` → timeout (default-deny drops ICMP, expected) |
| 2 | WAN inbound rules match baseline | Firewall → Rules → WAN: 1 pass rule (UDP 51820), 1 disabled forward (TCP 443→10.0.10.10), no block-private / block-bogon rules visible |
| 3 | NAT 443 → 10.0.10.10 forward exists, disabled | Firewall → NAT → Port Forward: rule present, greyed out |
| 4 | WG handshake works; LAN reachable only via tunnel | From laptop with WG active: `https://10.0.20.1` loads. From laptop without WG: same URL fails. Diagnostics → Command Prompt → `wg show` → "latest handshake" within seconds when peer is active. |
| 5 | Suricata installed, ETOpen loaded, alert mode, ≥1 signature fired | Services → Suricata → Alerts tab → SID 1:2100498 visible. Or trigger fresh: from any LAN VM, `echo -n "uid=0(root)" \| nc -u -w 1 8.8.8.8 53` → alert appears within 5s |
| 6 | `pfsense-baseline.xml` sanitised in repo | `grep -Ei 'password\|privatekey\|presharedkey\|bcrypt' pfsense-baseline.xml \| grep -v REDACTED` → empty output |
| 7 | `netlab-interfaces.md` filled in | This doc / `netlab-interfaces.md` reflects current state with real values |

---

## 6. Operations

Day-2 tasks you'll likely need to do.

### Add a new WireGuard peer
1. New team member generates a keypair locally (WG app's "Add empty tunnel" does this automatically)
2. They send their **public key** to CurlyRed
3. In pfSense: VPN → WireGuard → Peers → + Add Peer
   - Tunnel: `tun_wg0`
   - Description: `wg-peer-<github-handle>`
   - Dynamic Endpoint: ✓
   - Keep Alive: `25`
   - Public Key: paste their pubkey
   - Allowed IPs: `10.99.100.X/32` (next free slot)
4. Save → Apply Changes
5. Send back to the team member:
   - Their peer IP (`10.99.100.X`)
   - The pfSense WG server public key (`bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=`)
   - Endpoint (`192.168.189.16:51820`)
6. They construct their client config (template in section 3), activate the tunnel, verify

> **Use clipboard copy/paste — NEVER read public keys aloud or transcribe them in chat.** Base64 keys contain `l` and `I` and `1` and `0` and `O` which look identical in many fonts. One wrong character = silent handshake failure with no useful error. We lost time on this. Trust only the clipboard.

### Recover from a stuck WAN DHCP lease
NetLab DHCP soft-locks MACs after rapid release/renew cycles. Symptoms: `wg show` looks fine but tunnel doesn't work because pfSense lost its WAN IP. From shell:
```
ifconfig vmx0
```
Shows `inet6 fe80::...` but no IPv4 → no lease.

**Recovery in order:**
1. From shell: `/etc/rc.linkup interface=wan action=stop && sleep 3 && /etc/rc.linkup interface=wan action=start`
2. If that fails: reboot the VM
3. If that fails: redeploy from template (fresh MAC bypasses the soft-lock)

**Don't:** `dhclient -r vmx0` followed by `dhclient vmx0`. That's what triggers the soft-lock in the first place.

After recovery, the WAN IP may differ from `192.168.189.16`. Update the WG `Endpoint` line on every team member's client config to the new IP.

### View Suricata alerts
**Services → Suricata → Alerts tab.** Filter by SID, source IP, etc. Logs are also under Services → Suricata → Logs View.

To download logs for analysis: Logs View → select interface → Download.

### Export the config (for backup or repo update)
**Diagnostics → Backup & Restore → Download configuration as XML.** Skip RRD, no encryption. **Sanitise before committing** — the raw XML contains passwords, private keys, presharedkeys, and bcrypt hashes:
```
sed -i.bak \
  -e 's|<password>[^<]*</password>|<password>REDACTED</password>|g' \
  -e 's|<privatekey>[^<]*</privatekey>|<privatekey>REDACTED-PRIVATE-KEY</privatekey>|g' \
  -e 's|<presharedkey>[^<]*</presharedkey>|<presharedkey>REDACTED-PSK</presharedkey>|g' \
  -e 's|<bcrypt-hash>[^<]*</bcrypt-hash>|<bcrypt-hash>REDACTED-HASH</bcrypt-hash>|g' \
  pfsense-baseline.xml
grep -Ei 'password|privatekey|presharedkey|bcrypt' pfsense-baseline.xml | grep -v REDACTED  # must be empty
rm pfsense-baseline.xml.bak
```
WAN IP is NOT redacted — `192.168.189.16` is NetLab-internal RFC1918, not a routable public IP.

---

## 7. Known issues / NetLab quirks (not bugs)

- **Port group label is misleading.** `0189_Internet-DHCP-192.168.189.0_24` claims to be a /24 in `192.168.189.0`, but NetLab bridges multiple upstream subnets to this port group. We've seen DHCP hand out IPs from `192.168.230.0/23` briefly before settling. Don't rely on the label.
- **NetLab DHCP soft-locks MACs.** See recovery procedure above. Operational rule: don't `dhclient -r` on WAN.
- **Wizard doesn't honor Block-private/bogon checkboxes reliably.** Always verify at Interfaces → WAN → Reserved Networks after the wizard.
- **AnyConnect VPN routes 10.0.100.0/24** — that's why our WG tunnel uses `10.99.100.0/24` instead of the originally planned `10.0.100.0/24`.
- **Ping to pfSense WAN fails by design.** Default-deny drops ICMP on WAN. If you want to probe reachability, use `nc -u -v 192.168.189.16 51820` (the open WG port).

---

## 8. What's next (handoff to N2 and beyond)

**N2 — Internal firewall + Keycloak migration (Angel / Guts1313):**
- Deploy second pfSense (Inner FW) between PVlanA and DMZ PVlanB
- Migrate Keycloak from running outside the pod to its IdP VLAN location (10.0.20.10)
- Stand up KC-Postgres at 10.0.20.20
- Once DMZ NGINX is deployed at 10.0.10.10, **enable** the disabled NAT forward rule on pf-iam-n1 WAN

**N3 — VLAN segmentation:**
- Decide trunked-vs-access for PRO07 PVlans (the open question — check with NetLab admins)
- Build out VLAN 10/30/40/50/99

**D-series — DMZ deployment:**
- NGINX edge at 10.0.10.10
- TLS termination, reverse proxy to Keycloak

---

## Repo files related to N1

- [`pfsense-baseline.xml`](../pfsense-baseline.xml) — sanitised pfSense config dump
- [`netlab-interfaces.md`](../netlab-interfaces.md) — current addressing / interface mapping
- [`docs/n1-pfsense-session-handoff.md`](./n1-pfsense-session-handoff.md) — full session log with debug history and 8 plan corrections
- [`docs/n1-cli-closeout.md`](./n1-cli-closeout.md) — repo-side work order (now complete)
- This file — onboarding walkthrough

---

*Questions about N1 → ping CurlyRed in Discord or open an issue tagged `pillar:N1`.*
