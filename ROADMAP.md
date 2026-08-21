# Roadmap

SAP RFC Guard is published early so its security contract and deployment model
can be reviewed before a production SAP identity is connected.

## Available in `0.1.x`

- Governed HTTPS contract between n8n and an operated sidecar.
- Deny-by-default business aliases and response-field policies.
- Read-only SU01 inventory, detail, risk and summary operations.
- Synthetic contract workflows plus separate real-SAP acceptance workflows.
- Java 21 JCo sidecar with TLS, bounded workers, timeouts and fail-closed health.
- Direct application-server and message-server destination templates.

## Before `0.2.0`

- Complete one controlled execution against SAP with SAP JCo for Linux x86-64.
- Validate the two standard user BAPIs and their structures in the target release.
- Preserve SU01 and STAUTHTRACE evidence for positive and negative cases.
- Verify direct and load-balanced destination modes.
- Publish the community package to npm under the `next` tag.

## Candidate `0.2.x` work

- Optional metrics endpoint without user data or SAP credentials.
- Operation-specific concurrency and rate limits.
- Optional governed snapshot adapter for historical SU01 reporting.
- Additional read-only business aliases only after their SAP authorization and
  data-minimization contracts are documented and tested.

## `1.0.0` gate

- Reproducible SAP acceptance on at least one S/4HANA and one compatible
  SAP NetWeaver AS ABAP system.
- Documented upgrade, rollback, token rotation and certificate rotation.
- Stable operation and error contracts.
- No dependency on an unrestricted RFC or table-reader function.

Write operations are deliberately outside the current roadmap. A future write
capability would require a separate node/credential surface, explicit approval,
idempotency and an independent SAP authorization model.
