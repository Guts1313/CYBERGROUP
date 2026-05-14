# Implementation plan — IAM with Keycloak

## Phasing at a glance

```
Phase 0  → Pre-flight decisions (no tasks, just unblocks)
Phase 1  → Foundation              N1 · K1(temp) · W1 · M5(kickoff)
Phase 2  → Network + identity core N2 · K2 · K5 · M1 · D1
Phase 3  → Hardening + clients     N3 · K3 · K4 · D2 · D3 · W2
Phase 4  → Authz + observability   W3 · W4 · M2
Phase 5  → Deliverables + verify   W5 · M3 · M4 · M5(execute)
```

Two long poles set the cadence: **N (network)** and **W (web app)**. Keycloak (K) runs in parallel from day 1 on a temporary host, then moves into the IdP VLAN once N2 lands.

---

## Phase 0 — Pre-flight (do before Phase 1 starts)

These are decisions, not tickets — but each one unblocks a card.

| Decision | Unblocks | Owner |
|---|---|---|
| Pick demo app stack (e.g. Next.js / SvelteKit / Spring Boot) | W1 | Web app owner |
| Pick log stack (Loki+Grafana vs ELK-lite) | M2 | Monitoring owner |
| Order hardware MFA tokens (FIDO2 keys for Admin) | K3 | Whoever takes K |
| Engage pentest vendor (already in flight via #5) | M5 | Whoever takes M5 |
| Pick pfSense hardware / VM, decide WireGuard vs OpenVPN | N1 | Network owner |

---

## Phase 1 — Foundation (parallel, ~1 sprint)

Goal: independent foundations stood up, no cross-blocking.

| Task | Why now | Notes |
|---|---|---|
| **N1** pfSense perimeter | Nothing else gates it; long pole | Stand up box, WAN rules, VPN, base IDS/IPS rule set |
| **K1** Keycloak (temp host) | Don't wait for N2 — temp host is fine | Containerised Keycloak + persistent DB, base realm |
| **W1** Scaffold demo app | No deps; needed by W2 onwards | Repo + minimal pages + 2 placeholder APIs + CI |
| **M5** Pentest scoping | External lead time; start early | Define scope, get dates penciled with vendor |

**Phase exit gate:** VPN works, Keycloak admin console reachable on temp host, demo app runs locally for the team, pentest dates booked.

---

## Phase 2 — Network + identity core (~1 sprint)

Goal: real network shape, real Keycloak shape.

| Task | Depends | Notes |
|---|---|---|
| **N2** VLANs (User / Services / IdP) | N1 | After this, Keycloak migrates from temp → IdP VLAN |
| **D1** NGINX + TLS in DMZ | N2 | Plain reverse proxy first; WAF is D2 |
| **K2** 5 realm roles + permission mapping | K1 | Defines the matrix the whole rest of the project enforces |
| **K5** Password policy, token/session lifetimes, SSO | K1 | Small but earlier-better — affects every downstream login |
| **M1** Keycloak event logging | K1 | Cheap; needed before M2 makes sense |

**Phase exit gate:** Keycloak running on IdP VLAN, public traffic reaches internal services through NGINX over TLS, 5 roles exist with a documented matrix, login events visible.

---

## Phase 3 — Hardening + clients (~1 sprint)

Goal: the perimeter actually defends, and apps can authenticate.

| Task | Depends | Notes |
|---|---|---|
| **N3** Inter-VLAN ACLs + IP plan + docs | N2 | Lock the rules down, write them up |
| **D2** ModSecurity + OWASP CRS | D1 | Start in logging mode, then blocking |
| **D3** Rate limiting + secure headers + admin allowlist | D1 | Quick win, parallel with D2 |
| **K3** MFA per role | K2 | Needs hardware tokens on hand for Admin |
| **K4** OIDC clients (Auth Code + PKCE) | K2 | Defines clients the demo app + APIs will use |
| **W2** Demo app as OIDC client | K4 + W1 | First end-to-end login flow lands here |

**Phase exit gate:** A demo user can log into the demo app via Keycloak with MFA, NGINX is blocking known-bad traffic, network ruleset is documented and enforced.

---

## Phase 4 — Authorization + observability (~1 sprint)

Goal: roles actually do something, and you can see what's happening.

| Task | Depends | Notes |
|---|---|---|
| **W3** Backend JWT validation | W2 | Every API endpoint checks the token |
| **W4** Per-role UI/API authorization | W3 + K2 | The K2 matrix becomes real code paths |
| **M2** Central log shipping | N3 + D1 + M1 | Loki+Grafana or ELK-lite, ingest KC + pfSense + NGINX |

**Phase exit gate:** Each of the 5 roles can do only what their matrix entry allows (positive + negative tested), and logs from all three sources land in one place with dashboards.

---

## Phase 5 — Deliverables + verification (~1 sprint)

Goal: client-ready artefacts and external validation.

| Task | Depends | Notes |
|---|---|---|
| **W5** Role/access matrix doc + 5 demo accounts | W4 | Client deliverable |
| **M3** Alert rules | M2 | Brute force, MFA bypass, admin role change, denied traffic |
| **M4** Test plan + role-based test matrix | W5 | Run the full pass, file follow-ups |
| **M5** Pentest execution | (kickoff in P1) | Vendor runs it, you triage findings |

**Phase exit gate:** Client deliverables ready, alerting fires in test scenarios, pentest report received with remediation tickets opened.

---

## Critical path

```
N1 → N2 → N3 ────────────────┐
       └→ D1 → D2/D3         │
                             ▼
K1 → K2 → K3                M2 → M3
       └→ K4 → W2 → W3 → W4 → W5 → M4
W1 ──────────┘
M5 (runs in background P1 → P5)
```

The longest chain is `K1 → K2 → K4 → W2 → W3 → W4 → W5 → M4`. That's where slippage hurts most — keep the K block moving.

---

## Day-1 action list (concrete first moves)

1. **N owner:** rack/boot pfSense, get WAN+LAN reachable, pick VPN protocol.
2. **K owner:** `docker compose up` Keycloak with Postgres on a temp host, create the production realm, change the admin password, take a backup.
3. **W owner:** scaffold the chosen framework into the repo, push, wire CI to "build + lint + test."
4. **M owner:** email the pentest vendor with proposed scope and dates; in parallel, set up an empty Loki/Grafana or ELK-lite instance to be ready for Phase 4.
5. **Whoever:** create labels on the repo (`area:N`, `area:K`, `area:D`, `area:W`, `area:M`, `size:S`, `size:M`, `size:L`) and apply them — makes the board filterable.

---

If you delegate, the cleanest unit of work is **one phase at a time, scoped to one area**, e.g. "implement Phase 2 K-block (K2 + K5)" or "implement Phase 3 D-block (D2 + D3)." That gives the agent a coherent slice with verifiable exit criteria, instead of jumping across the dependency graph.
