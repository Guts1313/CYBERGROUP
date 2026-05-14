# NetLab Interfaces — Group J / PRO07

Last updated: 2026-05-14 by CurlyRed (Serhii)

## vCenter / NetLab environment
- vCenter: vcenter.netlab.fontysict.nl
- Cluster: Netlab-Cluster-B
- Folder / Resource pool: PRO07
- Datastore: DataCluster-Students [NIM01-7]

## pfSense perimeter firewall (N1)
- VM name: pf-iam-n1
- Template: Templ_pfSense_2.8.1_firewall
- pfSense version: 2.8.1-RELEASE amd64 (build 20251126-2112)
- FQDN: pf-iam-n1.iam.lab.local
- NIC mapping (VMXNET3, not E1000):

  | NIC | Driver | pfSense iface | Port group | IP |
  |-----|--------|---------------|------------|-----|
  | Adapter 1 | vmx0 | WAN | `0189_Internet-DHCP-192.168.189.0_24` | `192.168.189.16/24` (DHCP, may churn) |
  | Adapter 2 | vmx1 | LAN | `0861_PRO07_PVlanA` | `10.0.20.1/24` |

- LAN DHCP pool: `10.0.20.100–10.0.20.150` (Phase-1 testing convenience; disable in N2 — IdP VLAN is static-only per `network/ip-plan.md`)
- WG tunnel (corrected from plan — see handoff doc §correction-7):
  - Network: `10.99.100.0/24`
  - Server: `10.99.100.1`
  - First peer (`wg-peer-curlyred`): `10.99.100.2/32`
  - Endpoint: `192.168.189.16:51820`
  - pfSense WG public key: `bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=`
- Suricata: alert mode on WAN, ETOpen rules loaded, SID `1:2100498` confirmed firing (2026-05-14 20:59:36 UTC, src `192.168.189.16` → dst `8.8.8.8:53/UDP`)
- Admin password: held in operator's password manager (**not** in repo)

## Jump host (transient)
- VM name: iam-lan-test01
- Template: Kali
- OS hostname: kali-vm (cosmetic — vSphere name is canonical)
- LAN IP: `10.0.20.x` (DHCP from pfSense)
- Purpose: GUI access to pfSense from inside PVlanA; can be torn down after Suricata + WG verification

## PRO07 PVlan reservation (proposed for the team)

| PVlan | Port group | VLAN | Purpose | Status |
|-------|------------|------|---------|--------|
| A | `0861_PRO07_PVlanA` | 20 | IdP (Keycloak) | **claimed (N1)** |
| B | `0862_PRO07_PVlanB` | 10 | DMZ (`nginx-edge`) | reserved for N2 |
| C | `0863_PRO07_PVlanC` | 30 | Services (app, postgres, monitoring) | reserved |
| D | `0864_PRO07_PVlanD` | 99 | Management | reserved |

VLAN 40/50 (Users) unmapped — defer to N2.

**PVlan mode (confirmed by Guts1313, 2026-05-14):** PRO07 PVlans B/C/D are **trunk-mode** (802.1Q tags pass through; pfSense terminates VLANs as sub-interfaces). N2 can proceed using standard VLAN-on-trunk wiring.

## NetLab access notes (operational)
- Operator laptop reaches NetLab via Cisco AnyConnect to `vpn.netlab.fontysict.nl`
- VPN routes `192.168.0.0/16` + selected `10.x` ranges into NetLab — including `192.168.189.0/24` where pfSense WAN sits
- VPN ALSO routes `10.0.100.0/24` (which is why our WG tunnel had to move to `10.99.100.0/24`)
- Ping to pfSense WAN times out by design (WAN default-deny drops ICMP) — use `nc -u -v 192.168.189.16 51820` to probe the open WG port instead

## Lab quirks (not bugs)
- Port group label `0189_Internet-DHCP-192.168.189.0_24` is not authoritative — NetLab DHCP can hand out IPs from a disjoint subnet (saw `192.168.230.12/23` briefly during this session before stabilising)
- NetLab DHCP soft-locks MACs after `dhclient -r` release/renew churn. Recovery: redeploy VM from template (fresh MAC). Do NOT use `dhclient -r` on NetLab WAN.
