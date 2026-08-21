# Optional governed Z snapshot

The default design reads current SU01 information through standard BAPIs. A Z
table is justified only when the requirement is historical reporting, a stable
point-in-time extract, or avoiding thousands of detail BAPI calls during a
reporting window.

## Recommended separation

```text
Scheduled ABAP job -> governed Z snapshot table -> read-only Z RFC -> JCo sidecar -> n8n
```

- The ABAP job owns extraction and classification inside SAP.
- The n8n technical identity receives only execute access to the read RFC.
- The read RFC has a fixed output structure and bounded selection parameters.
- n8n cannot name tables, submit SQL, trigger the snapshot job or write rows.
- The public business alias can be `listSu01Snapshots`; the technical Z name
  remains compiled inside the sidecar.

## Minimal snapshot fields

| Field | Purpose |
|---|---|
| `MANDT` | Client isolation |
| `SNAPSHOT_ID` | Immutable run identifier |
| `SNAPSHOT_AT` | Snapshot timestamp |
| `USERNAME` | SAP user identifier |
| `USER_TYPE` | Dialog/system/communication/reference/service |
| `VALID_FROM`, `VALID_TO` | Validity window |
| `LAST_LOGON_DATE`, `LAST_LOGON_TIME` | Last logon evidence |
| `LOCK_STATUS` | Governed normalized lock state |
| `ACCOUNT_STATUS` | Governed normalized account state |
| `SOURCE_SYSTEM` | SAP system identifier |

Names and email addresses should be excluded unless the report has an approved
business purpose. Password material, profiles, roles, SNC identities and raw
authorization data are never copied to this table.

## Operational controls

- Separate job identity from the RFC read identity.
- Use an immutable snapshot ID and reject duplicate completion.
- Record row count, start/end time and status in a small run-control table.
- Define retention explicitly, for example 30 or 90 days, and purge inside SAP.
- Monitor failed or incomplete jobs before n8n consumes the snapshot.
- Restrict the RFC to snapshot ID/date, status and bounded row count.
- Trace the positive read and prove that direct table access and writes fail.

This pattern is not required for the first live test. It is an optional second
adapter and should not replace standard BAPIs merely to simplify development.
