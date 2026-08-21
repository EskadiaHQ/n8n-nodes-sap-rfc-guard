# Logali SAP RFC Guard

Security-first n8n community node for governed, read-only SAP RFC/BAPI operations.

> **Early access (`0.1.x`)**: the n8n governance contract and synthetic examples
> are testable now. Real SAP execution additionally requires the operated JCo
> sidecar, SAP's proprietary JCo runtime and a least-privilege SAP identity.

The node does **not** load native RFC libraries into n8n. It calls an operated HTTPS sidecar that
owns SAP JCo or SAP NetWeaver RFC SDK, the SAP technical identity, connection pooling, RFC type
conversion, and SAP-side authorization.

```text
n8n workflow -> Logali SAP RFC Guard -> HTTPS sidecar -> RFC/SNC -> SAP R/3, ECC, or S/4HANA
```

## Security model

- Read-only first release. The sidecar must attest `readOnly: true` in health and execution data.
- Deny by default. Credentials contain an exact business-operation allowlist.
- Technical names such as `BAPI_*`, `RFC_*`, `Z_*`, and `Y_*` are rejected in workflows.
- Every operation has a required response-field allowlist.
- Request bytes, response bytes, rows, and timeouts are bounded.
- AI Tool use requires a separate credential opt-in and keeps the same operation and field policies.
- SAP usernames and passwords never enter n8n credentials or workflow JSON.
- HTTPS is mandatory except for an explicitly enabled isolated local contract test.

## Sidecar contract

Health:

```http
GET /v1/health
Authorization: Bearer <token>
X-RFC-Guard-Mode: read-only
```

```json
{
  "status": "ok",
  "service": "sap-rfc-guard-jco",
  "version": "0.1.0",
  "backend": { "systemId": "S4D", "client": "100" },
  "capabilities": { "readOnly": true, "operations": ["listSu01Users"] }
}
```

Execute an approved business alias:

```http
POST /v1/operations/listSu01Users/execute
Authorization: Bearer <token>
X-Correlation-ID: <trace-id>
X-RFC-Guard-Mode: read-only
```

```json
{
  "operation": "listSu01Users",
  "parameters": { "client": "100", "inactiveDays": 90 },
  "context": { "client": "n8n-sap-rfc-guard", "readOnly": true }
}
```

Expected response:

```json
{
  "operation": "listSu01Users",
  "correlationId": "trace-id",
  "data": [
    {
      "username": "TRAINING_USER",
      "userType": "Dialog",
      "lastLogonAt": "2026-08-20",
      "accountStatus": "Active"
    }
  ],
  "meta": {
    "readOnly": true,
    "source": "sap-jco",
    "syntheticData": false,
    "operation": "listSu01Users"
  }
}
```

The source name is evidence returned by the sidecar, never an operation accepted from the
workflow.

## Install the early-access node

```bash
npm install n8n-nodes-sap-rfc-guard@next
```

Self-hosted n8n must still be configured to load community packages. Installing
the npm package does not install SAP JCo, create SAP credentials or start the
real sidecar.

## Credential configuration

For an SU01 reporting credential:

```text
Allowed Operations:
listSu01Users, getSu01UserDetail
```

```json
{
  "listSu01Users": [
    "username",
    "userType",
    "validFrom",
    "validTo",
    "lastLogonAt",
    "lockStatus",
    "accountStatus"
  ],
  "getSu01UserDetail": [
    "username",
    "userType",
    "fullName",
    "email",
    "validFrom",
    "validTo",
    "lastLogonAt",
    "lockStatus",
    "accountStatus"
  ]
}
```

## Operations

- **Connection / Test Connection** checks authentication and read-only capability.
- **Read Operation / Execute Approved Read** runs one credential-allowlisted business operation.

The node is intentionally separate from Logali HANA Guard. They share governance principles but
use different transports: HANA Guard speaks HANA SQL; SAP RFC Guard speaks HTTPS to an RFC
sidecar.

## Synthetic contract test

`contract-sidecar/` contains an isolated test double for validating the node before a real SAP
sidecar is available. It does **not** implement RFC and it returns only fictitious SU01-style
records. It exposes `listSu01Users`, marks responses with `source: contract-fixture` and
`readOnly: true`, and rejects every other operation.

The importable workflow is
`examples/rfc-guard-su01-contract-test.json`. It is deliberately distributed without a linked
credential. After import, select a dedicated fixture credential and keep the workflow unpublished.
Never reuse the fixture token or the insecure HTTP option for a real SAP connection.

`examples/su01/` adds five focused examples for a webinar or acceptance test: active dialog
users, technical users, locked/expired/inactive accounts, one governed user detail, and an
account-status summary. Their JSON files contain no credential IDs or internal URLs. Expected
results and business aliases are listed in `examples/su01/README.md`.

## Real SAP JCo sidecar

The GitHub repository's `jco-sidecar/` directory contains the
production-oriented Java 21 implementation for the
same HTTPS contract. It uses SAP JCo only at runtime, exposes no technical RFC
names, maps the two verified standard user BAPIs, requires TLS and fails closed
when JCo, SAP configuration or SAP connectivity is unavailable. Its deployment
is deliberately separate from the synthetic fixture.

## Development

```bash
npm install
npm test
npm run lint
npm run build
```
