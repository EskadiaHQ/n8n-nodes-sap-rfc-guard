# Roadmap

SAP RFC Guard is published early so its security contract and deployment model
can be reviewed before a production SAP identity is connected.

## Available in `0.3.x`

- Governed HTTPS contract between n8n and an operated sidecar.
- Deny-by-default business aliases and response-field policies.
- Read-only SU01 inventory, detail, risk and summary operations.
- Synthetic contract workflows plus separate real-SAP acceptance workflows.
- Java 21 JCo sidecar with TLS, bounded workers, timeouts and fail-closed health.
- Direct application-server and message-server destination templates.
- Optional, isolated Communication-user provisioning through `BAPI_USER_CREATE1`.
- Target-bound confirmation, username prefix, short validity, fixed user group and zero role/profile assignment.
- First-class read resources for company codes, materials, purchase orders and sales-order status.
- Six release-verified fixed BAPI mappings with operation-specific input and output contracts.

## Before `1.0.0`

- Validate every enabled standard BAPI and its projected structures in each target release.
- Replace the temporary test account with a least-privilege service account.
- Preserve complete SU01 and STAUTHTRACE evidence for positive and negative cases.
- Verify direct and load-balanced destination modes.
- Complete positive purchase-order and sales-order reads with approved document IDs.

## Candidate `0.4.x` work

- Material ATP through a fixed `BAPI_MATERIAL_AVAILABILITY` alias.
- Incoming-invoice listing/detail and bounded duplicate-candidate detection.
- Vendor and customer open-item reads plus overdue summaries.
- Purchase-order delivery schedules, confirmations and receipt/invoice totals.
- Governed vendor and customer master summaries.
- Real AI Agent, email and approval workflows using only fixed aliases.
- Optional metrics endpoint without user data or SAP credentials.
- Operation-specific concurrency and rate limits.
- Optional governed snapshot adapter for historical SU01 reporting.
- Additional read-only business aliases only after their SAP authorization,
  performance and data-minimization contracts are documented and tested.

## `1.0.0` gate

- Reproducible SAP acceptance on at least one S/4HANA and one compatible
  SAP NetWeaver AS ABAP system.
- Documented upgrade, rollback, token rotation and certificate rotation.
- Stable operation and error contracts.
- No dependency on an unrestricted RFC or table-reader function.

Further write operations remain outside the roadmap until each receives its own
isolated credential/sidecar mode, explicit approval, idempotency contract and
independent SAP authorization model.
