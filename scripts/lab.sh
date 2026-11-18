#!/usr/bin/env bash
# Lab control plane helper. Requires only bash and curl.
#
#   scripts/lab.sh token alice                 print an access token
#   scripts/lab.sh scenario latency-jitter     start a scenario (latency-jitter | dependency-outage | slow-database)
#   scripts/lab.sh status                      show active faults
#   scripts/lab.sh reset                       remove all faults
#   scripts/lab.sh transfer alice 42.50        one transfer from alice's checking account to bob's
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
ALICE_CHECKING="0193a000-0000-7000-8000-00000000a001"
BOB_CHECKING="0193a000-0000-7000-8000-00000000b001"

token() {
  local user="$1"
  curl -fsS -X POST "${KEYCLOAK_URL}/realms/sentinel/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=sentinel-cli \
    -d "username=${user}" -d "password=${user}-lab-password" \
    | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}

as_operator() {
  local method="$1" path="$2"
  curl -fsS -X "${method}" "${API_URL}${path}" -H "Authorization: Bearer $(token ops)"
  echo
}

case "${1:-}" in
  token) token "${2:?username}" ;;
  scenario) as_operator POST "/api/lab/scenarios/${2:?scenario name}" ;;
  status) as_operator GET /api/lab/faults ;;
  reset) as_operator DELETE /api/lab/faults ;;
  transfer)
    user="${2:-alice}"; amount="${3:-10.00}"
    source="${ALICE_CHECKING}"; target="${BOB_CHECKING}"
    [[ "${user}" == "bob" ]] && { source="${BOB_CHECKING}"; target="${ALICE_CHECKING}"; }
    curl -sS -i -X POST "${API_URL}/api/transfers" \
      -H "Authorization: Bearer $(token "${user}")" \
      -H "Idempotency-Key: cli-$(date +%s)-${RANDOM}" \
      -H "Content-Type: application/json" \
      -d "{\"sourceAccountId\":\"${source}\",\"targetAccountId\":\"${target}\",\"amount\":${amount}}"
    echo ;;
  *)
    sed -n '2,9p' "$0"
    exit 1 ;;
esac
