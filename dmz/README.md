# DMZ edge — NGINX reverse proxy (D1 #9 + D3 #24)

Single public entry point for the IAM stack: terminates TLS and reverse-proxies
to Keycloak and the backend. D3 hardening (TLS policy, security headers, rate
limits, body caps, admin allowlist) is baked into [`nginx.conf`](nginx.conf).

## Run locally (in front of the root compose stack)

```powershell
# 1. one-time: generate a self-signed cert (no openssl install needed)
dmz\certs\gen-cert.ps1

# 2. start the edge in front of the stack (also applies Keycloak's proxy-headers)
docker compose -f compose.yml -f dmz/compose.dmz.yml up -d
```

This starts `nginx-edge` and recreates `keycloak` once to pick up
`KC_PROXY_HEADERS` (so its account/admin UIs work *through* the proxy). The realm
data lives in Postgres and is preserved; backend / postgres / console are untouched.

## Verify (D1 + D3 acceptance proof)

```powershell
# proxy + TLS -> Keycloak reachable through nginx
curl.exe -k https://localhost/realms/cybergroup/.well-known/openid-configuration
# proxy -> backend through nginx
curl.exe -k https://localhost/api/public
# D3 security headers present
curl.exe -k -I https://localhost/realms/cybergroup/.well-known/openid-configuration
# :80 -> :443 redirect
curl.exe -sI http://localhost/
```

`200`s + a `Strict-Transport-Security` header = D1 (TLS reverse proxy) and D3
(hardening) working.

> **Browser note:** open the **working** login at <http://localhost:5173>
> (direct). Browsing <https://localhost> serves the same console *through* nginx,
> but the SPA's API calls still target `localhost:8080/8081` directly (hardcoded
> in `demo-console/index.html`), so a full browser login *through* nginx needs
> the host / redirect-URI integration step. The `curl` checks above are the D1
> acceptance proof.

## Mapping to the NetLab DMZ box (`10.0.10.10`)

Change only the three upstream lines in `nginx.conf`:

| Local (compose service) | NetLab (real target) |
|---|---|
| `set $kc  keycloak:8080;` | `set $kc  10.0.20.10:8080;` (firewall rule **#6**) — or `:8443` for end-to-end TLS (rule **#5**) |
| `set $api backend:8081;`  | `set $api 10.0.30.10:8081;` (wherever the backend lands) |
| `set $spa console:80;`    | serve the SPA from disk (mount `demo-console/`) instead of proxying |

Then on pfSense:
- **Firewall → Rules → DMZ:** enable rule `[#6]` (8080) / disable `[#5]`, or keep
  `[#5]` if Keycloak runs TLS on 8443.
- **Firewall → NAT → Port Forward:** enable `WAN:443 → 10.0.10.10:443` **and** its
  linked WAN rule.
- Uncomment the **admin allowlist** in the `/admin/` block.
- Replace the self-signed cert with a real one (internal CA / Let's Encrypt —
  egress rule `#22` permits ACME).
