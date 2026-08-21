#!/usr/bin/env bash
set -euo pipefail

STACK_DIR=/srv/sap-rfc-guard/stack
SECRET_FILE=/srv/sap-rfc-guard/secrets/sap-rfc-guard.env
VENDOR_DIR=/srv/sap-rfc-guard/vendor/sap-jco

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

for file in "$VENDOR_DIR/sapjco3.jar" "$VENDOR_DIR/libsapjco3.so" "$SECRET_FILE" "$STACK_DIR/compose.yml"; do
  [[ -s "$file" ]] || { echo "Missing required file: $file" >&2; exit 1; }
done

if grep -q 'REPLACE_WITH_' "$SECRET_FILE"; then
  echo "The SAP technical credential is not configured." >&2
  exit 1
fi

file "$VENDOR_DIR/libsapjco3.so" | grep -q 'ELF 64-bit.*x86-64' || {
  echo "libsapjco3.so is not the Linux x86-64 build." >&2
  exit 1
}
jar tf "$VENDOR_DIR/sapjco3.jar" | grep -q 'com/sap/conn/jco/JCoDestination.class' || {
  echo "sapjco3.jar does not contain SAP JCo 3 classes." >&2
  exit 1
}

chmod 0640 "$VENDOR_DIR/sapjco3.jar" "$VENDOR_DIR/libsapjco3.so"
chown 10001:10001 "$VENDOR_DIR/sapjco3.jar" "$VENDOR_DIR/libsapjco3.so"
docker compose -f "$STACK_DIR/compose.yml" config --quiet
docker compose -f "$STACK_DIR/compose.yml" up -d

for attempt in $(seq 1 30); do
  status=$(docker inspect sap-rfc-guard-jco --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)
  [[ "$status" == healthy ]] && { echo "SAP RFC Guard JCo is healthy."; exit 0; }
  [[ "$status" == unhealthy ]] && break
  sleep 2
done

docker logs --tail 50 sap-rfc-guard-jco >&2 || true
exit 1
