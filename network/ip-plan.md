# IP Plan — N3 (issue #11)

Scope: addressing scheme for the IAM-with-Keycloak proof-of-concept. Pairs with [`inter-vlan-acl.md`](./inter-vlan-acl.md), which encodes the firewall rules that ride on top of this plan.

Status: draft, versioned in repo. The firewall config on pfSense MUST match this document; any drift is a defect in one or the other.

---

## 1. Range choice and justification

Private supernet: **`10.0.0.0/16`**, sliced into `/24` per VLAN.

Why:

- **RFC1918, plenty of headroom.** 256 possible VLANs of 254 hosts each. We use 7; the rest are reserved for growth without re-IP.
- **`/24` per VLAN is readable.** Third octet = VLAN, easy to eyeball in firewall logs and Grafana dashboards.
- **Avoids common collisions.** `192.168.0.0/24` and `192.168.1.0/24` collide with home routers / VPN clients; `172.16.0.0/12` collides with Docker's default bridge. `10.x` keeps the lab clean for VPN-in from team members.
- **NetLab WAN** is whatever NetLab assigns on the pfSense WAN interface (DHCP or static handed out by the lab). It is not part of `10.0.0.0/16` and is documented as `TBD-NETLAB` below — this is the one unavoidable placeholder.
- **`10.99.100.0/24` reserved as a non-VLAN overlay.** The WireGuard tunnel uses a bumped second-octet (`10.99.x.x` rather than `10.0.x.x`) because NetLab's Cisco AnyConnect VPN already routes `10.0.100.0/24` over the campus VPN. Using the same `/24` for our WG would cause routing ambiguity on every team member's laptop. The chosen range is also deliberately outside the VLAN-ID-encoded `/24`s in the main table. See the "VPN tunnel (overlay)" subsection below.

Mapping convention inside each `/24`:

| Range            | Use                                                     |
| ---------------- | ------------------------------------------------------- |
| `.1`             | Gateway (pfSense interface or internal FW interface)    |
| `.2 – .9`        | Reserved infra (HA / future router-on-a-stick partners) |
| `.10 – .49`      | Static reservations (servers, appliances)               |
| `.50 – .199`     | DHCP pool                                               |
| `.200 – .250`    | Static reservations for clients (printers, jump hosts)  |
| `.251 – .254`    | Reserved (broadcast-adjacent, future VRRP/CARP)         |

---

## 2. VLAN list

| VLAN ID | Name           | Purpose                                                                | CIDR             | Gateway      | DHCP range                | Static range                |
| ------- | -------------- | ---------------------------------------------------------------------- | ---------------- | ------------ | ------------------------- | --------------------------- |
| —       | WAN            | pfSense WAN side, NetLab uplink                                        | `TBD-NETLAB`     | `TBD-NETLAB` | n/a (lab-provided)        | pfSense WAN interface only  |
| 10      | DMZ            | Public ingress: NGINX reverse proxy + ModSecurity/OWASP CRS WAF        | `10.0.10.0/24`   | `10.0.10.1`  | none (all static)         | `10.0.10.10 – 10.0.10.49`   |
| 20      | IdP            | Keycloak (+ its Postgres). **Not reachable from internet or User VLANs.** | `10.0.20.0/24`   | `10.0.20.1`  | none (all static)         | `10.0.20.10 – 10.0.20.49`   |
| 30      | Services       | Internal APIs, HR system, app DBs, Prometheus, Loki, Grafana           | `10.0.30.0/24`   | `10.0.30.1`  | none (all static)         | `10.0.30.10 – 10.0.30.49`   |
| 40      | User-Admin     | Admin role only — hardware FIDO2 MFA, admin consoles                   | `10.0.40.0/24`   | `10.0.40.1`  | `10.0.40.50 – 10.0.40.199` | `10.0.40.10 – 10.0.40.49`   |
| 50      | User-General   | IT Manager, HR Manager, Developer, Normal Worker (shared)              | `10.0.50.0/24`   | `10.0.50.1`  | `10.0.50.50 – 10.0.50.199` | `10.0.50.10 – 10.0.50.49`   |
| 99      | Management     | pfSense LAN-side mgmt, switch mgmt, OOB                                | `10.0.99.0/24`   | `10.0.99.1`  | none (all static)         | `10.0.99.10 – 10.0.99.49`   |

### Why two User VLANs and not five

The plan calls out hardware MFA for Admin specifically. Putting **Admin on its own VLAN** lets us:

- gate all admin-console access (Keycloak admin, Grafana admin, pfSense web admin) on a source-IP allowlist that is small and stable,
- enforce a stricter ACL out of `User-Admin` (e.g. no general internet browsing if we want it),
- detect anomalies (an Admin account logging in from `User-General` is a red flag).

The other four roles (IT Manager, HR Manager, Developer, Normal Worker) are differentiated by **Keycloak realm role**, not by network location. Authorization is enforced at the application layer (W3/W4) via JWT claims, which is the correct place for it — moving each role to its own VLAN would multiply ACL rules without adding security, because the trust boundary is the token, not the IP.

Net: 2 User VLANs (network separation where it buys something), 5 realm roles (application separation where it actually enforces authorization).

---

## VPN tunnel (overlay)

The WireGuard tunnel is not a VLAN — it's an overlay subnet used for remote employees and admins VPN'ing in. Peers route to other VLANs via the rules in [`inter-vlan-acl.md`](./inter-vlan-acl.md); the tunnel itself does not appear in the VLAN table above because it has no VLAN ID and no L2 segment in NetLab.

| Item                            | Value                                                                 |
| ------------------------------- | --------------------------------------------------------------------- |
| CIDR                            | `10.99.100.0/24`                                                       |
| Server (pfSense WG interface)   | `10.99.100.1`                                                          |
| Reserved client range           | `10.99.100.2 – 10.99.100.49` (each WG peer gets a static /32)            |
| DHCP                            | none — every peer is statically allocated in pfSense per-peer config  |
| Listen port (server)            | UDP `51820`                                                           |

See [`../docs/n1-perimeter-firewall.md`](../docs/n1-perimeter-firewall.md) for the per-peer config template and operational runbooks, and [`inter-vlan-acl.md`](./inter-vlan-acl.md) for the VPN→VLAN allow/deny matrix.

**Why `10.99.100.0/24` (bumped second octet) and not `10.0.100.0/24`:** NetLab's Cisco AnyConnect campus VPN already routes `10.0.100.0/24` over its tunnel — using the same range for our WG would cause routing ambiguity on every team member's laptop (Windows prefers the more-specific route, breaking handshake/keepalive paths). Bumping the second octet to `99` avoids the collision while staying inside the RFC1918 `10/8` block. The third octet `.100` is still outside the VLAN-ID-encoded range (10, 20, 30, 40, 50, 99) so it's obvious at a glance in firewall logs that traffic from `10.99.100.x` came in over WireGuard, not over a switched VLAN.

---

## 3. Static reservations

All servers and appliances get static IPs. DHCP is only used for end-user workstations on User-Admin and User-General.

### DMZ (VLAN 10)

| IP            | Hostname            | Role                                                                                              |
| ------------- | ------------------- | ------------------------------------------------------------------------------------------------- |
| `10.0.10.1`   | `fw-dmz`            | Internal firewall / L3 interface for DMZ (pfSense or dedicated)                                   |
| `10.0.10.10`  | `nginx-edge`        | NGINX reverse proxy + ModSecurity (the **only** public ingress; reachable from WAN on 443/tcp)    |
| `10.0.10.11`  | `nginx-edge-b`      | Reserved — second NGINX node if we add HA later                                                   |

### IdP (VLAN 20)

| IP            | Hostname            | Role                                                                                              |
| ------------- | ------------------- | ------------------------------------------------------------------------------------------------- |
| `10.0.20.1`   | `fw-idp`            | Internal firewall interface for IdP VLAN                                                          |
| `10.0.20.10`  | `keycloak`          | Keycloak server (HTTP `8080`, management `9000`)                                                  |
| `10.0.20.20`  | `kc-postgres`       | Postgres backing Keycloak (port `5432`, reachable **only** from `keycloak`)                       |

### Services (VLAN 30)

| IP            | Hostname            | Role                                                                                              |
| ------------- | ------------------- | ------------------------------------------------------------------------------------------------- |
| `10.0.30.1`   | `fw-svc`            | Internal firewall interface for Services VLAN                                                     |
| `10.0.30.10`  | `app-backend`       | Demo app backend / internal APIs (the W-block service)                                            |
| `10.0.30.11`  | `hr-system`         | HR system (placeholder service consumed by HR-Manager role)                                       |
| `10.0.30.20`  | `app-postgres`      | Application Postgres (separate instance from Keycloak's DB)                                       |
| `10.0.30.30`  | `loki`              | Loki log aggregator (M2)                                                                          |
| `10.0.30.31`  | `grafana`           | Grafana (admin UI reachable only from User-Admin; data path from Loki/Prometheus internal)        |
| `10.0.30.32`  | `prometheus`        | Prometheus (scrapes Keycloak `9000`, NGINX, pfSense exporters)                                    |

### User VLANs

User-Admin (VLAN 40) and User-General (VLAN 50) use DHCP for workstations; static reservations `.10–.49` are for any role-shared service (e.g. an Admin jump host at `10.0.40.10`).

### Management (VLAN 99)

| IP            | Hostname            | Role                                                                                              |
| ------------- | ------------------- | ------------------------------------------------------------------------------------------------- |
| `10.0.99.1`   | `pfsense-lan`       | pfSense LAN-side management interface                                                             |
| `10.0.99.10`  | `switch-core`       | Core switch management                                                                            |

---

## 4. Static vs DHCP — policy

- **Static**: anything in DMZ, IdP, Services, Management, plus pfSense itself. Anything we point a firewall rule, a DNS record, a Prometheus scrape, or a Loki target at.
- **DHCP**: end-user workstations in User-Admin and User-General only. DHCP leases are logged centrally so a user→IP mapping is reconstructable.
- **No DHCP in DMZ / IdP / Services / Management.** A new host in those VLANs is a deliberate, ticketed change to this document.

---

## 5. Open items pending NetLab

- WAN CIDR, gateway, DNS — fill in once NetLab assignments land. Track in N1.
- Whether NetLab gives us a routable v4 or NATs us — affects whether `nginx-edge` needs a 1:1 NAT on pfSense or is reachable directly.

Everything else in this document is final and ready to drive pfSense config.
