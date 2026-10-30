#!/usr/bin/env bash
# End-to-end smoke test against a running instance:  ./smoke-test.sh [base-url]
# Requires only bash + curl. Uses default timeouts (a hanging probe takes ~12s to end as TIMED_OUT).
BASE="${1:-http://localhost:8080}"
PASS=0; FAIL=0
BODY=""; CODE=""
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# req METHOD PATH [json-body] [header]
req() {
  local method=$1 path=$2 data=${3:-} header=${4:-}
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' -d "$data")
  [ -n "$header" ] && args+=(-H "$header")
  CODE=$(curl "${args[@]}"); BODY=$(cat "$TMP/body")
}
check() { # description expected-code
  if [ "$CODE" = "$2" ]; then PASS=$((PASS+1)); echo "  ok    $1 -> $CODE"
  else FAIL=$((FAIL+1)); echo "  FAIL  $1 -> expected $2, got $CODE: $BODY"; fi
}
contains() { # description needle
  if [[ "$BODY" == *"$2"* ]]; then PASS=$((PASS+1)); echo "  ok    $1"
  else FAIL=$((FAIL+1)); echo "  FAIL  $1 (missing $2) in: $BODY"; fi
}
device_id() { echo "$BODY" | sed -n 's/^{"id":\([0-9]*\).*/\1/p'; }
op_id()     { echo "$BODY" | sed -n 's/.*"id":"\([0-9a-f-]*\)".*/\1/p' | head -1; }
rnd()       { echo $((RANDOM % 250 + 1)); }

# wait_final OPERATION_ID  -> sets BODY to the terminal operation
wait_final() {
  for _ in $(seq 1 60); do
    req GET "/api/v1/pings/$1"
    [[ "$BODY" == *'"status":"PENDING"'* || "$BODY" == *'"status":"RUNNING"'* ]] || return 0
    sleep 1
  done
}

if ! curl -s -o /dev/null --max-time 5 "$BASE/api/v1/devices?size=1"; then
  echo "Server at $BASE is not reachable. Start it first (docker compose up -d) or pass the correct base URL."
  exit 2
fi

R=$(rnd)
echo "== Devices"
req POST /api/v1/devices "{\"name\":\"smoke-modem\",\"ipAddress\":\"10.$R.$(rnd).$(rnd)\",\"type\":\"MODEM\"}"
check "create device" 201; DEV=$(device_id); IP=$(echo "$BODY" | sed -n 's/.*"ipAddress":"\([^"]*\)".*/\1/p')
req POST /api/v1/devices "{\"name\":\"dup\",\"ipAddress\":\"$IP\",\"type\":\"RADIO\"}"; check "duplicate IP rejected" 409
req POST /api/v1/devices '{"name":"","ipAddress":"999.1.1.1","type":"MODEM"}'; check "invalid body rejected" 400
contains "validation details present" '"details"'
req POST /api/v1/devices '{"name":"x","ipAddress":"10.1.1.1","type":"TOASTER"}'; check "unknown type rejected" 400
req GET "/api/v1/devices/$DEV"; check "get device" 200; contains "status UNKNOWN initially" '"lastStatus":"UNKNOWN"'
req GET "/api/v1/devices?page=0&size=10"; check "list devices" 200
req GET "/api/v1/devices?size=0"; check "invalid page size" 400
req PUT "/api/v1/devices/$DEV" "{\"name\":\"renamed\",\"ipAddress\":\"$IP\",\"type\":\"RADIO\"}"; check "update device" 200
req GET /api/v1/devices/999999; check "unknown device" 404

echo "== Async ping + idempotency"
KEY="smoke-$RANDOM$RANDOM"
req POST "/api/v1/devices/$DEV/pings" "" "Idempotency-Key: $KEY"; check "ping accepted (async)" 202; OP=$(op_id)
req POST "/api/v1/devices/$DEV/pings" "" "Idempotency-Key: $KEY"; check "same key replayed" 200
[ "$(op_id)" = "$OP" ] && { PASS=$((PASS+1)); echo "  ok    replay returns the same operation"; } || { FAIL=$((FAIL+1)); echo "  FAIL  replay returned a different operation"; }
req POST "/api/v1/devices/$DEV/pings" "" "Idempotency-Key: bad key!"; check "invalid idempotency key" 400
wait_final "$OP"; check "poll operation" 200
case "$BODY" in *'"status":"SUCCEEDED"'*|*'"status":"FAILED"'*) PASS=$((PASS+1)); echo "  ok    operation reached a final state";;
  *) FAIL=$((FAIL+1)); echo "  FAIL  not final: $BODY";; esac
req GET "/api/v1/devices/$DEV/pings?limit=10"; check "ping history" 200
req GET "/api/v1/devices/$DEV/monitor?window=50"; check "monitor summary" 200; contains "summary has totals" '"totalOperations"'
req GET /api/v1/pings/7c9e6679-7425-40de-944b-e07fc1f90ae7; check "unknown operation" 404
req GET /api/v1/pings/not-a-uuid; check "malformed operation id" 400
req POST /api/v1/devices/999999/pings; check "ping unknown device" 404
req GET /api/v1/devices/999999/monitor; check "monitor unknown device" 404

echo "== Monitoring switch"
req PUT "/api/v1/devices/$DEV/monitoring" '{"enabled":false}'; check "disable monitoring" 200
req POST "/api/v1/devices/$DEV/pings"; check "ping refused while disabled" 422
req PUT "/api/v1/devices/$DEV/monitoring" '{"enabled":true}'; check "enable monitoring" 200

echo "== Deterministic mock behaviours"
req POST /api/v1/devices "{\"name\":\"down\",\"ipAddress\":\"192.0.2.$(rnd)\",\"type\":\"MODEM\"}"; check "create DOWN device" 201; D1=$(device_id)
req POST "/api/v1/devices/$D1/pings"; check "ping DOWN device" 202; wait_final "$(op_id)"
contains "unreachable but operation succeeded" '"status":"SUCCEEDED"'; contains "reachable=false" '"reachable":false'
req GET "/api/v1/devices/$D1"; contains "device marked DOWN" '"lastStatus":"DOWN"'

req POST /api/v1/devices "{\"name\":\"error\",\"ipAddress\":\"203.0.113.$(rnd)\",\"type\":\"MODEM\"}"; D2=$(device_id)
req POST "/api/v1/devices/$D2/pings"; check "ping failing probe" 202; wait_final "$(op_id)"
contains "retried then FAILED" '"status":"FAILED"'; contains "all attempts used" '"attempts":3'

echo "== Timeout + concurrency (takes ~12s)"
req POST /api/v1/devices "{\"name\":\"hang\",\"ipAddress\":\"198.51.100.$(rnd)\",\"type\":\"RADIO\"}"; D3=$(device_id)
req POST "/api/v1/devices/$D3/pings"; check "ping hanging probe" 202; HOP=$(op_id)
req POST "/api/v1/devices/$D3/pings"; check "concurrent ping rejected" 409; contains "conflict code" 'ACTIVE_OPERATION_EXISTS'
req DELETE "/api/v1/devices/$D3"; check "delete refused while active" 409
wait_final "$HOP"; contains "ends TIMED_OUT" '"status":"TIMED_OUT"'
req GET "/api/v1/devices/$D3"; contains "device status UNKNOWN" '"lastStatus":"UNKNOWN"'

echo "== 8 parallel requests, same device"
for i in $(seq 1 8); do
  ( curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/v1/devices/$D3/pings" > "$TMP/par$i" ) &
done; wait
ACCEPTED=$(cat "$TMP"/par* | grep -c '^202$'); CONFLICTS=$(cat "$TMP"/par* | grep -c '^409$')
[ "$ACCEPTED" = 1 ] && [ "$CONFLICTS" = 7 ] && { PASS=$((PASS+1)); echo "  ok    exactly 1 accepted, 7 conflicts"; } \
  || { FAIL=$((FAIL+1)); echo "  FAIL  accepted=$ACCEPTED conflicts=$CONFLICTS"; }

echo "== Cleanup"
req DELETE "/api/v1/devices/$DEV"; check "delete device" 204
req DELETE "/api/v1/devices/$D1"; check "delete DOWN device" 204
req DELETE "/api/v1/devices/$D2"; check "delete error device" 204

echo; echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" = 0 ]
