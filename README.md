# Logali SAP RFC Guard

Security-first n8n community node for governed SAP RFC/BAPI reads and isolated
Communication-user provisioning.

> **Early access (`0.3.x`)**: the n8n governance contract and synthetic examples
> are testable now. Real SAP execution additionally requires the operated JCo
> sidecar, SAP's proprietary JCo runtime and a least-privilege SAP identity.

The node does **not** load native RFC libraries into n8n. It calls an operated HTTPS sidecar that
owns SAP JCo or SAP NetWeaver RFC SDK, the SAP technical identity, connection pooling, RFC type
conversion, and SAP-side authorization.

```text
n8n workflow -> Logali SAP RFC Guard -> HTTPS sidecar -> RFC/SNC -> SAP R/3, ECC, or S/4HANA
```

## Security model

- Read operations remain on a dedicated sidecar that must attest `readOnly: true`.
- User creation requires a different sidecar, token and credential that attest `writeEnabled: true`.
- Deny by default. Credentials contain an exact business-operation allowlist.
- Technical names such as `BAPI_*`, `RFC_*`, `Z_*`, and `Y_*` are rejected in workflows.
- Every operation has a required response-field allowlist.
- Request bytes, response bytes, rows, and timeouts are bounded.
- AI Tool use requires a separate credential opt-in and keeps the same operation and field policies.
- SAP usernames and passwords never enter n8n credentials or workflow JSON.
- HTTPS is mandatory except for an explicitly enabled isolated local contract test.
- The only write alias creates one prefix-restricted Communication user with short validity, no roles and no profiles.
- The initial password remains in the provisioning sidecar secret and is never sent to or returned by n8n.

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
  "version": "0.3.0",
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

- **Connection / Test Connection** checks authentication and the capability expected by the selected credential mode.
- **Company Code / Get Many** maps to `BAPI_COMPANYCODE_GETLIST`.
- **Company Code / Get Details** maps to `BAPI_COMPANYCODE_GETDETAIL`.
- **Material / Search** maps to bounded `BAPI_MATERIAL_GETLIST`.
- **Material / Get Details** maps to `BAPI_MATERIAL_GET_DETAIL`.
- **Purchase Order / Get Details** maps to `BAPI_PO_GETDETAIL1`.
- **Sales Order / Get Status** maps to `BAPI_SALESORDER_GETSTATUS`.
- **Read Operation / Execute Approved Read** runs one credential-allowlisted business operation.
- **User Administration / Create Communication User** calls the isolated provisioning sidecar
  after an exact `CREATE <username>` confirmation. It maps only to `BAPI_USER_CREATE1` and does
  not accept technical function names, roles, profiles or password input from a workflow.

The visible operation description states which fixed BAPI is used, but the BAPI
name is never editable. See [`docs/BAPI-VS-BADI.md`](docs/BAPI-VS-BADI.md) for
the distinction between an external business interface and an internal SAP
enhancement point.

The node is intentionally separate from Logali HANA Guard. They share governance principles but
use different transports: HANA Guard speaks HANA SQL; SAP RFC Guard speaks HTTPS to an RFC
sidecar.

## Compatibility and optional snapshot

The transport does not require HANA. It can target compatible SAP R/3, ECC and
S/4HANA systems, but every enabled BAPI and its exact structure must be checked
in each target release. SAP documents `BAPI_USER_GETLIST` from release 6.20;
older R/3 systems may require a narrowly scoped custom read RFC. See
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md).

Current SU01 data should normally be read from the standard BAPIs. When a
historical point-in-time report or a large scheduled extract is required, use
the separated governed Z snapshot pattern in
[`docs/Z-SNAPSHOT-PATTERN.md`](docs/Z-SNAPSHOT-PATTERN.md), never an unrestricted
table-reader RFC.

## Synthetic contract test

`contract-sidecar/` contains an isolated test double for validating the node before a real SAP
sidecar is available. It does **not** implement RFC and it returns only fictitious SU01-style
records. It exposes `listSu01Users`, marks responses with `source: contract-fixture` and
`readOnly: true`, and rejects every other operation.

The importable workflow is
`examples/rfc-guard-su01-contract-test.json`. It is deliberately distributed without a linked
credential. After import, select a dedicated fixture credential and keep the workflow unpublished.
Never reuse the fixture token or the insecure HTTP option for a real SAP connection.

`examples/su01/` adds focused examples for a webinar or acceptance test: active dialog users,
technical users, locked/expired/inactive accounts, one governed user detail, account-status
summary, last-logon review and system readiness. Their JSON files contain no credential IDs or
internal URLs. Expected results and business aliases are listed in `examples/su01/README.md`.

`examples/business/real/` adds first-class company, material, purchase-order and
sales-order examples. Document workflows contain placeholders and fail input
validation until an approved ID from the target client is supplied.

## Real SAP JCo sidecar

The GitHub repository's `jco-sidecar/` directory contains the
production-oriented Java 21 implementation for the
same HTTPS contract. It uses SAP JCo only at runtime, exposes no technical RFC
names, maps the verified standard user BAPIs, requires TLS and fails closed
when JCo, SAP configuration or SAP connectivity is unavailable. Its deployment
is deliberately separate from the synthetic fixture.

Provisioning is also separate from reporting: operate a second container with
`RFC_GUARD_MODE=user-provisioning`, its own certificate/API token, a fixed
username prefix and user group, a short maximum validity, and a server-held
initial password. Never point a read-only credential at that endpoint.

## Development

```bash
npm install
npm test
npm run lint
npm run build
```

See [`ROADMAP.md`](ROADMAP.md) for the early-access acceptance gates and planned
work. If the SAP JCo download is blocked, the minimal authorization request is
available in [`docs/SAP-DOWNLOAD-REQUEST.md`](docs/SAP-DOWNLOAD-REQUEST.md).
