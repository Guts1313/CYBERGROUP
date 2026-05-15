# N2 — pfSense Internal Firewall + VLAN Segmentation

**Status:** ✅ deployed and accepted (2026-05-15)
**Owner:** CurlyRed (Serhii)
**Pillar:** Network / N2 — internal firewall + DMZ/SVC/MGMT VLANs on the existing pf-iam-n1
**Related issues:** [#10](https://github.com/Guts1313/CYBERGROUP/issues/10) (N2 — internal FW + VLAN segmentation), [#11](https://github.com/Guts1313/CYBERGROUP/issues/11) (N3 — inter-VLAN ACLs + IP plan)

This doc is for **team members onboarding to the project**. It explains what N2 added on top of N1, how it's wired together, and the pfSense behaviours we discovered along the way that aren't obvious from the GUI. For N1 perimeter context read [`n1-perimeter-firewall.md`](./n1-perimeter-firewall.md) first.

---

## 1. What this is

N2 turns the single-VLAN N1 setup (perimeter + IdP LAN) into a properly segmented internal network. Same `pf-iam-n1` VM, three new OPT interfaces. Five VLANs now terminate on pfSense:

```
                Fontys AnyConnect VPN
                          │
                          ▼
                  ┌───────────────┐
                  │ pf-iam-n1 WAN │  192.168.189.16 (NetLab "internet")
                  └──────┬────────┘
                         │
   ┌─────────────────────┼──────────────────────────────────────┐
   │                     │                                      │
 OPT2 / DMZ          LAN (=IdP)         OPT3 / SVC          OPT4 / MGMT
 10.0.10.0/24       10.0.20.0/24       10.0.30.0/24        10.0.99.0/24
 PVlanB VLAN10      PVlanA VLAN20      PVlanC VLAN30       PVlanD VLAN99
 nginx-edge (D1)    Keycloak +         app-backend, DBs,   pfSense LAN-side
                    KC-Postgres        Loki, Grafana,      mgmt + switches
                                       Prometheus, HR
```

Every VLAN-to-VLAN flow is **deny-by-default**. Allow rules are explicit and listed in [`network/inter-vlan-acl.md`](../network/inter-vlan-acl.md). No User VLAN ever talks to Keycloak directly — internal users reach the IdP through the DMZ NGINX (which D1 will deploy).

User VLANs 40 (User-Admin) and 50 (User-General) are **not** part of N2 part 1. They're deferred to N2 part 2 — see [§8 What's next](#8-whats-next).

---

## 2. Quick facts

| What | Value |
|---|---|
| **VM** | `pf-iam-n1` (same VM as N1 — N2 just added 3 NICs) |
| **pfSense version** | 2.8.1-RELEASE amd64 |
| **Total interfaces** | 5 (WAN + LAN + OPT2 DMZ + OPT3 SVC + OPT4 MGMT) — OPT1 is the WireGuard tunnel from N1 |
| **NIC ↔ pfSense iface mapping** | See [`network/netlab-interfaces.md`](../network/netlab-interfaces.md) — matched by MAC after a PCI reorder caused by hot-adding NICs |
| **Firewall aliases** | 17 — 7 network (`*_NET`) + 10 host (`KEYCLOAK`, `NGINX_EDGE`, etc.) |
| **Filter rules** | ~64 across DMZ/SVC/LAN/MGMT/WG_TUN tabs, tagged with `[#N]` / `[DN]` / `[VN]` / `[#sanity]` per the ACL spec |
| **Default-deny** | logging enabled; visible in Status → System Logs → Firewall |
| **Smoke tests** | 26/26 passed in-lab on 2026-05-15 (10 allow + 11 deny + 4 sanity + 1 logging) |
| **Config dump** | [`pfsense-baseline.xml`](../pfsense-baseline.xml) (sanitised) |
| **Known issue** | TLS GUI cert private key was historically committed unredacted in N1 baseline. Redacted in HEAD; cert rotation + git history scrub deferred. See the XML comment header at the top of `pfsense-baseline.xml`. |

### Static reservations (per ip-plan.md)

| VLAN | Gateway | Notable hosts (static IPs from `ip-plan.md`) |
|---|---|---|
| 10 DMZ | `10.0.10.1` | `nginx-edge` `.10` (future — D1) |
| 20 IdP | `10.0.20.1` | `keycloak` `.10`, `kc-postgres` `.20` |
| 30 SVC | `10.0.30.1` | `app-backend` `.10`, `hr-system` `.11`, `app-postgres` `.20`, `loki` `.30`, `grafana` `.31`, `prometheus` `.32` |
| 99 MGMT | `10.0.99.1` | `pfsense-lan` `.1`, `switch-core` `.10` |

### Trust boundary in one sentence

NGINX in DMZ is the only thing on the internet; everything else sits behind the internal firewall with zero-trust deny-by-default between every pair of VLANs, and no User VLAN ever talks to Keycloak directly — it always goes through DMZ NGINX.

---

## 3. How to inspect or modify the firewall

Same three access paths as N1 — see [`n1-perimeter-firewall.md` §3](./n1-perimeter-firewall.md#3-how-to-access-the-firewall) for the WireGuard client config and the AnyConnect prerequisite.

To inspect rules: log in to pfSense GUI at `https://10.0.20.1` (via WG) → **Firewall → Rules** → pick the tab for the interface whose ingress you care about. Each rule is described `[<tag>] <intent>` where `<tag>` corresponds to a row in [`network/inter-vlan-acl.md`](../network/inter-vlan-acl.md).

To modify: do not freelance. The architectural spec is `inter-vlan-acl.md` — change it first (PR with the doc edit), then change pfSense to match. The whole point of having the spec is that the running config is a faithful expression of it. Drift between the two is a defect — fix the doc and the box together.

---

## 4. What was built (deployment summary)

A "here's the architecture and config logic" view, not a click-through walkthrough. For the click-through, the runbook lived in `network/netlab-n2-runbook.md` during the build — it served its one-shot purpose and was not retained in the repo.

### 4.1 Three new NICs added to `pf-iam-n1` in vCenter

Three VMXNET3 adapters added: Adapter 3 on `0862_PRO07_PVlanB`, Adapter 4 on `0863_PRO07_PVlanC`, Adapter 5 on `0864_PRO07_PVlanD`. On next boot FreeBSD's PCI bus re-numerated and all five `vmx*` assignments shuffled. Recovery is documented in [§7.1 below](#71-adding-nics-reshuffles-pci-ordering). Final `vmx* ↔ port group ↔ role` mapping (by MAC) lives in [`network/netlab-interfaces.md`](../network/netlab-interfaces.md).

### 4.2 Path 4a — single-VLAN trunk (vSphere pre-tags)

The PRO07 PVlan port groups are configured at the vSphere DSwitch level as Type=`VLAN` with a single VLAN ID per port group (PVlanB=862, PVlanC=863, PVlanD=864). vSphere strips the tag before delivering frames to the VM, so pfSense receives untagged frames and does not need VLAN sub-interfaces. Three new OPT interfaces were assigned directly to `vmx2`/`vmx4` (the post-reorder physical NICs for DMZ/MGMT/etc.) — no inner pfSense VLAN tagging required.

If anyone reconfigures the DSwitch to multi-VLAN trunk (Type=`VLAN trunking` with a range), this path no longer applies — pfSense itself would need VLAN sub-interfaces. The underlay map captured in `network/netlab-interfaces.md` records the current single-trunk choice.

### 4.3 17 firewall aliases

Created via **Firewall → Aliases**. Network aliases (7) reference VLAN CIDRs; host aliases (10) reference individual reserved IPs. Using aliases throughout means a future re-IP changes one definition, not 64 rules.

| Network aliases | Host aliases |
|---|---|
| `DMZ_NET 10.0.10.0/24` | `NGINX_EDGE 10.0.10.10` |
| `IDP_NET 10.0.20.0/24` | `KEYCLOAK 10.0.20.10` |
| `SVC_NET 10.0.30.0/24` | `KC_POSTGRES 10.0.20.20` |
| `USER_ADMIN 10.0.40.0/24` | `APP_BACKEND 10.0.30.10` |
| `USER_GENERAL 10.0.50.0/24` | `APP_POSTGRES 10.0.30.20` |
| `MGMT_NET 10.0.99.0/24` | `LOKI 10.0.30.30` |
| `VPN_NET 10.99.100.0/24` | `GRAFANA 10.0.30.31` |
| | `PROMETHEUS 10.0.30.32` |
| | `HR_SYSTEM 10.0.30.11` |
| | `PFSENSE_LAN 10.0.99.1` ¹ |

¹ Naming nit: this alias holds pfSense's MGMT-side IP, not its LAN-side IP. A rename to `PFSENSE_MGMT` is tracked as a low-priority follow-up cleanup.

### 4.4 ~64 filter rules across five tabs

Spread across **Firewall → Rules** tabs: DMZ, SVC, LAN, MGMT, WG_TUN. Per-tab structure:

| Tab | Approx rules | Notable contents |
|-----|--------------|------------------|
| DMZ  | 14 | `[#5]` NGINX→Keycloak:8443, `[#7]` NGINX→app-backend:443, `[#sanity-DMZ]` ICMP, `[#22]` DMZ→Any egress, `[D8]/[D9]/[D10]` cross-VLAN denies |
| SVC  | 16 | `[#8]` app-backend→Keycloak:8443, `[#9]` app-backend→app-postgres, `[#17]` Prom→Keycloak:9000, `[#sanity-SVC]`, `[#24]` SVC→Any egress, `[D12]/[D13]/[D17]` denies |
| LAN (= IdP)  | 14 (+5 N1 baseline retained) | `[#10]` Keycloak→KC-Postgres, `[#23]` IdP→Any egress, `[#sanity-IDP]`, `[D11]` cross-VLAN denies, N1 catch-all egress disabled (rollback-retained) |
| MGMT | 9 | `[#sanity-MGMT]`, default-deny encoded as explicit `[Dn]` blocks to all other VLANs |
| WG_TUN | 8 | `[V1]` VPN→MGMT (SSH+HTTPS), `[V2]` VPN→User-Admin (no-op until N2 part 2), `[V3]` VPN→nginx-edge (no-op until D1), `[V4]/[V5]` denies to IdP/SVC |

The architectural source-of-truth is `network/inter-vlan-acl.md`. Each rule's pfSense Description starts with the tag from that doc — easy two-way audit.

### 4.5 NAT port-forward pre-staged but disabled (awaiting D1)

The N1 baseline had a disabled `WAN TCP/443 → 10.0.10.10:443` NAT rule as a placeholder. After N2 the destination IP `10.0.10.10` is **routable** (DMZ VLAN exists) — but the NGINX VM at that address doesn't yet. So the rule stays disabled. D1 deployment flips it on as part of NGINX bring-up. Also pre-staged: DMZ-tab allow rules `[#5]` and `[#7]` so NGINX can reach Keycloak and the app backend immediately on first boot.

---

## 5. Verification (smoke tests + acceptance)

### 5.1 Seven-box acceptance — all green

| # | Box | Evidence |
|---|---|---|
| 1 | All 5 interfaces (WAN/LAN/DMZ/SVC/MGMT) are up with correct IPs | Status → Dashboard: 5 green interfaces with the gateway IPs from §2 |
| 2 | All 17 firewall aliases exist with correct types and addresses | Firewall → Aliases: 17 entries match the §4.3 list |
| 3 | All rules across DMZ/SVC/LAN/MGMT/WG_TUN tabs match `inter-vlan-acl.md` | Visual audit of each tab against the spec; D-4/D-5/D-6 corrections all applied |
| 4 | Allow tests T-N2-10..19 pass | 10/10 PASS — see §5.2 |
| 5 | Deny tests T-N2-30..40 pass with correct `[D*]` / `[V*]` log entries | 11/11 PASS — see §5.2 |
| 6 | Default-deny logging enabled and firing | Status → System Logs → Settings checked; `Default deny rule IPv4 (1000000103)` entries present from background traffic |
| 7 | Sanitized baseline XML committed | [`pfsense-baseline.xml`](../pfsense-baseline.xml) — refreshed in this PR, all secrets redacted including TLS GUI cert private key |

### 5.2 Smoke-test results — 26/26 PASS (in-lab, 2026-05-15)

| Phase | Tests | Result |
|---|---|---|
| Gateway pings (T-N2-01..04) | 4 | ✅ 4/4 PASS |
| Allow verifications (T-N2-10..19) | 10 | ✅ 10/10 PASS |
| Deny verifications (T-N2-30..40) | 11 | ✅ 11/11 PASS |
| Default-deny logging (T-N2-50) | 1 | ✅ PASS |
| **Total active (Part 1)** | **26** | **✅ 26/26 PASS** |
| Deferred to N2 part 2 (User VLANs) | 8 | not yet exercised |

The test plan that drove these checks (`network/netlab-n2-test-plan.md`) was one-shot scaffolding for the lab session and is not retained in the repo. The acceptance criteria above are the durable record.

---

## 6. Operations

Day-2 tasks you'll likely need to do.

### Add a new allow rule
1. Update [`network/inter-vlan-acl.md`](../network/inter-vlan-acl.md) with the new row (Source / Destination / Port / Justification). Commit + open PR.
2. In pfSense GUI: **Firewall → Rules → \<correct interface tab\>** → + Add (top or bottom depending on intent).
3. Source / Destination: use **`Address or Alias`** dropdown if you're referencing an alias (Host or Network type). See §7.2.
4. Description: paste the tag from `inter-vlan-acl.md` (`[#N]`, `[DN]`, `[VN]`, or `[#sanity]`) so future audits can match the rule to the spec.
5. **Save → Apply Changes.**

### Add an internet-egress allow rule (DMZ/SVC/IdP → public)
Destination must be `Any`, not `WAN address` — see [§7.3](#73-wan-address-is-pfsenses-own-wan-ip-not-out-via-wan). Place the rule **after** the cross-VLAN deny rules on the same tab; first-match semantics rely on the denies catching internal destinations first.

### Verify a rule is firing
**Status → System Logs → Firewall** is the live block log. Filter by Source/Destination/Port to find the flow. The Description column shows the matching rule's name — so `[D9] DMZ → IdP — denied (anything not #5/#6)` blocks tell you exactly which rule denied which packet.

For state-table evidence on allowed flows: **Diagnostics → States**, filter by source or destination IP. A state row with `SYN_SENT` proves the firewall passed the SYN even if the destination didn't respond (useful when testing rules before the upstream service is deployed).

### Export the running config
Same procedure as N1 — see [`n1-perimeter-firewall.md` §6 Export the config](./n1-perimeter-firewall.md#export-the-config-for-backup-or-repo-update). After N2 the redaction list also includes `<prv>` under `<cert>` (the webGUI TLS cert private key — D-7 fix):

```
sed -i.bak \
  -e 's|<bcrypt-hash>[^<]*</bcrypt-hash>|<bcrypt-hash>REDACTED-HASH</bcrypt-hash>|g' \
  -e 's|<privatekey>[^<]*</privatekey>|<privatekey>REDACTED-PRIVATE-KEY</privatekey>|g' \
  -e 's|<prv>[^<]*</prv>|<prv>REDACTED-TLS-PRIVATE-KEY</prv>|g' \
  -e 's|<presharedkey>[^<]*</presharedkey>|<presharedkey>REDACTED-PSK</presharedkey>|g' \
  pfsense-baseline.xml
grep -Ei 'bcrypt|privatekey|<prv>|presharedkey' pfsense-baseline.xml | grep -v REDACTED  # must be empty
```

### Flip the disabled D1 NAT rule on
When D1 deploys NGINX at `10.0.10.10`: **Firewall → NAT → Port Forward** → click the disabled `WAN TCP/443 → 10.0.10.10:443` rule → uncheck Disabled → Save → Apply. Then on **Firewall → Rules → WAN**, do the same for the associated filter rule. Verify with `curl -k https://192.168.189.16/` over AnyConnect VPN — should reach NGINX, not pfSense's own GUI.

---

## 7. pfSense gotchas (learnings from the N2 build)

These are pfSense 2.8.1 behaviours that the original N2 plan didn't account for. Documenting them here so the next person editing the firewall doesn't have to rediscover them.

### 7.1 — Adding NICs reshuffles PCI ordering

When `pf-iam-n1` rebooted after we hot-added three new VMXNET3 NICs in vCenter, FreeBSD's PCI bus re-numerated. All five `vmx*` names shuffled relative to vCenter adapter order — including the WAN and LAN that were stable in N1. The welcome banner showed `WAN → vmx0` with no IPv4, and `LAN → vmx1` carrying the `10.0.20.1/24` that was supposed to be on LAN.

**Don't trust vCenter adapter number to predict pfSense interface name.** Recovery is via pfSense console option `1) Assign Interfaces` — that menu shows the MAC alongside the driver name, so you can match by MAC against the table in `network/netlab-interfaces.md` and reassign each `vmx*` to the correct role.

This applies any time you add or remove a NIC.

### 7.2 — Alias references need "Address or Alias", not "Network"

In the pfSense 2.8.1 firewall rule editor, the Source / Destination dropdown has both `Network` and `Address or Alias` options. For a rule that references one of our defined aliases (`DMZ_NET`, `KEYCLOAK`, etc. — Host or Network type alike), pick **`Address or Alias`**.

The `Network` dropdown rejects alias names with `The field Source bit count is required.` / `Alias entries must be a single host or alias.` It's strictly for typed CIDR input.

We hit this on the first cross-VLAN deny rule attempt during step 6.2 of the build. All ~64 rules use `Address or Alias` for alias references.

Quick-reference cheat sheet:

| Source / Destination is…                    | pfSense dropdown                            |
|---------------------------------------------|---------------------------------------------|
| Any single IP, any alias (Host or Network)  | `Address or Alias`                          |
| A raw CIDR typed by hand                    | `Network`                                   |
| Whole subnet attached to local interface    | `<INTERFACE> subnets` (e.g. `DMZ subnets`)  |
| pfSense's own IP on that interface          | `<INTERFACE> address` (e.g. `WAN address`)  |

### 7.3 — `WAN address` is pfSense's own WAN IP, not "out via WAN"

`WAN address` is a built-in macro meaning *the IP currently assigned to pfSense's WAN-facing interface*. It is **not** the egress direction. A rule with destination `WAN address` only matches traffic targeting pfSense itself on its WAN IP — useless for internet egress.

For internet-egress allow rules (DMZ/IdP/SVC → public internet), the destination must be `Any` and the rule must sit **after** the cross-VLAN deny rules on the same tab. First-match semantics: the explicit denies catch internal destinations first; only true public destinations fall through to the egress allow. See [`network/inter-vlan-acl.md`](../network/inter-vlan-acl.md) rules #22/#23/#24.

We caught this when `curl https://1.1.1.1/` from inside the IdP VLAN timed out — the supposed-replacement egress allow wasn't actually permitting anything. Destination changed to `Any` and reordered to bottom of LAN tab — curl returned a TLS handshake immediately.

A cleaner future revision (tracked as a follow-up cleanup ticket): an `RFC1918` alias inverted as the destination, so the rule matches only true public destinations and the ordering dependency disappears.

### 7.4 — ICMP echo is not implicitly allowed

pfSense does not implicitly allow ICMP to its own interfaces. Under our deny-by-default posture, `ping <gateway>` from inside any VLAN fails — the firewall has no rule matching ICMP and falls through to default-deny.

Each interface needs an explicit `[#sanity]` allow rule: source `<VLAN> subnets`, destination `<VLAN> address`, protocol ICMP, subtype `echoreq`. See [`network/inter-vlan-acl.md`](../network/inter-vlan-acl.md) §2b.

**ICMP subtype:** select `Echo request` (`echoreq`, type 8), **NOT** `Echo reply` (`echorep`, type 0). The two options sit adjacent in pfSense's alphabetised subtype dropdown. Echo replies are handled automatically by pf's stateful tracking; only the outgoing echoreq needs an explicit allow. Picking `echorep` produces a silent failure mode — ping-to-gateway times out on an otherwise-correctly-configured firewall.

---

## 8. What's next

**D1 — NGINX in DMZ (#9).** Unblocked by N2. Deploy NGINX at `10.0.10.10` in PVlanB. Flip the disabled NAT rule on. The pre-staged DMZ-tab rules `[#5]` (→ Keycloak:8443) and `[#7]` (→ app-backend:443) are already in place.

**D3 — TLS / headers / rate-limits (#24).** Locked-value Nginx hardening. Folded into D1's `nginx.conf` per the deployment-architecture research.

**D2 — ModSecurity + OWASP CRS (#23).** Sits in front of D1's NGINX. Detection-only mode first; flip to blocking after the golden-path OIDC flow runs clean.

**M2 — Loki / Grafana / Promtail (#20).** Joint with Guts1313 — Promtail on the Keycloak host means changing his Compose stack. Sync point.

**M3 — Alert rules (#29) and O3 — Keycloak metrics scrape (#49).** Depend on M2.

**N2 part 2 — User VLANs 40 and 50 (re-open / new issue).** Currently no port groups allocated. Either reuse PVlanA/B/C/D with 802.1Q tagging (requires switching DSwitch to multi-VLAN trunk and adding VLAN sub-interfaces in pfSense) or request PVlanE/F from NetLab. The eight `T-N2-60..67` test cases in the lab test plan map to this work.

**Security cleanup — rotate TLS GUI cert + scrub history.** Tracked as a separate follow-up ticket. Not blocking; bounded threat per the inline XML comment in `pfsense-baseline.xml`.

---

## Repo files related to N2

- [`../pfsense-baseline.xml`](../pfsense-baseline.xml) — sanitised pfSense config dump (the running rules; includes the inline D-7 XML comment header)
- [`../network/inter-vlan-acl.md`](../network/inter-vlan-acl.md) — architectural ACL spec (the source-of-truth for the rules in §4.4)
- [`../network/ip-plan.md`](../network/ip-plan.md) — addressing scheme
- [`../network/netlab-interfaces.md`](../network/netlab-interfaces.md) — live NIC + interface state
- [`./n1-perimeter-firewall.md`](./n1-perimeter-firewall.md) — N1 perimeter walkthrough (this doc assumes you've read it)
- This file — N2 onboarding walkthrough

---

*Questions about N2 → ping CurlyRed in Discord or open an issue tagged `pillar:N2`.*
