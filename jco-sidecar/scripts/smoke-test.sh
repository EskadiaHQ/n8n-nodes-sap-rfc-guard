#!/usr/bin/env bash
set -euo pipefail

SECRET_FILE=${RFC_GUARD_SECRET_FILE:-/srv/sap-rfc-guard/secrets/sap-rfc-guard.env}
BASE_URL=${RFC_GUARD_BASE_URL:-https://sap-rfc-guard-jco:8080}
CA_FILE=${RFC_GUARD_CA_FILE:-/srv/sap-rfc-guard/tls/ca.crt}

token=$(sed -n 's/^RFC_GUARD_API_TOKEN=//p' "$SECRET_FILE")
[[ -n "$token" ]] || { echo "API token is unavailable." >&2; exit 1; }

if [[ -n "${RFC_GUARD_EXEC_CONTAINER:-}" ]]; then
  docker exec -i \
    -e RFC_GUARD_API_TOKEN="$token" \
    -e RFC_GUARD_BASE_URL="$BASE_URL" \
    "$RFC_GUARD_EXEC_CONTAINER" node <<'NODE'
const token = process.env.RFC_GUARD_API_TOKEN;
const baseUrl = process.env.RFC_GUARD_BASE_URL;
const headers = {
  Authorization: `Bearer ${token}`,
  'Content-Type': 'application/json',
  'X-Correlation-ID': 'server-smoke-test',
  'X-RFC-Guard-Mode': 'read-only',
};

(async () => {
  const healthResponse = await fetch(`${baseUrl}/v1/health`, { headers });
  const health = await healthResponse.json();
  if (!healthResponse.ok || health.status !== 'ok' || health.capabilities?.readOnly !== true) {
    throw new Error('RFC Guard health contract failed.');
  }

  const operationResponse = await fetch(`${baseUrl}/v1/operations/listSu01Users/execute`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      operation: 'listSu01Users',
      parameters: { maxRows: 3 },
      context: { client: 'n8n-sap-rfc-guard', readOnly: true },
    }),
  });
  const result = await operationResponse.json();
  if (
    !operationResponse.ok ||
    result.meta?.readOnly !== true ||
    result.meta?.syntheticData !== false ||
    result.meta?.source !== 'sap-jco' ||
    !Array.isArray(result.data) ||
    result.data.length > 3
  ) {
    throw new Error('Governed RFC read contract failed.');
  }
  console.log('Health and governed SU01 read passed.');
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
NODE
  exit 0
fi

curl --fail --silent --show-error --cacert "$CA_FILE" \
  -H "Authorization: Bearer $token" \
  -H 'X-RFC-Guard-Mode: read-only' \
  "$BASE_URL/v1/health" | jq -e \
  '.status == "ok" and .capabilities.readOnly == true and (.backend.client | length) > 0' >/dev/null

curl --fail --silent --show-error --cacert "$CA_FILE" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: server-smoke-test' \
  -H 'X-RFC-Guard-Mode: read-only' \
  --data '{"operation":"listSu01Users","parameters":{"maxRows":3},"context":{"client":"n8n-sap-rfc-guard","readOnly":true}}' \
  "$BASE_URL/v1/operations/listSu01Users/execute" | jq -e \
  '.meta.readOnly == true and .meta.syntheticData == false and .meta.source == "sap-jco" and (.data | length) <= 3' >/dev/null

echo "Health and governed SU01 read passed."
