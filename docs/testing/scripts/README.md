# Test scripts (M4 #30)

Headless RBAC + JWT smoke suite. Drives the running stack via curl and asserts every cell of the W5 access matrix.

## Files

| Script | What |
|---|---|
| `test-prep.sh` | Lowers realm security to make password-grant testing possible (enables DAG on `iam-frontend`, clears `CONFIGURE_TOTP`). One-off per fresh stack. |
| `run-rbac-tests.sh` | The actual test runner. 32 assertions: public access, no-token rejection, bad-token rejection, 5 roles × 5 endpoints. |
| `test-teardown.sh` | Restores realm to production-like config (re-disables DAG, re-adds `CONFIGURE_TOTP`). |

## Run order

```
# Once: stand up the stack
cd keycloak && docker compose up -d
# … then run backend (see /backend/README.md for options)

# Per test pass
KEYCLOAK_ADMIN_PASSWORD=... bash docs/testing/scripts/test-prep.sh
bash docs/testing/scripts/run-rbac-tests.sh
KEYCLOAK_ADMIN_PASSWORD=... bash docs/testing/scripts/test-teardown.sh
```

`run-rbac-tests.sh` exits with the number of failed assertions, so it's CI-friendly.

## Why we lower security to test

The realm-import (K3 #15) requires every demo user to enrol TOTP on first login, and disables direct-access-grant on `iam-frontend`. Both are correct for production. For a headless test runner using curl, neither plays nicely — there's no browser to scan a QR code, and there's no Auth Code + PKCE flow built into the script.

`test-prep.sh` flips both temporarily. `test-teardown.sh` flips them back. The realm JSON in `keycloak/realm-import/` is unchanged, so a fresh `docker compose down -v && up -d` always returns to the production-like state.

## Caveats

- **Don't leave the system in the prepped state.** It bypasses MFA — useful for testing only.
- **The `iam-frontend` redirect URI for production isn't `http://localhost:5173/*`.** That's a dev-only allow-list in the realm import. Phase D moves to the IdP VLAN URL.
- **Token-internals coverage** (signature swap, expiry, refresh rotation) belongs in E4 #59 (Testcontainers backend integration tests), not here.

See `docs/testing/role-rbac-test-report.md` for the full M4 test plan and last execution result.
