# Inter-VLAN ACL Matrix — N3 (issue #11)

Scope: every allowed flow between the VLANs defined in [`ip-plan.md`](./ip-plan.md). **Default is deny.** Anything not in the allow table below must be dropped and logged at the internal firewall (pfSense or the dedicated L3 between VLANs).

This document is the source of truth for the firewall config. Drift between this file and the running ruleset is a defect — fix the doc and the box together.

---

## 1. Trust model in one sentence

NGINX in DMZ is the only thing on the internet; everything else sits behind the internal firewall, with **zero-trust deny-by-default between every pair of VLANs**, and **no User VLAN ever talks to Keycloak directly — it always goes through DMZ NGINX**.

Direction notation: `Source → Destination`. Flows are stateful — the return path is implicit.

---

## 2. Allow matrix

| #  | Source                | Destination               | Port / Proto      | Action | Justification (one line)                                                                  |
| -- | --------------------- | ------------------------- | ----------------- | ------ | ----------------------------------------------------------------------------------------- |
| 1  | WAN (any internet)    | `nginx-edge` `10.0.10.10` | `443/tcp`         | allow  | Public HTTPS ingress — NGINX is the only thing exposed (DMZ pattern).                     |
| 2  | WAN (any internet)    | `nginx-edge` `10.0.10.10` | `80/tcp`          | allow  | HTTP → HTTPS redirect only; NGINX returns 301, no app traffic on `:80`.                   |
| 3  | User-General `10.0.50.0/24` | `nginx-edge` `10.0.10.10` | `443/tcp`   | allow  | Internal users reach the demo app through the same NGINX as external users (one ingress). |
| 4  | User-Admin `10.0.40.0/24`   | `nginx-edge` `10.0.10.10` | `443/tcp`   | allow  | Admin users reach the demo app the same way (consistent OIDC flow).                       |
| 5  | `nginx-edge` `10.0.10.10`   | `keycloak` `10.0.20.10`   | `8443/tcp`  | allow  | Reverse-proxy auth requests (OIDC `/auth`, `/token`, `/userinfo`) to Keycloak over TLS.   |
| 6  | `nginx-edge` `10.0.10.10`   | `keycloak` `10.0.20.10`   | `8080/tcp`  | allow  | Same as #5 if Keycloak runs HTTP behind NGINX TLS termination — pick one of #5/#6, not both. Default: **enable #5, disable #6** once Keycloak has a server cert. |
| 7  | `nginx-edge` `10.0.10.10`   | `app-backend` `10.0.30.10` | `443/tcp`  | allow  | NGINX reverse-proxies app API calls to backend in Services.                               |
| 8  | `app-backend` `10.0.30.10`  | `keycloak` `10.0.20.10`   | `8443/tcp`  | allow  | Backend validates JWTs against Keycloak (JWKS fetch, introspection if used).              |
| 9  | `app-backend` `10.0.30.10`  | `app-postgres` `10.0.30.20` | `5432/tcp` | allow  | App reads/writes its own DB. Intra-Services flow but still explicit.                      |
| 10 | `keycloak` `10.0.20.10`     | `kc-postgres` `10.0.20.20` | `5432/tcp` | allow  | Keycloak persists realm + sessions. Intra-IdP, explicit so the rule survives a refactor.  |
| 11 | `hr-system` `10.0.30.11`    | `keycloak` `10.0.20.10`   | `8443/tcp`  | allow  | HR system uses Keycloak as IdP (OIDC) — required for the HR-Manager flow.                 |
| 12 | User-Admin `10.0.40.0/24`   | `nginx-edge` `10.0.10.10` | `443/tcp`   | allow  | Already #4 — Admin reaches Keycloak admin console **via NGINX** at `/admin/`, not directly. |
| 13 | User-Admin `10.0.40.0/24`   | `grafana` `10.0.30.31`    | `3000/tcp`  | allow  | Admin reaches Grafana UI. Restricted to User-Admin source.                                |
| 14 | User-Admin `10.0.40.0/24`   | `pfsense-lan` `10.0.99.1` | `443/tcp`   | allow  | Admin reaches pfSense web UI. Source-locked to User-Admin only.                           |
| 15 | User-Admin `10.0.40.0/24`   | Management `10.0.99.0/24` | `22/tcp`    | allow  | Admin SSH to network appliances. Source-locked to User-Admin only.                        |
| 16 | Promtail (all VLANs)        | `loki` `10.0.30.30`       | `3100/tcp`  | allow  | Log shipping from Keycloak host, NGINX host, pfSense exporter, app backend → Loki (M2).   |
| 17 | `prometheus` `10.0.30.32`   | `keycloak` `10.0.20.10`   | `9000/tcp`  | allow  | Prometheus scrapes Keycloak management/metrics endpoint (per #49).                        |
| 18 | `prometheus` `10.0.30.32`   | `nginx-edge` `10.0.10.10` | `9113/tcp`  | allow  | Prometheus scrapes NGINX exporter (metrics for the WAF/edge).                             |
| 19 | `prometheus` `10.0.30.32`   | `pfsense-lan` `10.0.99.1` | `9100/tcp`  | allow  | Prometheus scrapes node_exporter on pfSense.                                              |
| 20 | All VLANs                   | DNS resolver (pfSense `10.0.X.1`) | `53/udp`, `53/tcp` | allow | Local DNS resolution. Each VLAN uses its own gateway as resolver, no cross-VLAN DNS.   |
| 21 | All VLANs                   | NTP (pfSense `10.0.X.1`)  | `123/udp`   | allow  | Time sync. Token expiry / log correlation breaks without it.                              |
| 22 | DMZ `10.0.10.0/24`          | `Any` ¹                   | `443/tcp`, `80/tcp` | allow | NGINX needs outbound for CRL/OCSP, ACME (Let's Encrypt) if used, package updates. Destination `Any` — see §4 footnote ¹.                |
| 23 | IdP `10.0.20.0/24`          | `Any` ¹                   | `443/tcp`   | allow (egress-restricted) | Keycloak outbound for federation (if used) + package updates. Lock to specific FQDNs via pfSense alias when possible. Destination `Any` — see §4 footnote ¹. |
| 24 | Services `10.0.30.0/24`     | `Any` ¹                   | `443/tcp`   | allow (egress-restricted) | Package updates + outbound API calls from app backend, locked by alias. Destination `Any` — see §4 footnote ¹. |
| 25 | User-General `10.0.50.0/24` | WAN                       | `80/tcp`, `443/tcp` | allow | Normal user internet browsing.                                                            |
| 26 | User-Admin `10.0.40.0/24`   | WAN                       | `80/tcp`, `443/tcp` | allow | Admin internet browsing. Tighter logging on this VLAN.                                    |

---

## 2a. VPN peers (`10.99.100.0/24`) → other VLANs

WireGuard peers terminate on the pfSense `WG_TUN` interface in the overlay subnet `10.99.100.0/24` (see `ip-plan.md` → "VPN tunnel (overlay)" and the operational walkthrough at [`../docs/n1-perimeter-firewall.md`](../docs/n1-perimeter-firewall.md)). Treat the VPN as its own zone with its own allow/deny table — peers do **not** inherit any User-VLAN's privileges by virtue of being on the same `10.x` supernet.

| #  | Source                     | Destination                  | Port / Proto     | Action          | Justification                                                                          |
| -- | -------------------------- | ---------------------------- | ---------------- | --------------- | -------------------------------------------------------------------------------------- |
| V1 | VPN `10.99.100.0/24`        | Management `10.0.99.0/24`    | `22/tcp`, `443/tcp` | allow         | Admins manage pfSense (`443`) and switches (`22`) from VPN.                            |
| V2 | VPN `10.99.100.0/24`        | User-Admin `10.0.40.0/24`    | `443/tcp`        | allow           | Admin-role users working remotely reach admin consoles (Keycloak admin UI, Grafana) via the User-Admin admin-jump-host pattern. |
| V3 | VPN `10.99.100.0/24`        | `nginx-edge` `10.0.10.10`    | `443/tcp`        | allow           | VPN'd users use the app via DMZ NGINX, same path as any other client.                  |
| V4 | VPN `10.99.100.0/24`        | IdP `10.0.20.0/24`           | any              | **deny + log**  | Admins reach Keycloak admin via NGINX in DMZ at `/admin/`, never directly. Mirrors D5/D6 for user VLANs. |
| V5 | VPN `10.99.100.0/24`        | Services `10.0.30.0/24`      | any              | **deny + log**  | Services VLAN hosts internal-only backends; not user-facing. VPN peers reach the app through NGINX (V3), not directly. |

Notes:

- V2's destination is the User-Admin VLAN subnet because Grafana / Keycloak admin UIs are exposed to that VLAN only (see #13 and the admin pattern around #14). If a future change moves those consoles behind NGINX, V2 collapses into V3.
- V4 is the VPN equivalent of D5/D6: no VLAN — not User-General, not User-Admin, not VPN — reaches Keycloak directly.

---

## 2b. Sanity ICMP (diagnostic ping to gateway)

pfSense does **not** implicitly allow ICMP to its own interfaces. Under our deny-by-default posture, `ping <gateway>` from inside any VLAN fails unless each interface has an explicit allow rule. These rules are narrow: only within-VLAN ICMP echo to the gateway IP. They don't enable cross-VLAN ping — the explicit denies in §3 still block ICMP between VLANs.

| #             | Source         | Destination                  | Port / Proto    | Action | Justification                                          |
| ------------- | -------------- | ---------------------------- | --------------- | ------ | ------------------------------------------------------ |
| #sanity-DMZ   | `DMZ subnets`  | `DMZ address` (`10.0.10.1`)  | ICMP `echoreq`  | allow  | Operator diagnostic ping to DMZ gateway.               |
| #sanity-SVC   | `SVC subnets`  | `SVC address` (`10.0.30.1`)  | ICMP `echoreq`  | allow  | Operator diagnostic ping to Services gateway.          |
| #sanity-MGMT  | `MGMT subnets` | `MGMT address` (`10.0.99.1`) | ICMP `echoreq`  | allow  | Operator diagnostic ping to Management gateway.        |
| #sanity-IDP   | `LAN subnets`  | `LAN address` (`10.0.20.1`)  | ICMP `echoreq`  | allow  | Operator diagnostic ping to IdP gateway (LAN tab).     |

**ICMP subtype matters.** Select `Echo request` (`echoreq`, type 8), NOT `Echo reply` (`echorep`, type 0). The two options sit adjacent in pfSense's alphabetised dropdown. Echo replies are handled automatically by pf's stateful tracking — only the outgoing echoreq needs an explicit allow. Picking `echorep` produces a silent failure mode: ping-to-gateway times out on an otherwise correctly-configured firewall.

---

## 3. Explicit denies (called out so they don't become a default-rule footnote)

| #   | Source                  | Destination               | Port / Proto  | Action | Justification                                                                            |
| --- | ----------------------- | ------------------------- | ------------- | ------ | ---------------------------------------------------------------------------------------- |
| D1  | WAN                     | IdP `10.0.20.0/24`        | any           | **deny + log** | Keycloak is **never** reachable from the internet. Period. (Plan: "unreachable from internet".) |
| D2  | WAN                     | Services `10.0.30.0/24`   | any           | **deny + log** | Internal APIs / DBs / Prometheus / Loki / Grafana are not internet-exposed.              |
| D3  | WAN                     | User VLANs                | any           | **deny + log** | Workstations are not internet-exposed (NAT outbound only).                               |
| D4  | WAN                     | Management `10.0.99.0/24` | any           | **deny + log** | Network gear is never exposed.                                                           |
| D5  | User-General `10.0.50.0/24` | IdP `10.0.20.0/24`    | any           | **deny + log** | No User VLAN talks to Keycloak directly — must go through DMZ NGINX (zero-trust).         |
| D6  | User-Admin `10.0.40.0/24`   | IdP `10.0.20.0/24`    | any           | **deny + log** | Same as D5 — Admin reaches Keycloak admin via NGINX `/admin/`, not by direct IP.          |
| D7  | User-General `10.0.50.0/24` | Services `10.0.30.0/24` | any         | **deny + log** | Users reach app via DMZ NGINX only; no direct backend / DB / Grafana access for non-admins. |
| D8  | DMZ `10.0.10.0/24`      | Services `10.0.30.0/24`   | any except #7 | **deny + log** | NGINX may reverse-proxy to `app-backend` only; no access to DBs, Loki, Prometheus, Grafana, HR. |
| D9  | DMZ `10.0.10.0/24`      | IdP `10.0.20.0/24`        | any except #5/#6 | **deny + log** | NGINX talks to Keycloak HTTP/HTTPS only — no SSH, no DB, no management port.             |
| D10 | DMZ `10.0.10.0/24`      | User VLANs                | any           | **deny + log** | A compromised NGINX must not pivot to user workstations.                                 |
| D11 | IdP `10.0.20.0/24`      | Services / User / Mgmt    | any           | **deny + log** | Keycloak does not initiate inbound to anything else (other than #23 egress to WAN).      |
| D12 | Services `10.0.30.0/24` | IdP `10.0.20.0/24`        | any except #8, #11, #17 | **deny + log** | Only token validation / OIDC / Prometheus scrape allowed into IdP from Services. |
| D13 | Services `10.0.30.0/24` | User VLANs                | any           | **deny + log** | Backends do not initiate to user workstations.                                           |
| D14 | User-General `10.0.50.0/24` | Management `10.0.99.0/24` | any       | **deny + log** | Non-admins must not see network management.                                              |
| D15 | User-General `10.0.50.0/24` | User-Admin `10.0.40.0/24` | any       | **deny + log** | One-way isolation: admin workstations are not reachable from general user VLAN.          |
| D16 | Any                     | `kc-postgres` `10.0.20.20`| `5432/tcp`    | **deny + log** (except #10) | Only Keycloak itself talks to its DB.                                       |
| D17 | Any                     | `app-postgres` `10.0.30.20` | `5432/tcp`  | **deny + log** (except #9) | Only `app-backend` talks to the app DB.                                    |
| D18 | **Any → Any**           | **Any**                   | **any**       | **deny + log** (default rule) | Catch-all. If a flow isn't in section 2, it doesn't happen.                |

---

## 4. Notes for the firewall operator

- **Stateful**: all allow rules in section 2 are stateful, so reply traffic does not need its own rule.
- **Logging**: every deny rule logs. Section 2 allows do **not** log per-packet (too noisy); rely on NetFlow / pfSense state table + application logs for the allowed paths.
- **Aliases**: define pfSense aliases for `IDP_NET`, `SVC_NET`, `DMZ_NET`, `USER_ADMIN`, `USER_GENERAL`, `MGMT_NET`, and host aliases for `KEYCLOAK`, `NGINX_EDGE`, `APP_BACKEND`, `LOKI`, `PROMETHEUS`. Rules should reference aliases, not raw IPs, so a re-IP doesn't rewrite the ruleset.
- **Rule order**: deny-by-default goes last. WAN-side denies (D1–D4) go above the WAN allow (#1, #2) only if you express them as "deny WAN → internal except NGINX:443/80"; otherwise rely on the implicit "no rule = no flow" on internal-facing interfaces.
- **Ports recap** (so the operator doesn't have to grep): NGINX `443/80`, Keycloak `8443` (TLS) or `8080` (plain), Keycloak mgmt/metrics `9000`, Postgres `5432`, Loki `3100`, Grafana `3000`, NGINX exporter `9113`, node_exporter `9100`, DNS `53`, NTP `123`, SSH `22`.
- **pfSense rule-editor dropdown types (pfSense 2.8.1).** For any *alias* reference — Host or Network type — pick dropdown **`Address or Alias`** and type the alias name. The `Network` dropdown rejects alias names with a `bit count required` validation error; it accepts only literal CIDR input.

  | Source / Destination is…                    | pfSense dropdown                            |
  |---------------------------------------------|---------------------------------------------|
  | Any single IP, any alias (Host or Network)  | `Address or Alias`                          |
  | A raw CIDR typed by hand                    | `Network`                                   |
  | Whole subnet attached to local interface    | `<INTERFACE> subnets` (e.g. `DMZ subnets`)  |
  | pfSense's own IP on that interface          | `<INTERFACE> address` (e.g. `WAN address`)  |

- **¹ pfSense `WAN address` semantics.** `WAN address` in pfSense rules is a built-in macro meaning *the IP assigned to pfSense's WAN-facing interface*, not *"out via WAN"*. For internet-egress allow rules (#22, #23, #24), the destination must be `Any` and the rule must sit **after** the cross-VLAN deny rules on the same tab. First-match semantics: the denies catch internal destinations first; only true public-internet destinations fall through to the egress allow.
- **Cleaner future revision for #22/#23/#24.** Add an alias `RFC1918` = `{10/8, 172.16/12, 192.168/16}`. Set each egress rule's destination to `Address or Alias` with value `RFC1918` and check **Invert match**. The rule then matches only non-RFC1918 destinations (true public internet); the cross-VLAN-deny ordering dependency disappears. Tracked as a follow-up cleanup ticket; not blocking N2.

---

## 5. Done criteria (mirrors issue #11)

- [x] IP plan exists and is versioned (this repo, `network/ip-plan.md`).
- [x] Inter-VLAN ACL matrix exists and is versioned (this file).
- [x] pfSense ruleset matches sections 2 + 2b + 3 of this document (verified during the N2 in-lab build, 2026-05-15 — 17 aliases + ~64 filter rules across DMZ/SVC/LAN/MGMT/WG_TUN tabs; see `pfsense-baseline.xml`).
- [ ] A `curl https://<wan>` from outside reaches NGINX; a `curl http://10.0.20.10:8080` from User-General is dropped and logged.
