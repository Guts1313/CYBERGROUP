# N1 — pfSense Perimeter Firewall — Session Handoff

**Date:** 2026-05-14
**Operator:** CurlyRed (Serhii), Group J, Fontys S7
**Session channel:** live NetLab session (hands-on driver)
**Handoff target:** repo-side session (GitHub coordination)
**Current position in 10-step plan:** Step 6.2 done (laptop keypair + connectivity analysis). About to create the WireGuard tunnel on pfSense (Step 6.3).

---

## TL;DR

Steps 1–5 closed. Perimeter firewall is up, hardened to baseline (WAN rules + LAN rules + default-deny logging), and reachable from a Kali jump host. About to configure the WireGuard tunnel. **Seven plan corrections** discovered live — they need to land in the repo plan + `netlab-interfaces.md`. No GitHub-side changes yet; Step 10 (issue #8 comment) doesn't fire until Suricata + WG + XML export + acceptance checklist are done.

---

## What got done this session

### Step 1 — VM provisioning ✅
- **Method:** Deployed from `Templ_pfSense_2.8.1_firewall` (NOT a fresh install). Template is pre-configured with NICs assigned, default credentials, and base config. First boot lands at console menu, not installer.
- **VM name:** `pf-iam-n1` (initially typoed as `pf-aim-n1`, renamed in vCenter)
- **Specs:** 2 vCPU / 2 GB RAM / 16 GB thin (template defaults). An earlier manual "New Virtual Machine" wizard was abandoned in favor of the template — that path would have given 4 GB / 90 GB.
- **vCenter location:** Folder/Resource pool `PRO07`, Datastore `DataCluster-Students [NIM01-7]`, Cluster `Netlab-Cluster-B`
- **pfSense version:** `2.8.1-RELEASE amd64` (build 20251126-2112)
- **Netgate Device ID:** `89e297309e18ca8bf518` (post-redeploy; original instance was discarded — see "DHCP lease failure" below)

### Step 2 — Interface assignments ✅
- Adapter 1 (WAN) → port group `0189_Internet-DHCP-192.168.189.0_24` → assigned to `vmx0`
- Adapter 2 (LAN) → port group `0861_PRO07_PVlanA` → assigned to `vmx1`
- **WAN IP (DHCP):** `192.168.189.16/24`
- **LAN IP (template default):** `172.16.2.1/24` (Step 4 re-IPed to `10.0.20.1/24`)
- **Internet egress verified:** ping 8.8.8.8 → 4/4 packets, ~3.9ms RTT, 0% loss

### Step 3 — Setup Wizard ✅
- 3.1 — pfSense VM renamed; Kali jump host `iam-lan-test01` deployed on PVlanA
- 3.2 — Kali got DHCP lease `172.16.2.19/24` from pfSense LAN
- 3.3 — Reached pfSense GUI at `https://172.16.2.1`, logged in with template defaults
- 3.4 — Setup Wizard completed:
  - Hostname `pf-iam-n1`, domain `iam.lab.local`
  - DNS: primary `192.168.200.14`, secondary `192.168.200.13`
  - Timezone Europe/Amsterdam
  - WAN: DHCP, blocks unchecked in wizard (see correction #6 — they re-appeared as active anyway)
  - LAN: kept `172.16.2.1/24` (re-IP separately in Step 4)
  - Admin password changed from template default
  - Reload OK; dashboard shows `pf-iam-n1.iam.lab.local`

### Step 4 — LAN re-IP ✅
- Used console option `2) Set interface(s) IP address` (cleaner than GUI — atomic IP + DHCP scope change)
- New LAN: `10.0.20.1/24`, DHCP pool `10.0.20.100–10.0.20.150`
- Kali released and renewed; new lease `10.0.20.100/24`
- Stale `172.16.2.19/24` address on Kali cleaned manually with `ip addr del`
- GUI now reachable at `https://10.0.20.1`

### Step 5 — Baseline ruleset ✅

**5.1 WAN rules** (Firewall → Rules → WAN):
- WG inbound: Pass IPv4 UDP `any → This Firewall (self):51820`, logged
- DMZ NGINX 443 forward: NAT port-forward + associated filter rule, **disabled** (placeholder for N2 — `10.0.10.10` doesn't exist yet)
- After applying wizard config, Block-private-networks and Block-bogon-networks **appeared as active rules at the top** despite wizard intent — had to manually uncheck both at **Interfaces → WAN → Reserved Networks**. See correction #6.

**5.2 LAN rules** (Firewall → Rules → LAN):
- Removed template's default "allow LAN to any" rules
- Anti-Lockout Rule kept (system, immutable)
- Added four allow rules: `LAN subnets → LAN address` on TCP 443, TCP 22, UDP 53, UDP 123
- Added egress rule: `LAN subnets → !LAN subnets`, any protocol, **logged**
- Verified from Kali: `curl https://10.0.20.1` → 200, `ping 8.8.8.8` → 2/2 replies

**5.3 Default-deny logging** (Status → System Logs → Settings):
- "Log packets matched from the default block rules in the ruleset" — confirmed checked

### Step 6 — WireGuard (in progress)
- 6.0 — WireGuard for Windows installed on operator laptop (downloaded from wireguard.com)
- 6.1 — Keypair generated via "Add empty tunnel" in WG app. **Public key:** `8FcM4mT0BUKLwvWayHNm6HQwi/nO+gvULvZ6yOF3lD4=` (private key stays in the WireGuard app, never leaves laptop)
- 6.2 — Connectivity analysis: `route print` on the laptop revealed:
  - Cisco AnyConnect VPN (`vpn.netlab.fontysict.nl`) pushes `192.168.0.0/16` via gateway `192.168.223.1` (metric 2) — so pfSense WAN at `192.168.189.16` IS reachable at L3 from the laptop. The original assumption that simulated-internet segments were isolated from the VPN was wrong.
  - Ping to `192.168.189.16` from laptop times out — **correct behavior**: pfSense WAN default-deny drops ICMP. The proper handshake test is UDP/51820, which is open per Step 5.1.
  - **Conflict discovered:** VPN already routes `10.0.100.0/24` via `192.168.223.1`. Plan's reserved WG tunnel range `10.0.100.0/24` would collide with this. See correction #7.
- 6.3 — next: create WG tunnel on pfSense using new tunnel range `10.99.100.0/24` per correction #7.

---

## 🚩 PLAN CORRECTIONS — apply to repo

### 1. NIC driver names: `em0/em1` → `vmx0/vmx1`
- Template uses VMXNET3 paravirtualized NICs, not Intel E1000.
- Replace `em0`/`em1` everywhere (Step 2 done-when, Step 5 ruleset interface refs, Step 7 Suricata interface, `netlab-interfaces.md`).
- VMXNET3 is preferable to E1000 — no functional change, just nomenclature.

### 2. WireGuard is an official Netgate package, not third-party — needs explicit Package Manager install
- Plan Step 6 says "WireGuard pkg → tunnel tun_wg0". This wording is **correct** — WG is still a package.
- *Correction to an earlier correction in this file:* I initially wrote "WireGuard is built-in, not a package" — that was wrong. The reality: from pfSense 2.7+, WG is an official Netgate-supported package (no third-party repo needed), but it is **NOT pre-installed by default** in the standard pfSense CE template — including `Templ_pfSense_2.8.1_firewall`.
- Install path: **System → Package Manager → Available Packages → search "WireGuard" → install**. After install, the menu **VPN → WireGuard** appears.
- Documentation reference: https://docs.netgate.com/pfsense/en/latest/vpn/wireguard/index.html

### 3. Block RFC1918 / bogons on WAN — must be OFF in NetLab
- Plan Step 5 baseline says "(3) RFC1918 src block, (4) bogons block" on WAN inbound. **Wrong for NetLab.**
- Per Fontys exercise doc: *"WAN at RFC1918 and bogon networks — verify that the checkboxes are OFF !!"*
- Reason: seclab's "internet" is itself RFC1918 (our WAN is `192.168.189.16/24` — an RFC1918 address). Blocking would break all return traffic.
- Mark this as a documented NetLab-specific deviation from production hardening.

### 4. Template LAN default: `172.16.2.1/24`, not `192.168.1.1/24`
- Plan Step 4 says "Re-IP LAN 192.168.1.0/24 → 10.0.20.0/24".
- Template ships LAN at `172.16.2.1/24`, not factory default.
- Step 4 source range: `172.16.2.0/24 → 10.0.20.0/24`.

### 5. Step 1 done-when needs rewording for template-based deploy
- Original: "Welcome banner" (implies installer ISO).
- Reality: template deploy lands at pre-installed pfSense console menu showing options 1–16 and version banner. No installer.
- New done-when: "VM powered on, console shows `pfSense 2.8.1-RELEASE` banner with assigned NICs visible."

### 6. Block-private / Block-bogon toggles can persist after wizard — verify at the interface
- Wizard offers checkboxes to disable RFC1918 / bogon blocking. We unchecked them.
- After wizard reload, the auto-generated block rules **still appeared as active** on the WAN rules tab (bogon rule already had 560 B of matched traffic).
- **Operational fix:** after wizard, go to **Interfaces → WAN → Reserved Networks** and explicitly uncheck both `Block private networks and loopback addresses` and `Block bogon networks`, then Save + Apply.
- Update Step 5 acceptance: "verify the auto-generated block rules do NOT appear on the WAN rules tab after applying."

### 7. WG tunnel range conflicts with NetLab VPN routes — change to `10.99.100.0/24`
- Plan reserves `10.0.100.0/24` for the WG tunnel.
- NetLab's Cisco AnyConnect VPN already pushes `10.0.100.0/24` via `192.168.223.1` (in laptop's route table). Using the same /24 causes routing ambiguity — Windows prefers the more-specific VPN route over the WG tunnel for traffic to `10.0.100.x`, breaking handshake/keepalive paths.
- **New WG tunnel range: `10.99.100.0/24`**
  - WG server (pfSense tunnel-side): `10.99.100.1/24`
  - First peer (wg-peer-curlyred): `10.99.100.2/32`
- All other plan IPs (LAN `10.0.20.x`, DMZ `10.0.10.x`, Services `10.0.30.x`, etc.) are clean — none in the VPN route table.
- Update plan canonical: `VPN tunnel — 10.0.100.0/24` → `VPN tunnel — 10.99.100.0/24`.
- Update Step 10 unblock comment template.

### 8. pfSense 2.8.1 UI label: "LAN subnets", not "LAN net"
- In rule editors, the source/destination option matching the whole LAN range is labeled **`LAN subnets`** in 2.8.1 (older docs/screenshots say `LAN net`).
- Functionally identical. Distinct from `LAN address` (matches only `10.0.20.1`).
- Minor — update plan/wiki language for consistency.

---

## NetLab port group convention (proposed for the team)

Claimed PVlanA for VLAN 20 (IdP). Recommend the team adopt this mapping:

| PVlan | NetLab Port Group | VLAN | Purpose | Status |
|---|---|---|---|---|
| A | `0861_PRO07_PVlanA` | VLAN 20 | IdP (Keycloak) | **claimed (N1)** |
| B | `0862_PRO07_PVlanB` | VLAN 10 | DMZ (nginx-edge) | reserved for N2 |
| C | `0863_PRO07_PVlanC` | VLAN 30 | Services (app, postgres, monitoring) | reserved |
| D | `0864_PRO07_PVlanD` | VLAN 99 | Management | reserved |

VLAN 40/50 (Users) unmapped — defer to N2.

**Open question for the team:** Are PRO07 PVlans configured as 802.1Q trunks or access-mode only? Affects N2 VLAN strategy. Doesn't block N1.

---

## Lab quirks worth recording

### Port group label ≠ actual DHCP-served subnet
- Label says `192.168.189.0/24`. Earlier session lease was `192.168.230.12/23` — disjoint. Final lease `192.168.189.16` matches label.
- NetLab apparently bridges multiple upstream subnets through the same port group. **Don't trust port group labels as ground truth for actual L3 subnet.**

### DHCP lease failure mode and recovery
- Pattern: original VM got a lease, then `dhclient -r` (release) + `dhclient` (renew) cycle worked once, then subsequent renewals silently failed — DHCPDISCOVER outbound, zero DHCPOFFER inbound (tcpdump confirmed).
- Reboot didn't recover. `/etc/rc.linkup` restart didn't recover.
- **Resolution:** redeploy from template (fresh MAC) → lease acquired immediately.
- **Lesson:** do NOT use `dhclient -r` on NetLab WAN. NetLab DHCP soft-locks MACs after release/renew churn. If a lease goes stuck, redeploy or change MAC.

### NetLab VPN routing — laptop CAN reach simulated-internet segments
- Cisco AnyConnect to `vpn.netlab.fontysict.nl` pushes:
  - `192.168.0.0/16` via `192.168.223.1` (covers `192.168.189.0/24` WAN, `192.168.200.10/.11` seclab DNS, and others)
  - Multiple `10.x.x.x/24` ranges including `10.0.100.0/24` (the collision noted in correction #7)
  - `10.17.0.0/20`, `10.22.0.0/20` (probably Fontys management subnets — not relevant)
- **Operator laptop on the VPN is a valid WG client endpoint.** No need for the "Remote Access VM" pattern from the exercise PDF.
- Ping to pfSense WAN times out because pfSense's default-deny drops ICMP — correct security posture, not a connectivity problem. Probe with `nc -u -v 192.168.189.16 51820` for the open port.

---

## Current critical facts (snapshot)

- **pfSense WAN IP (NetLab-internal RFC1918):** `192.168.189.16` — DHCP, may change on lease renewal
- **pfSense LAN IP:** `10.0.20.1/24`, DHCP pool `10.0.20.100–10.0.20.150`
- **WG tunnel network (NEW per correction #7):** `10.99.100.0/24` — server `.1`, peer `wg-peer-curlyred` at `.2`
- **WG endpoint:** `192.168.189.16:51820` (assuming current WAN IP holds — recheck at handshake time)
- **WG peer public key (laptop):** `8FcM4mT0BUKLwvWayHNm6HQwi/nO+gvULvZ6yOF3lD4=`
- **Hostname / FQDN:** `pf-iam-n1.iam.lab.local`
- **Kali jump host:** `iam-lan-test01`, `10.0.20.100/24` on PVlanA. Transient. OS hostname `kali-vm` (cosmetic).
- **pfSense admin password:** set during wizard, in operator's password manager. Not in this file, not in the repo.

---

## Next actions

### Hands-on operator (live NetLab session) — continue Step 6
1. Create WG tunnel `tun_wg0` on pfSense: VPN → WireGuard → Tunnels → Add (listen UDP 51820, address `10.99.100.1/24`)
2. Add peer `wg-peer-curlyred` with the laptop's public key, allowed IPs `10.99.100.2/32`
3. Enable WG service, assign WG interface (Interfaces → Assignments → add `wg0`)
4. Add WG-interface firewall rules per plan (WG → LAN 443, WG → LAN 22, default deny)
5. Build the client config text on the laptop side (Address `10.99.100.2/32`, DNS `10.0.20.1`, AllowedIPs `10.0.0.0/16`, Endpoint `192.168.189.16:51820`)
6. Activate the tunnel on the laptop. Verify handshake (last handshake ≤ 60s ago, bytes counter incrementing).
7. From laptop with WG active: confirm `https://10.0.20.1` and `ping 10.0.20.1` work. End-to-end tunnel proof.
8. Step 7 — Suricata install + ETOpen + alert mode + trigger SID 2100498
9. Step 8 — XML export + sanitisation
10. Step 9 — 7-box acceptance checklist
11. Step 10 — issue #8 unblock comment

### Repo side (this handoff target)
1. **Update plan doc** with the 8 corrections above. Key canonical changes:
   - `em0`/`em1` → `vmx0`/`vmx1`
   - WireGuard description — clarify it's the official Netgate package, installed via **System → Package Manager** (not pre-installed in the template)
   - Step 5 baseline: Block RFC1918 / bogons → **OFF** in lab + gotcha that the wizard alone doesn't enforce it (verify at Interfaces → WAN → Reserved Networks)
   - Step 4 source range: `172.16.2.0/24`
   - Step 1 done-when: console menu, not installer Welcome
   - **WG tunnel range: `10.0.100.0/24` → `10.99.100.0/24`** (server `.1`, peer `.2`)
   - Rule editor language: "LAN subnets" not "LAN net" for 2.8.1
2. **Create / initialize `netlab-interfaces.md`** with: vCenter facts (PRO07, DataCluster-Students, Netlab-Cluster-B), NIC mappings (`vmx0`/`vmx1` with port groups), PVlan-to-VLAN proposed mapping table, current WAN/LAN IPs, WG tunnel range, lab-quirk notes.
3. **Coordinate with Guts1313 on PVlan reservations** — confirm B/C/D reservations work for his pillar.
4. **No issue #8 comment yet.** Step 10 fires only after Steps 6–9 are done.

---

## Plan items still outstanding

- Step 6 — WireGuard tunnel (in progress, using `10.99.100.0/24` and built-in module)
- Step 7 — Suricata + ETOpen, alert mode, trigger SID 2100498
- Step 8 — XML export + sanitisation per the redaction list
- Step 9 — 7-box acceptance checklist
- Step 10 — issue #8 unblock comment

Steps 1–5 closed.
