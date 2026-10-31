# Approved function catalog (0.7.0)

The catalog lets a workflow call a **standard SAP function that a reviewer has approved**,
without a dedicated form or business alias for each one. The n8n user picks the function
from a live list, reads its real SAP signature and sends the input as JSON. The sidecar
reads the metadata from SAP, validates the input against it and runs the call.

**Scope of this release: reads only.** A policy entry that declares `"effect": "write"` is
rejected when the policy loads. Writes keep using the dedicated, confirmed operations
(invoice, goods receipt, user administration).

## How a call is decided

```
visible in n8n = sidecar policy  ∩  present and remote-enabled in SAP  ∩  credential list
executed       = all of the above, re-checked on every call by the sidecar
                 + SAP S_RFC for the technical user + business authorizations inside the BAPI
```

| Layer | Who decides | What it controls | Refusal code |
|---|---|---|---|
| Credential | n8n owner | `Allow Approved Function Catalog` and `Approved Catalog Functions` | node error, no request sent |
| Policy, metadata | SAP owner, reviewed file on the sidecar | `allowMetadata`: signature and documentation may be read | `METADATA_NOT_APPROVED` |
| Policy, execution | SAP owner | `allowExecute`, approved inputs, fixed values, returned outputs, row limit | `FUNCTION_NOT_APPROVED`, `EXECUTION_NOT_APPROVED`, `PARAMETER_NOT_ALLOWED`, `PARAMETER_FIXED_BY_POLICY` |
| Hard deny list | sidecar code | generic executors (`RFC_READ_TABLE`, `RFC_ABAP_INSTALL_AND_RUN`, `SXPG_*`, `TH_*`), transaction control, metadata RFCs and families with a dedicated flow (`BAPI_USER_*`, `SUSR_*`, `PRGN_*`) | `FUNCTION_FORBIDDEN` |
| SAP RFC authorization | SAP roles of the technical user (S_RFC) | whether the RFC may be called at all | `SAP_RFC_NOT_AUTHORIZED` |
| SAP business authorization | SAP, inside the BAPI (for example M_BEST_BSA, F_LFA1_APP) | which documents the user may see | BAPI `RETURN` message, `outcome: BUSINESS_ERROR` |

The catalog never changes SAP roles. If SAP denies an RFC the answer is a clear
`SAP_RFC_NOT_AUTHORIZED`, not a workaround. SAP credentials stay in the sidecar; n8n and the
browser only receive the sidecar token and SAP data that the policy lets through. The
backend host is removed before the node outputs metadata.

## What SAP really offers (verified on A4H/250, SAP_BASIS 816, JCo 3.1.13)

| Capability | Function | `N8N_LOGALI` | `T422_RFC` | Used for |
|---|---|---|---|---|
| Signature with types, structures, tables, optional flags, defaults, texts | JCo repository → `RFC_METADATA_GET` | yes | yes | signature, validation |
| Remote-enabled / update-task flags | `RFC_GET_FUNCTION_INTERFACE` | yes | yes | discovery (approved ∩ present) |
| Search function modules by pattern | `RFC_FUNCTION_SEARCH` | yes | **no (S_RFC)** | admin candidate search |
| BOR registration (is it a BAPI?) | `SWO_QUERY_API_METHODS` | yes | **no (S_RFC)** | BAPI verification |
| BAPI documentation | `BAPI_INTERFACE_GETDOCU` | yes | **no (S_RFC)** | documentation |
| `BAPI_TRANSACTION_ROLLBACK` | | yes | **no** | `rollback-after-call` |
| `BAPI_PO_GETDETAIL1`, `BAPI_VENDOR_GETDETAIL`, `BAPI_MATERIAL_AVAILABILITY` | | yes | **no** | demo |

A function is a BAPI only if SAP registers it as a BOR method (`SWO_QUERY_API_METHODS`). The
name prefix is never used. Example: `BAPI_PO_GETDETAIL1` is BOR `BUS2012.GETDETAIL1` (and three
other objects); `STFC_CONNECTION` is a generic RFC. When the technical user cannot call
`SWO_QUERY_API_METHODS`, the response says `verified: null` and the kind comes from the
reviewed policy. If SAP contradicts the policy, execution stops with `POLICY_KIND_MISMATCH`.

Without `RFC_FUNCTION_SEARCH` the catalog still works: discovery checks each approved
function with `RFC_GET_FUNCTION_INTERFACE`. Only the administrative search needs it.

## Minimum SAP permissions for the sidecar user

- Metadata (always): S_RFC for `RFC_METADATA_GET` (used by the JCo repository) and
  `RFC_GET_FUNCTION_INTERFACE`. Both already work for `T422_RFC` and `N8N_LOGALI`; check the
  function groups in SU53/ST01 before granting them to a new user.
- Optional: `RFC_FUNCTION_SEARCH` (search), `SWO_QUERY_API_METHODS` (BAPI verification),
  `BAPI_INTERFACE_GETDOCU` (documentation), `BAPI_TRANSACTION_ROLLBACK` (only for entries with
  `"transaction": "rollback-after-call"`).
- Execution: S_RFC for each approved function, plus the display authorizations the BAPI
  checks (listed per entry in `businessAuthorizations`).
- Never grant development (`S_DEVELOP`) or table access (`S_TABU_*`) for the catalog.

## Sidecar configuration

| Variable | Default | Meaning |
|---|---|---|
| `RFC_GUARD_FUNCTION_CATALOG` | empty (catalog off) | Path to the mounted policy JSON. Mount it read-only. |
| `RFC_GUARD_CATALOG_CACHE_TTL_SECONDS` | 900 | Expiry of cached metadata, remote flags, BOR list and docs. |
| `RFC_GUARD_CATALOG_CACHE_MAX_ENTRIES` | 256 | LRU size. Keys are `systemId/client` + function. |
| `RFC_GUARD_CATALOG_MAX_RESPONSE_BYTES` | 1048576 | Larger results fail with `RESPONSE_TOO_LARGE`. |
| `RFC_GUARD_MAX_ROWS` | 50 | Operated ceiling; each entry's `maxRows` can only lower it. |
| `RFC_GUARD_REQUEST_TIMEOUT_SECONDS` | 30 | Per request, including metadata loads. |

The catalog is disabled in `user-provisioning` mode. The policy file is re-read whenever it
changes (no restart). An invalid file disables the catalog (`CATALOG_POLICY_INVALID`) until
it is fixed; it never falls back to an older policy.

## Policy file

See `examples/catalog/policy.a4h-250.json`. One entry:

```json
"BAPI_VENDOR_GETDETAIL": {
  "title": "Read supplier",
  "kind": "BAPI",
  "bor": { "objectType": "LFA1", "method": "GETDETAIL" },
  "effect": "read",
  "transaction": "none",
  "allowMetadata": true,
  "allowExecute": true,
  "inputs": {
    "VENDORNO": { "required": true, "pad": "alpha", "pattern": "[0-9]{10}" },
    "COMPANYCODE": { "pattern": "[A-Z0-9]{4}" }
  },
  "fixed": { "BYPASS_AUTH_CHECK": "" },
  "outputs": ["GENERALDETAIL", "COMPANYDETAIL"],
  "maxRows": 10,
  "businessAuthorizations": ["F_LFA1_APP ACTVT 03", "F_LFA1_BUK ACTVT 03"],
  "approval": { "approvedBy": "...", "approvedOn": "2026-10-31", "reviewReference": "C415-CAT-002" }
}
```

- `effect` and `transaction` are mandatory. `transaction` is `none` or
  `rollback-after-call` (the call and `BAPI_TRANSACTION_ROLLBACK` run in one stateful JCo
  context, so any update-task registration is discarded). Reads are never committed.
  Note: a synchronous RFC ends with an implicit database commit, so `effect: read` is a
  reviewed declaration, not something the sidecar can prove. Review the function before
  approving it.
- `inputs` is the allowlist of parameters the caller may send. Rules: `required`,
  `pattern` (checked on non-initial values), `allowedValues`, `pad: "alpha"` (left-pad
  numeric text to the field length), `maxRows` for input tables, `fields` to restrict
  structure or table fields.
- `fixed` pins scalar IMPORT parameters. A caller that sends one gets
  `PARAMETER_FIXED_BY_POLICY`. Use it for switches such as `BYPASS_AUTH_CHECK`.
- `outputs` lists the EXPORT, CHANGING or TABLES parameters returned to n8n. Everything else
  is dropped. In the example, supplier bank data is not returned. The `RETURN` parameter
  (or `messageParameter`) is always read to separate SAP messages.
- An entry with `allowExecute: true` needs `outputs` and an `approval` with
  `approvedBy`, `approvedOn` and `reviewReference`.

### Approving a new function

1. Search for candidates (needs `"metadataSearch": true` and `RFC_FUNCTION_SEARCH`):
   `GET /v1/catalog/search?pattern=BAPI_PO_GET*`. Candidates are informational.
2. Add an entry with `allowMetadata: true` and `allowExecute: false`. Read the signature:
   `GET /v1/catalog/functions/{name}`. Check `policy.warnings`, `bapi.verified` and the SAP
   documentation.
3. Review the function in SAP (SE37): it must not post or change data, call update tasks
   that matter, or expose more than intended.
4. Declare `inputs`, `fixed`, `outputs`, `maxRows`, `businessAuthorizations` and the
   approval, then set `allowExecute: true`.
5. Make sure the technical user has S_RFC for the function. Do not widen roles to make a
   test pass.
6. Add the function to the credential's `Approved Catalog Functions`.
7. Run `Validate Only` from n8n, then one real read, and keep the correlation ID.

## Sidecar API

| Route | Purpose |
|---|---|
| `GET /v1/catalog/functions?query=&cursor=&limit=` | Approved ∩ present ∩ remote-enabled, paginated (max 50), with `excluded` reasons. |
| `GET /v1/catalog/functions/{name}` | Real signature, policy view, input template, BAPI check, documentation, permissions. |
| `POST /v1/catalog/functions/{name}/execute` | `{"function","parametersJson","options":{"maxRows","dryRun"},"context":{"readOnly":true}}` with `X-RFC-Guard-Mode` set to the sidecar mode. |
| `GET /v1/catalog/search?pattern=&limit=` | Administrative SAP search; results are not executable. |

Encode `/` in namespaced names as `%2F`. `parametersJson` is parsed with exact decimals and
rejects duplicated keys.

## Values and types

| SAP type | Send | Notes |
|---|---|---|
| CHAR, NUMC, STRING | JSON string | Numbers are rejected (`TYPE_MISMATCH`) so leading zeros cannot be lost. NUMC is left-padded. |
| DEC (BCD), DECFLOAT | string such as `"12.50"` or exact JSON number | Never rounded: too many decimals → `DECIMAL_SCALE_EXCEEDED`; too many digits → `DECIMAL_TOO_LARGE`. Returned as strings. |
| INT, INT1, INT2, INT8 | whole number | Range checked. |
| DATE / TIME | `YYYY-MM-DD` / `HH:MM:SS` | Returned in the same format. |
| STRUCTURE | object | Unknown field → `FIELD_UNKNOWN` with suggestions. |
| TABLE | array of objects | Input rows capped per rule; output rows capped by `maxRows`, with `meta.tableRows` and `meta.truncated`. |
| RAW, XSTRING | not accepted as input | Returned as Base64. |

## Errors are separated

| Category | HTTP | Codes |
|---|---|---|
| Policy / request | 400, 403, 404, 409 | `FUNCTION_NOT_APPROVED`, `PARAMETER_UNKNOWN`, `TYPE_MISMATCH`, `POLICY_INCOMPLETE`, … |
| SAP authorization | 403 | `SAP_RFC_NOT_AUTHORIZED` (S_RFC) |
| ABAP exception | 422 | `ABAP_EXCEPTION` with `details.sapKey` |
| ABAP runtime error | 502 | `SAP_ABAP_RUNTIME_ERROR` |
| Transport | 502, 504 | `SAP_TRANSPORT_FAILURE`, `SAP_LOGON_FAILED`, `SAP_READ_TIMEOUT` |
| Business message | 200 | `outcome: BUSINESS_ERROR` or `WARNING`, `messages[]` from `RETURN` |

The node shows the sidecar code and message. By default an SAP error message stops the
node (`Fail on SAP Error Message`); turn it off to branch on `outcome`.

## Provenance

Every result carries `meta.source: sap-jco`, `syntheticData: false`, `readOnly: true`,
`write: false`, `kind`, `kindVerified`, `transactionPolicy`, `transaction`, `rowLimit`,
`tableRows`, `truncated`, `policySha256`, `approval`, `signatureSource`, SAP `systemId`,
`client` and `release`, `executedAt`, `durationMs` and `correlationId`. The sidecar logs one
`RFC_GUARD_CATALOG_CALL` line per call with function, correlation ID, outcome and duration,
never parameter values.

## Why JSON instead of generated form fields

The n8n node API builds dynamic choices (`loadOptions`, `listSearch`) and flat dynamic
columns (`resourceMapper`, for scalar fields). It cannot draw nested structures or tables
from metadata at run time, and its number fields are JavaScript doubles. The node therefore
offers: a live, paginated function list; a **Get Signature** operation that returns the real
parameters and an input template; a JSON input that is sent to the sidecar as typed text;
and **Validate Only**, which checks the input against the real signature without calling
the function.

## Limits of this release

- Reads only; no catalog writes, commits or confirmations.
- No per-field projection of returned structures (whole parameters are approved).
- RAW and XSTRING inputs are not supported.
- SAP documentation is available for BOR BAPIs only.
- A sidecar user without `SWO_QUERY_API_METHODS` cannot verify the BAPI kind.
