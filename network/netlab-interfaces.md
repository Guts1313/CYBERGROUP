# NetLab Interfaces — Group J / PRO07

Last updated: 2026-05-15 by CurlyRed (Serhii)

This file is an operational reference for the live state of the lab: vCenter
location, what each NIC actually maps to, current IPs, and lab quirks. For the
architectural addressing scheme see `network/ip-plan.md`. For the firewall
ruleset spec see `network/inter-vlan-acl.md`. For onboarding to pfSense and the
gotchas discovered during deployment see `docs/n1-perimeter-firewall.md` and
`docs/n2-internal-firewall.md`.

## vCenter / NetLab environment
- vCenter: vcenter.netlab.fontysict.nl
- Cluster: Netlab-Cluster-B
- Folder / Resource pool: PRO07
- Datastore: DataCluster-Students [NIM01-7]

## pfSense firewall (N1 perimeter + N2 internal — same VM `pf-iam-n1`)

After N2 added three OPT interfaces for DMZ/SVC/MGMT, FreeBSD's PCI bus
re-numerated on reboot. The five `vmx*` names shuffled relative to vCenter
adapter order. Final mapping (matched by MAC during pfSense console-option-1
recovery):

| vCenter Adapter | Port group           | MAC               | pfSense iface | Role + IP                              |
|-----------------|----------------------|-------------------|---------------|----------------------------------------|
| Adapter 1       | `0189_Internet-DHCP` | 00:50:56:97:a0:7d | `vmx1`        | WAN — `192.168.189.16/24` (DHCP)       |
| Adapter 2       | `0861_PRO07_PVlanA`  | 00:50:56:97:3a:51 | `vmx3`        | LAN (VLAN 20, IdP) — `10.0.20.1/24`    |
| Adapter 3       | `0862_PRO07_PVlanB`  | 00:50:56:97:d2:d0 | `vmx4`        | OPT2 / DMZ (VLAN 10) — `10.0.10.1/24`  |
| Adapter 4       | `0863_PRO07_PVlanC`  | 00:50:56:97:a3:63 | `vmx0`        | OPT3 / SVC (VLAN 30) — `10.0.30.1/24`  |
| Adapter 5       | `0864_PRO07_PVlanD`  | 00:50:56:97:bc:dd | `vmx2`        | OPT4 / MGMT (VLAN 99) — `10.0.99.1/24` |

**If anyone adds another NIC**, expect PCI re-numeration on next boot. Don't
trust vCenter adapter order to predict pfSense interface names. Always match
by MAC (pfSense console option `1) Assign Interfaces` shows MAC + link state
per detected NIC).

### Underlay VLAN map (vSphere-side, decoupled from logical numbering)

The PRO07 PVlan port groups are single-VLAN trunks at the DSwitch level
(`Networking → Configure → Policies → VLAN`, Type = `VLAN`, one ID per port
group). vSphere strips the tag at the port group before delivering to the VM,
so pfSense sees untagged frames — no VLAN sub-interfaces needed inside pfSense.

| Port group | vSphere VLAN ID (underlay) | Logical VLAN (what we use) | Function |
|---|---|---|---|
| PVlanA | 861 | 20 | IdP |
| PVlanB | 862 | 10 | DMZ |
| PVlanC | 863 | 30 | Services |
| PVlanD | 864 | 99 | Management |

All docs (`ip-plan.md`, `inter-vlan-acl.md`, rule descriptions) use the
logical numbering exclusively. The 861–864 underlay numbers never appear in
pfSense and exist only to disambiguate if someone is configuring the vSphere
side directly.

### Other pfSense details
- VM name: `pf-iam-n1`
- Template: `Templ_pfSense_2.8.1_firewall`
- pfSense version: `2.8.1-RELEASE amd64` (build 20251126-2112)
- FQDN: `pf-iam-n1.iam.lab.local`
- LAN DHCP pool: `10.0.20.100–10.0.20.150` (Phase-1 testing convenience; the
  IdP VLAN is static-only per `ip-plan.md` — disable the pool once Keycloak
  + KC-Postgres take their static IPs)
- DMZ/SVC/MGMT: DHCP **off** on all three (per `ip-plan.md` static-only policy)
- WG tunnel (`WG_TUN` interface):
  - Network: `10.99.100.0/24` (bumped from plan's `10.0.100.0/24` — AnyConnect VPN routes that range)
  - Server: `10.99.100.1`
  - First peer (`wg-peer-curlyred`): `10.99.100.2/32`
  - Endpoint: `192.168.189.16:51820`
  - pfSense WG public key: `bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=`
- Suricata: alert mode on WAN, ETOpen rules loaded, SID `1:2100498` confirmed
  firing 2026-05-14 (`src 192.168.189.16 → dst 8.8.8.8:53/UDP`)
- Admin password: held in operator's password manager (**not** in repo)
- 17 firewall aliases + ~64 filter rules across DMZ/SVC/LAN/WG_TUN/MGMT tabs
  (see `pfsense-baseline.xml` for the dump; `inter-vlan-acl.md` for the spec)

## Jump host (transient)
- VM name: `iam-lan-test01`
- Template: Kali
- OS hostname: `kali-vm` (cosmetic — vSphere name is canonical)
- LAN IP: `10.0.20.x` (DHCP from pfSense)
- Purpose: GUI access to pfSense from inside PVlanA; can be torn down once
  the team has working WG access

## PRO07 PVlan reservation

| PVlan | Port group           | VLAN | Purpose                                                | Status            |
|-------|----------------------|------|--------------------------------------------------------|-------------------|
| A     | `0861_PRO07_PVlanA`  | 20   | IdP (Keycloak)                                         | claimed (N1)      |
| B     | `0862_PRO07_PVlanB`  | 10   | DMZ (`nginx-edge`)                                     | **claimed (N2)**  |
| C     | `0863_PRO07_PVlanC`  | 30   | Services (app, postgres, monitoring)                   | **claimed (N2)**  |
| D     | `0864_PRO07_PVlanD`  | 99   | Management                                             | **claimed (N2)**  |

VLAN 40/50 (User-Admin / User-General) unmapped — deferred to N2 part 2.
Either reuse existing PVlans with 802.1Q tagging (would require switching
PVlan type to multi-VLAN trunk) or request PVlanE/F from NetLab.

**PVlan mode (confirmed 2026-05-15 during N2 step 4):** single-VLAN trunk.
vSphere pre-tags each port group at the DSwitch level; pfSense receives
untagged frames and does not need VLAN sub-interfaces (path 4a from the N2
runbook applied).

## NetLab access notes (operational)
- Operator laptop reaches NetLab via Cisco AnyConnect to `vpn.netlab.fontysict.nl`
- VPN routes `192.168.0.0/16` + selected `10.x` ranges into NetLab — including
  `192.168.189.0/24` where pfSense WAN sits
- VPN ALSO routes `10.0.100.0/24` (which is why our WG tunnel had to move to
  `10.99.100.0/24`)
- Ping to pfSense WAN times out by design (WAN default-deny drops ICMP); use
  `nc -u -v 192.168.189.16 51820` to probe the open WG port instead

## Lab quirks (not bugs)
- Port group label `0189_Internet-DHCP-192.168.189.0_24` is not authoritative
  — NetLab DHCP can hand out IPs from a disjoint subnet (saw `192.168.230.12/23`
  briefly during the N1 session before stabilising)
- NetLab DHCP soft-locks MACs after `dhclient -r` release/renew churn. Recovery:
  redeploy VM from template (fresh MAC). Do NOT use `dhclient -r` on NetLab WAN.
- Hot-adding NICs to `pf-iam-n1` reshuffles the FreeBSD PCI ordering. Always
  re-match `vmx*` to MAC via pfSense console option 1 after such a change.
