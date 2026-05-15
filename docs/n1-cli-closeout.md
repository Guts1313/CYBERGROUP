# N1 — Closeout (Steps 9 + 10)

**Date:** 2026-05-14
**Operator:** CurlyRed (Serhii)
**This file's job:** describe the repo-side operations needed to close N1 acceptance.

**Read first (context):** `n1-pfsense-session-handoff.md` (from the same session). This closeout assumes that doc has already been ingested — corrections, IP plan changes, port group conventions, lab quirks are all there.

---

## Current state

Steps 1–8 done in the lab session:

- **Steps 1–6:** pfSense `pf-iam-n1` deployed, configured, baseline ruleset applied, WireGuard `tun_wg0` tunnel verified end-to-end (handshake works, laptop reaches `https://10.0.20.1` only via tunnel)
- **Step 7:** Suricata installed in alert mode on WAN. SID `1:2100498` ("GPL ATTACK_RESPONSE id check returned root") fired and captured. Screenshot evidence saved by operator.
- **Step 8.1:** Operator downloaded `pfsense-baseline.xml` from pfSense GUI to laptop. File is in the operator's repo working directory (or they will provide path).

Remaining = **Steps 9 (acceptance verification) and 10 (issue #8 unblock comment)**, plus the repo-side artifacts (sanitised XML committed, `netlab-interfaces.md` created, plan doc patched).

---

## Repo-side operations — in order

### 1. Ingest the pfSense XML provided by the operator
Operator hands `pfsense-baseline.xml` (raw, unsanitised) from their machine. The file is whatever pfSense's "Backup Configuration" exported (full XML, RRD data skipped, no encryption).

### 2. Sanitise the XML
Replace per the plan's redaction list:

| Pattern | Replacement |
|---|---|
| `<password>...</password>` | `<password>REDACTED</password>` |
| `<privatekey>...</privatekey>` | `<privatekey>REDACTED-PRIVATE-KEY</privatekey>` |
| `<presharedkey>...</presharedkey>` | `<presharedkey>REDACTED-PSK</presharedkey>` |
| `<bcrypt-hash>...</bcrypt-hash>` | `<bcrypt-hash>REDACTED-HASH</bcrypt-hash>` |

**WAN IP redaction: SKIP.** WAN IP is `192.168.189.16` — RFC1918, NetLab-internal, not routable on the open internet. Per the plan's rule ("WAN public IP **if routable**"), no redaction needed. Leave it in the XML as documented infrastructure detail. Note this decision in the commit message.

**Sanitisation one-liner (Linux/macOS sed):**
```bash
sed -i.bak \
  -e 's|<password>[^<]*</password>|<password>REDACTED</password>|g' \
  -e 's|<privatekey>[^<]*</privatekey>|<privatekey>REDACTED-PRIVATE-KEY</privatekey>|g' \
  -e 's|<presharedkey>[^<]*</presharedkey>|<presharedkey>REDACTED-PSK</presharedkey>|g' \
  -e 's|<bcrypt-hash>[^<]*</bcrypt-hash>|<bcrypt-hash>REDACTED-HASH</bcrypt-hash>|g' \
  pfsense-baseline.xml
```

**Verify after sanitising:**
```bash
grep -Ei 'password|privatekey|presharedkey|bcrypt' pfsense-baseline.xml | grep -v REDACTED
```
Output must be **empty**. If any line comes back, a secret leaked through — investigate (different tag, attribute-style, etc.) and patch the regex.

Delete `pfsense-baseline.xml.bak` after verification to avoid committing the unsanitised version.

### 3. Create `netlab-interfaces.md` in the repo root

Template below — fill in with real values from the previous handoff doc. This is acceptance box #7.

```markdown
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
  | Adapter 1 | vmx0 | WAN | 0189_Internet-DHCP-192.168.189.0_24 | 192.168.189.16/24 (DHCP, may churn) |
  | Adapter 2 | vmx1 | LAN | 0861_PRO07_PVlanA | 10.0.20.1/24 |
- LAN DHCP pool: 10.0.20.100–10.0.20.150
- WG tunnel (corrected from plan — see correction #7 in handoff doc):
  - Network: 10.99.100.0/24
  - Server: 10.99.100.1
  - First peer (wg-peer-curlyred): 10.99.100.2/32
  - Endpoint: 192.168.189.16:51820
  - pfSense WG public key: bjkVpTXusEJotmuc5we3nviD5ssWlGhIxF4ZLlPYf0A=
- Suricata: alert mode on WAN, ETOpen rules loaded, SID 2100498 confirmed firing (2026-05-14 20:59:36 UTC, src 192.168.189.16 dst 8.8.8.8:53/UDP)
- Admin password: held in operator's password manager (NOT in repo)

## Jump host (transient)
- VM name: iam-lan-test01
- Template: Kali
- OS hostname: kali-vm (cosmetic — vSphere name is canonical)
- LAN IP: 10.0.20.x (DHCP from pfSense)
- Purpose: GUI access to pfSense from inside PVlanA; can be torn down after Suricata + WG verification

## PRO07 PVlan reservation (proposed for the team)
| PVlan | Port group | VLAN | Purpose | Status |
|-------|------------|------|---------|--------|
| A | 0861_PRO07_PVlanA | 20 | IdP (Keycloak) | claimed (N1) |
| B | 0862_PRO07_PVlanB | 10 | DMZ (nginx-edge) | reserved for N2 |
| C | 0863_PRO07_PVlanC | 30 | Services (app, postgres, monitoring) | reserved |
| D | 0864_PRO07_PVlanD | 99 | Management | reserved |

VLAN 40/50 (Users) unmapped — defer to N2.

**Open question for Guts1313:** Are PRO07 PVlan port groups configured as trunked (802.1Q) or access-mode? Affects N2 VLAN strategy.

## NetLab access notes (operational)
- Operator laptop reaches NetLab via Cisco AnyConnect to vpn.netlab.fontysict.nl
- VPN routes 192.168.0.0/16 + selected 10.x ranges into NetLab — including 192.168.189.0/24 where pfSense WAN sits
- VPN ALSO routes 10.0.100.0/24 (which is why our WG tunnel had to move to 10.99.100.0/24)
- Ping to pfSense WAN times out by design (WAN default-deny drops ICMP) — use `nc -u -v 192.168.189.16 51820` to probe the open WG port instead

## Lab quirks (not bugs)
- Port group label `0189_Internet-DHCP-192.168.189.0_24` is not authoritative — NetLab DHCP can hand out IPs from a disjoint subnet (saw 192.168.230.12/23 briefly during this session before stabilising)
- NetLab DHCP soft-locks MACs after dhclient -r release/renew churn. Recovery: redeploy VM from template (fresh MAC). Do NOT use `dhclient -r` on NetLab WAN.
```

### 4. Update the plan doc
Apply the 8 corrections from the handoff doc to the plan. Key canonical changes (full list in handoff):
- `em0/em1` → `vmx0/vmx1`
- WireGuard is the official Netgate package, installed via System → Package Manager (not pre-installed in the template)
- Block RFC1918 / bogons on WAN: OFF in NetLab (and *verify at Interfaces → WAN → Reserved Networks* — wizard alone doesn't guarantee this)
- Step 4 source range: 172.16.2.0/24
- Step 1 done-when: pfSense console menu, not installer welcome
- **WG tunnel network: 10.0.100.0/24 → 10.99.100.0/24** (server .1, first peer .2). Update Step 10 unblock comment template.
- pfSense 2.8.1 rule editor label: "LAN subnets" (not "LAN net")

### 5. Add the team-onboarding walkthrough doc

Commit `n1-perimeter-firewall.md` (provided by operator alongside this closeout) to `docs/` in the repo. This is the **evergreen onboarding doc** for team members joining the project — it bakes in the corrections, explains the architecture, has add-a-WG-peer / recover-DHCP / view-Suricata-alerts runbooks, and is the canonical reference once N1 is closed.

If the repo doesn't have a `docs/` folder, create one. Suggested final location: `docs/n1-perimeter-firewall.md`. The walkthrough's internal links assume that path (it references `./n1-pfsense-session-handoff.md` and `./n1-cli-closeout.md` as siblings).

Also: if the repo's root `README.md` doesn't yet have a section per pillar, add a short "N1 — Perimeter Firewall" entry that links to `docs/n1-perimeter-firewall.md`. Helps Angel and future contributors find the right doc on first visit.

### 6. Commit

```
git add pfsense-baseline.xml netlab-interfaces.md docs/n1-perimeter-firewall.md docs/n1-pfsense-session-handoff.md docs/n1-cli-closeout.md docs/plan-N1.md  # or wherever plan lives
git commit -m "N1: close-out — perimeter firewall up, baseline ruleset, WG tunnel, Suricata

- Add sanitised pfSense XML config (password/privatekey/presharedkey/bcrypt-hash redacted)
- WAN IP not redacted: 192.168.189.16 is NetLab-internal RFC1918, not a routable public IP
- Add netlab-interfaces.md with vCenter/NIC/PVlan/access info (acceptance #7)
- Add docs/n1-perimeter-firewall.md — team onboarding walkthrough (architecture, runbooks, quick facts)
- Add docs/n1-pfsense-session-handoff.md — session log with debug history and corrections
- Add docs/n1-cli-closeout.md — this work order
- Apply 8 plan corrections discovered during live deploy (see handoff doc)
- WG tunnel range changed: 10.0.100.0/24 → 10.99.100.0/24 (NetLab VPN route conflict)

Refs #8
"
git push
```

### 7. Post the issue #8 unblock comment

Final wording (corrections applied):

> **pfSense perimeter firewall up.**
>
> - IdP VLAN: 10.0.20.0/24, gateway 10.0.20.1, DHCP pool 10.0.20.100–10.0.20.150
> - Keycloak target IP: 10.0.20.10 (HTTP 8080, mgmt 9000), KC-Postgres 10.0.20.20
> - WG tunnel: **10.99.100.0/24** (changed from plan's 10.0.100.0/24 — NetLab AnyConnect VPN already routes 10.0.100.0/24, causes routing conflict on client side; see `netlab-interfaces.md` and handoff doc correction #7)
> - WG endpoint: **192.168.189.16:51820** (NetLab-internal RFC1918, reachable via Fontys AnyConnect VPN)
> - Suricata on WAN in alert mode, ETOpen rules loaded, SID 2100498 confirmed firing
> - Baseline firewall ruleset applied (with NetLab corrections: Block RFC1918/bogons OFF on WAN since seclab "internet" is itself RFC1918)
>
> @Guts1313 — ready for Keycloak migration into the IdP VLAN. Tag me if you need the WG client config, admin creds, or anything else.

### 8. Coordinate with Guts1313 (optional but recommended)
Drop a short message in Discord or as an issue comment confirming the PVlan reservation table proposed in `netlab-interfaces.md`. He has the rest of the pod's PVlans (B/C/D) effectively reserved for DMZ/Services/Management work. If he had different plans for any of them, surface that now before N2 starts.

---

## Step 9 — Acceptance checklist (final tally)

| # | Box | State | Evidence / where in repo |
|---|---|---|---|
| 1 | pfSense reachable on LAN, NOT on WAN | ✅ | LAN GUI works from Kali; WAN ping drops (default-deny). |
| 2 | WAN inbound rules match baseline | ✅ | Confirmed live: UDP/51820 pass, NAT/443 disabled, Block-private/bogon off. Reflected in `pfsense-baseline.xml`. |
| 3 | NAT port-forward to 10.0.10.10:443 exists and is disabled | ✅ | Visible in Firewall → NAT → Port Forward, and in `pfsense-baseline.xml`. |
| 4 | WG handshake works; LAN resource reachable only via tunnel | ✅ | `wg show` confirms recent handshake + bidirectional bytes. Laptop browser hits `https://10.0.20.1` only when WG active. |
| 5 | Suricata installed, ETOpen loaded, alert mode, ≥1 signature fired | ✅ | SID 1:2100498 alert captured 2026-05-14 20:59:36. Operator has screenshot. |
| 6 | pfsense-baseline.xml sanitised | ⬜ → ✅ | done during commit (Step 2 above). |
| 7 | netlab-interfaces.md filled with real NIC names / IPs / date | ⬜ → ✅ | created from template above (Step 3 above). |

**Once Steps 2 + 3 + 5 + 6 are done, all 7 boxes are green.**

---

## Completion report (what to confirm back to Serhii / the team)

After committing and posting, summarise back:

> N1 closed. Commits pushed to `github.com/Guts1313/CYBERGROUP`:
> - `pfsense-baseline.xml` (sanitised, WAN IP retained per RFC1918 exception)
> - `netlab-interfaces.md` (vCenter/NIC/PVlan/WG/access info)
> - `docs/n1-perimeter-firewall.md` (team onboarding walkthrough)
> - `docs/n1-pfsense-session-handoff.md` (session log + 8 corrections)
> - `docs/n1-cli-closeout.md` (this work order)
> - Plan doc patched with 8 corrections
>
> Issue #8 unblocked with the unblock comment. @Guts1313 tagged; Keycloak migration into IdP VLAN can proceed.
>
> All 7 acceptance boxes green.

---

## Files needed for the repo-side work

1. `pfsense-baseline.xml` (raw, downloaded from pfSense GUI on laptop)
2. `n1-perimeter-firewall.md` (team onboarding walkthrough)
3. `n1-pfsense-session-handoff.md` (the earlier session log)
4. `n1-cli-closeout.md` (this file)
5. The Suricata alert screenshot (optional — for repo evidence under `docs/evidence/n1-suricata-sid-2100498.png` or similar; not required for acceptance but useful proof)

That's it. After the repo-side work completes, N1 is done.
