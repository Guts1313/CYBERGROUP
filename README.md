# CYBERGROUP — IAM with Keycloak

Group J / Fontys S7 proof-of-concept for centralised identity & access management on Keycloak, deployed into NetLab.

**Architecture:** internet → pfSense perimeter (VPN + IDS) → DMZ (NGINX + WAF) → internal firewall → IdP VLAN (Keycloak) + Services VLAN + User VLANs. See [`docs/iam-implementation-plan.md`](docs/iam-implementation-plan.md) for the 5-phase rollout and [`docs/n1-perimeter-firewall.md`](docs/n1-perimeter-firewall.md) for the deployed perimeter.

## Pillars

| Pillar | Owner | Status | Docs |
|---|---|---|---|
| **N — Network** (pfSense, VLANs, ACLs) | CurlyRed | N1 + N2 + N3 deployed; D1 next | [`docs/n1-perimeter-firewall.md`](docs/n1-perimeter-firewall.md), [`docs/n2-internal-firewall.md`](docs/n2-internal-firewall.md), [`network/ip-plan.md`](network/ip-plan.md), [`network/inter-vlan-acl.md`](network/inter-vlan-acl.md), [`network/netlab-interfaces.md`](network/netlab-interfaces.md) |
| **K — Keycloak / IAM core** | Guts1313 | Phase A + B + C merged (realm, roles, MFA, clients, JWT) | [`keycloak/`](keycloak/), [`docs/iam/role-access-matrix.md`](docs/iam/role-access-matrix.md), [`docs/ops/keycloak-upgrade.md`](docs/ops/keycloak-upgrade.md) |
| **W — Web app / RBAC** | Guts1313 | W1–W5 merged | [`backend/`](backend/), [`docs/iam/role-access-matrix.md`](docs/iam/role-access-matrix.md) |
| **D — DMZ (NGINX + WAF)** | CurlyRed | drafts ready (D1/D2/D3) | [`dmz/`](dmz/) (unmerged drafts) |
| **M — Monitoring + alerts** | shared | drafts ready (M2/M3); M1/M4 merged | [`monitoring/`](monitoring/) (unmerged drafts), [`docs/testing/role-rbac-test-report.md`](docs/testing/role-rbac-test-report.md) |
| **Security — red-team prep** | shared | drafts ready (#5/#31) | [`docs/security/`](docs/security/) (unmerged drafts) |

## Network reality (as of 2026-05-15)

| Subnet | Purpose | Status |
|---|---|---|
| `10.0.10.0/24` | DMZ (`nginx-edge`) | **VLAN deployed (PVlanB); NGINX VM pending — D1 #9** |
| `10.0.20.0/24` | IdP (Keycloak) | **VLAN deployed (PVlanA); Keycloak VM pending migration from temp host — K1 #12** |
| `10.0.30.0/24` | Services (backend, DBs, monitoring) | **VLAN deployed (PVlanC); service VMs pending** |
| `10.0.40.0/24` | User-Admin | designed only — N2 part 2 (#76) |
| `10.0.50.0/24` | User-General | designed only — N2 part 2 (#76) |
| `10.0.99.0/24` | Management | **VLAN deployed (PVlanD)** |
| `10.99.100.0/24` | WireGuard tunnel overlay | **deployed in NetLab** |

Next sync points: **K1** (Keycloak migration to `10.0.20.10` on PVlanA) and **D1** (NGINX deploy at `10.0.10.10` in DMZ). Once both are live, end-to-end public OIDC flow becomes testable.

## Repo layout

```
backend/          Spring Boot app (W1–W4), OAuth2 resource server, role-based authz
keycloak/         Keycloak compose + realm import + ops scripts
network/          IP plan + inter-VLAN ACLs (live design — N2/N3 still ahead)
dmz/              NGINX + ModSecurity drafts (D1/D2/D3 — unmerged)
monitoring/       Loki/Grafana/Promtail/Prometheus drafts (M2/M3/O3 — unmerged)
docs/             Plan, runbooks, role-access matrix, test reports, security
pfsense-baseline.xml   Sanitised firewall config dump (N1 + N2 — 6 interfaces, 17 aliases, ~64 rules)
network/netlab-interfaces.md   Current NetLab addressing and access notes
```

## Onboarding

If you're new to the project, read in this order:

1. [`docs/iam-implementation-plan.md`](docs/iam-implementation-plan.md) — overall plan
2. [`docs/n1-perimeter-firewall.md`](docs/n1-perimeter-firewall.md) — perimeter walkthrough (architecture, quick facts, runbooks)
3. [`docs/n2-internal-firewall.md`](docs/n2-internal-firewall.md) — internal firewall + VLAN segmentation + pfSense gotchas
4. [`network/ip-plan.md`](network/ip-plan.md) — addressing scheme
5. [`network/inter-vlan-acl.md`](network/inter-vlan-acl.md) — inter-VLAN ACL spec
6. [`network/netlab-interfaces.md`](network/netlab-interfaces.md) — current NetLab state
7. [`docs/iam/role-access-matrix.md`](docs/iam/role-access-matrix.md) — the 5-role permission model

## Issue tracker

[Project board](https://github.com/users/Guts1313/projects/2/views/1). Issues use the pillar prefix in titles (`[N1]`, `[K2]`, `[D3]`, etc.).
