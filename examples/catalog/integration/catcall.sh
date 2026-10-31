#!/bin/sh
# Usage on logali-prod: catcall.sh <host> <METHOD> <path> [json-body] [mode]
# Runs curl from a throwaway container on the isolated test network; the token stays on the server.
D=/tmp/c415-catalog-test
HOST=$1; METHOD=$2; P=$3; BODY=$4; MODE=${5:-read-only}
TOKEN=$(sudo -n grep '^RFC_GUARD_API_TOKEN=' $D/override.env | cut -d= -f2)
if [ "$METHOD" = "POST" ]; then
  printf '%s' "$BODY" | sudo -n docker run --rm -i --network c415-catalog-test-net -v $D/tls/server.crt:/ca.crt:ro \
    curlimages/curl:8.11.1 -sS --cacert /ca.crt -w '\nHTTP %{http_code}\n' -X POST \
    -H "Authorization: Bearer $TOKEN" -H "X-RFC-Guard-Mode: $MODE" -H 'Content-Type: application/json' \
    -H "X-Correlation-Id: c415-cat-$(date +%s)" --data-binary @- "https://$HOST:8080$P"
else
  sudo -n docker run --rm --network c415-catalog-test-net -v $D/tls/server.crt:/ca.crt:ro \
    curlimages/curl:8.11.1 -sS --cacert /ca.crt -w '\nHTTP %{http_code}\n' \
    -H "Authorization: Bearer $TOKEN" -H "X-Correlation-Id: c415-cat-$(date +%s)" "https://$HOST:8080$P"
fi
