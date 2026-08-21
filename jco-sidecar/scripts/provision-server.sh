#!/usr/bin/env bash
set -euo pipefail

STACK_DIR=/srv/sap-rfc-guard/stack
SECRET_FILE=/srv/sap-rfc-guard/secrets/sap-rfc-guard.env
TLS_DIR=/srv/sap-rfc-guard/tls
VENDOR_DIR=/srv/sap-rfc-guard/vendor/sap-jco

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

install -d -m 0750 "$STACK_DIR" "$VENDOR_DIR"
install -d -m 0700 "$TLS_DIR"

if [[ ! -f "$TLS_DIR/ca.crt" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$TLS_DIR/ca.key"
  openssl req -x509 -new -sha256 -days 1825 -key "$TLS_DIR/ca.key" \
    -subj "/CN=Logali SAP RFC Guard Internal CA" -out "$TLS_DIR/ca.crt"
fi

if [[ ! -f "$TLS_DIR/server.p12" ]]; then
  tls_password=$(openssl rand -base64 36 | tr -d '\n')
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$TLS_DIR/server.key"
  openssl req -new -key "$TLS_DIR/server.key" -subj "/CN=sap-rfc-guard-jco" -out "$TLS_DIR/server.csr"
  extension_file=$(mktemp)
  trap 'rm -f "$extension_file"' EXIT
  printf '%s\n' \
    'basicConstraints=CA:FALSE' \
    'keyUsage=digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectAltName=DNS:sap-rfc-guard-jco' \
    > "$extension_file"
  openssl x509 -req -sha256 -days 825 -in "$TLS_DIR/server.csr" \
    -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" -CAcreateserial \
    -extfile "$extension_file" -out "$TLS_DIR/server.crt"
  openssl pkcs12 -export -name sap-rfc-guard-jco -inkey "$TLS_DIR/server.key" \
    -in "$TLS_DIR/server.crt" -certfile "$TLS_DIR/ca.crt" \
    -passout "pass:$tls_password" -out "$TLS_DIR/server.p12"
else
  tls_password=""
fi

chmod 0600 "$TLS_DIR/ca.key" "$TLS_DIR/server.key" 2>/dev/null || true
chmod 0644 "$TLS_DIR/ca.crt" "$TLS_DIR/server.crt" 2>/dev/null || true
chown 10001:10001 "$TLS_DIR/server.p12"
chmod 0400 "$TLS_DIR/server.p12"

if [[ ! -f "$SECRET_FILE" ]]; then
  api_token=$(openssl rand -base64 48 | tr -d '\n')
  if [[ -z "$tls_password" ]]; then
    echo "Cannot recover the existing PKCS#12 password; create the secret file manually." >&2
    exit 1
  fi
  umask 077
  {
    printf 'RFC_GUARD_API_TOKEN=%s\n' "$api_token"
    printf 'RFC_GUARD_MAX_ROWS=50\n'
    printf 'RFC_GUARD_INACTIVE_DAYS=90\n'
    printf 'RFC_GUARD_REQUEST_TIMEOUT_SECONDS=30\n'
    printf 'RFC_GUARD_TLS_KEYSTORE=/run/secrets/sap-rfc-guard-server.p12\n'
    printf 'RFC_GUARD_TLS_KEYSTORE_PASSWORD=%s\n' "$tls_password"
    printf 'SAP_ASHOST=sap.example.internal\n'
    printf 'SAP_SYSNR=00\n'
    printf 'SAP_CLIENT=100\n'
    printf 'SAP_USER=REPLACE_WITH_TECHNICAL_USER\n'
    printf 'SAP_PASSWORD=REPLACE_WITH_TECHNICAL_PASSWORD\n'
    printf 'SAP_LANG=EN\n'
    printf 'SAP_POOL_CAPACITY=3\n'
    printf 'SAP_PEAK_LIMIT=10\n'
  } > "$SECRET_FILE"
  chmod 0600 "$SECRET_FILE"
fi

if [[ -f "$STACK_DIR/compose.example.yml" && ! -f "$STACK_DIR/compose.yml" ]]; then
  install -m 0640 "$STACK_DIR/compose.example.yml" "$STACK_DIR/compose.yml"
fi

echo "Provisioning complete. No container was started."
echo "CA certificate: $TLS_DIR/ca.crt"
echo "Pending: SAP JCo files and a dedicated SAP technical credential."
