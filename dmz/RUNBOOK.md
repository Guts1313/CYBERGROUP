# CYBERGROUP — NetLab deployment runbook (DMZ edge + backend split)

How the IAM stack is deployed across the three VLANs, and how to bring any piece
back with a single `docker compose` command.

## Topology — who runs what

| VLAN | VM | IP | Container(s) | Compose file |
|---|---|---|---|---|
| **IdP** `10.0.20.0/24` | `cybergroup-backend` (Ubuntu) | `10.0.20.10` | `keycloak`, `keycloak-postgres` | `compose.yml` + `dmz/compose.dmz.yml` |
| **DMZ** `10.0.10.0/24` | `n2-test-dmz` (Kali) | `10.0.10.10` | `nginx-edge` | `dmz/compose.edge.yml` |
| **Services** `10.0.30.0/24` | `n2-test-svc` (Kali) | `10.0.30.10` | `iam-backend` | `dmz/compose.backend.yml` |

Public entry: `https://192.168.189.16/` → pfSense **NAT** (WAN:443) → **nginx-edge** (DMZ)
→ Keycloak (`/realms`, `/admin`) + backend (`/api`). nginx also serves the demo console
(static, from `demo-console/`). pfSense allows only: WAN→nginx:443, nginx→keycloak:8080
(`#6`), nginx→backend:8081 (`#7`), backend→keycloak:8080 (`#8`), plus DNS/NTP/egress —
every other cross-VLAN flow is denied (zero-trust).

## Restart after containers are deleted

Each VM restores its own container(s) with one command. The VM IPs and the self-signed
cert are persistent, so only the containers need recreating.

**IdP VM (`cybergroup-backend`)** — Keycloak + Postgres:
```bash
cd ~/CYBERGROUP
docker compose -p cybergroup -f compose.yml -f dmz/compose.dmz.yml up -d keycloak postgres
```

**DMZ VM (`n2-test-dmz`)** — nginx edge:
```bash
cd ~/CYBERGROUP/dmz && docker compose -f compose.edge.yml up -d
```

**Services VM (`n2-test-svc`)** — backend:
```bash
cd ~/CYBERGROUP/dmz && docker compose -f compose.backend.yml up -d
```

> Use **`docker compose`** (v2, space). The Ubuntu VM's old `docker-compose` (v1) crashes
> on modern Docker (`KeyError: ContainerConfig`); the v2 plugin is installed at
> `/usr/local/lib/docker/cli-plugins/`. The Kali VMs' egress is **443-only** — install
> tools over HTTPS, not apt port 80.

## Keycloak realm config

The lab realm settings — Frontend URL, `iam-frontend` redirect/web-origin/post-logout URIs
(all `https://192.168.189.16`), and `loginTheme=cybergroup` — are applied with `kcadm` and
stored in **Postgres**, so they survive container restarts. They are **not** in the
realm-import JSON. So they only need re-applying if the **`keycloak-postgres` volume is
wiped** (which re-imports the realm from JSON):
```bash
# on cybergroup-backend, only after a volume wipe:
bash dmz/lab-kc-frontdoor.sh   # Frontend URL + redirect URIs + post-logout
bash dmz/lab-kc-theme.sh       # mount theme + loginTheme=cybergroup
```

## Admin dashboard — active sessions + login events (audit evidence)

Two **admin-only panels** in the demo console: **Active sessions** (who is logged in right
now — live state) and **Login events** (the persistent audit trail — every login, logout and
failed attempt). The backend endpoints `GET /api/admin/sessions` and `GET /api/admin/events`
(admin role only) **relay the admin's own token** to Keycloak's Admin REST API; no service
account or client secret is stored.

**Quickest — one identical command per VM** (auto-detects the VM, runs only its step):
```bash
cd ~/CYBERGROUP && git pull && bash dmz/deploy.sh
```

Or run each VM's step manually:

1. **Keycloak** (`cybergroup-backend`) — grant the `admin` role read access to sessions.
   One-time; stored in Postgres, so it survives restarts (re-run only after a volume wipe):
   ```bash
   bash dmz/lab-kc-admin-roles.sh   # grants view-users + view-clients + view-events to 'admin'
   ```
2. **Backend** (`n2-test-svc`) — rebuild so it picks up the new endpoint + `KEYCLOAK_BASE_URL`:
   ```bash
   cd ~/CYBERGROUP/dmz && docker compose -f compose.backend.yml up -d --build
   ```
3. **Console** (`n2-test-dmz`) — refresh the static console so nginx serves the new panel:
   ```bash
   cd ~/CYBERGROUP && git pull   # updates demo-console/index.html (served read-only by nginx)
   ```

Then **log in as `admin-test`** (re-login if already signed in, so the new roles are in the
token) → the "Active sessions" and "Login events" panels populate for the admin.

## What this branch deployed (summary of changes)

- **D1 / D3** — nginx reverse proxy + TLS termination + D3 hardening (`dmz/nginx.lab.conf`)
  on the DMZ VM; pfSense NAT (`WAN:443→10.0.10.10`) and DMZ rule `#6` enabled.
- **Keycloak** pinned to static `10.0.20.10` (IdP), made proxy-aware (`KC_PROXY_HEADERS`),
  Frontend URL + redirect/post-logout URIs repointed to the front door, CYBERGROUP login
  theme mounted + selected.
- **Backend split** — the stateless `iam-backend` moved to the Services VLAN (`10.0.30.10`);
  firewall rules `#7` (nginx→backend) and `#8` (backend→Keycloak) now carry real cross-VLAN
  traffic — the zero-trust segmentation demonstrated end-to-end.
- **Helper scripts** (`dmz/lab-*.sh`) do the one-time box prep on each VM (re-IP, Docker +
  compose-v2 install, cert, image build). The `compose.*.yml` files above are the canonical
  way to (re)create the containers afterwards.

## Known follow-ups (not part of the working demo)

- **Admin console** (master realm) still resolves the old `10.0.20.102`, and its `kcadm`
  update returns 401 (KC 26 bootstrap-admin quirk + master-realm Frontend URL). It's a
  management UI, separate from the demo flow — for the Keycloak owner (Guts1313).
- **Internal-hop TLS** — nginx→backend and backend→Keycloak run HTTP (firewall ports 8080/
  8081, not the designed TLS 8443/443). The external hop is TLS. Hardening item.
- **`.env`** Keycloak admin credentials should be rotated if still placeholders.
- Replace the self-signed edge cert with a real one (internal CA / ACME).
- Merge `feat/d1-d3-dmz-nginx-edge` after review.
