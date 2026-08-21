# SAP BASIS runbook

Replace the deployment placeholders with the approved SAP system ID, client,
application/message server and instance. Do not commit a real destination or
technical identity to this repository.

## Dedicated identity

Create a non-dialog technical identity such as `N8N_RFC_DEV`. Do not reuse the
ADT administrator or a trainer account. Use a distinct identity in production.

Minimum authorization design to validate with `STAUTHTRACE`:

- `S_RFC`, activity `16`, limited to the exact BAPIs enabled in the credential.
  For SU01 reporting this is `BAPI_USER_GETLIST` and `BAPI_USER_GET_DETAIL`.
  A business-data reader may additionally need only the approved subset of
  `BAPI_COMPANYCODE_GETLIST`, `BAPI_COMPANYCODE_GETDETAIL`,
  `BAPI_MATERIAL_GETLIST`, `BAPI_MATERIAL_GET_DETAIL`,
  `BAPI_PO_GETDETAIL1` and `BAPI_SALESORDER_GETSTATUS`.
- When the target release authorizes by function group rather than function,
  inspect each BAPI in SE37 and scope the role to only those required groups.
- Display authorization for the intended user groups through `S_USER_GRP`,
  activity `03`. Avoid unrestricted groups unless the report genuinely covers
  every SU01 account.
- No user-maintenance activity, no table-generic RFC, no `RFC_READ_TABLE`, no
  development authorization and no wildcard RFC role.

Run one positive and one negative trace. The positive test must read a small
approved sample. The negative test must prove that an unrelated RFC and every
user write operation are rejected. Preserve `SU53`/`STAUTHTRACE` evidence with
the role transport.

## Verified standard interfaces

Verify every enabled function in the target system before deployment. The user
BAPIs normally belong to package `SUSR`, function group `SU_USER`:

- `BAPI_USER_GETLIST`: search and bounded user list.
- `BAPI_USER_GET_DETAIL`: logon data, address, administrative dates and lock
  state. The sidecar requests and publishes only its approved subset.

The A4H/250 acceptance system also exposes the six company/material/document
BAPIs listed above as RFC-enabled. Repeat SE37 signature verification and
positive/negative traces in every target release; do not infer compatibility
from A4H alone.

No Z table or custom RFC is required for this first release. A snapshot table is
only justified later for historical reporting, with retention and job monitoring.
