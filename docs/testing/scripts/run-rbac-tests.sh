#!/usr/bin/env bash
# M4 #30 — RBAC + JWT smoke suite. Curls every protected endpoint as every
# demo user, asserts the result against the W5 #27 access matrix.
#
# Requires test-prep.sh to have run first (DAG enabled, MFA cleared on demo users).
# Returns the number of failed assertions as the exit code.
set -uo pipefail

REALM_BASE="${REALM_BASE:-http://localhost:8080/realms/cybergroup}"
BACKEND="${BACKEND:-http://localhost:8081}"

declare -a USERS=(
    "admin-demo:AdminDemo123!Init"
    "itmgr-demo:ITMgrDemo123!Init"
    "hrmgr-demo:HRMgrDemo123!Init"
    "dev-demo:DevDemo123!Init"
    "normal-demo:NormalDemo123!Init"
)
ENDPOINTS=("/api/private" "/api/private/admin" "/api/private/it" "/api/private/hr" "/api/private/dev")

# Mirror of W5 access matrix.
declare -A EXPECTED=(
    [admin-demo,/api/private]=200       [admin-demo,/api/private/admin]=200 [admin-demo,/api/private/it]=200 [admin-demo,/api/private/hr]=200 [admin-demo,/api/private/dev]=200
    [itmgr-demo,/api/private]=200       [itmgr-demo,/api/private/admin]=403 [itmgr-demo,/api/private/it]=200 [itmgr-demo,/api/private/hr]=403 [itmgr-demo,/api/private/dev]=200
    [hrmgr-demo,/api/private]=200       [hrmgr-demo,/api/private/admin]=403 [hrmgr-demo,/api/private/it]=403 [hrmgr-demo,/api/private/hr]=200 [hrmgr-demo,/api/private/dev]=403
    [dev-demo,/api/private]=200         [dev-demo,/api/private/admin]=403   [dev-demo,/api/private/it]=403   [dev-demo,/api/private/hr]=403   [dev-demo,/api/private/dev]=200
    [normal-demo,/api/private]=200      [normal-demo,/api/private/admin]=403 [normal-demo,/api/private/it]=403 [normal-demo,/api/private/hr]=403 [normal-demo,/api/private/dev]=403
)

pass=0
fail=0

assert() {
    local label="$1" expected="$2" actual="$3"
    if [[ "$expected" == "$actual" ]]; then
        echo "  PASS  $label  (expected=$expected, actual=$actual)"
        pass=$((pass + 1))
    else
        echo "  FAIL  $label  (expected=$expected, actual=$actual)"
        fail=$((fail + 1))
    fi
}

echo "=========================================="
echo "TEST 1: public endpoint reachable anonymously"
echo "=========================================="
status=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND/api/public")
assert "GET /api/public (no auth)" 200 "$status"

echo
echo "=========================================="
echo "TEST 2: protected endpoint rejects no-token"
echo "=========================================="
for ep in "${ENDPOINTS[@]}"; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND$ep")
    assert "GET $ep (no token)" 401 "$status"
done

echo
echo "=========================================="
echo "TEST 3: protected endpoint rejects bad token"
echo "=========================================="
status=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer not.a.real.jwt" "$BACKEND/api/private")
assert "GET /api/private (garbage token)" 401 "$status"

echo
echo "=========================================="
echo "TEST 4: per-role access matrix"
echo "=========================================="
for entry in "${USERS[@]}"; do
    user="${entry%%:*}"
    pw="${entry#*:}"
    echo
    echo "--- $user ---"

    token=$(curl -fsS -X POST "$REALM_BASE/protocol/openid-connect/token" \
        -d grant_type=password \
        -d client_id=iam-frontend \
        -d username="$user" \
        -d password="$pw" \
        | python -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

    if [[ -z "${token:-}" ]]; then
        echo "  TOKEN ERROR — skipping rest of this user"
        fail=$((fail + 1))
        continue
    fi

    # Decode roles from the JWT payload for at-a-glance verification.
    payload=$(echo "$token" | cut -d. -f2)
    pad=$(awk -v n="${#payload}" 'BEGIN{m=n%4; if(m==2)print"==";else if(m==3)print"=";else if(m==1)print"==="; else print ""}')
    roles=$(printf '%s%s' "$payload" "$pad" | base64 -d 2>/dev/null \
        | python -c "import sys,json; d=json.load(sys.stdin); print(','.join(d.get('realm_access',{}).get('roles',[])))")
    echo "  realm_access.roles = [$roles]"

    for ep in "${ENDPOINTS[@]}"; do
        actual=$(curl -s -o /dev/null -w "%{http_code}" \
            -H "Authorization: Bearer $token" "$BACKEND$ep")
        expected="${EXPECTED[$user,$ep]}"
        assert "GET $ep" "$expected" "$actual"
    done
done

echo
echo "=========================================="
echo "RESULTS: pass=$pass  fail=$fail"
echo "=========================================="
exit $fail
