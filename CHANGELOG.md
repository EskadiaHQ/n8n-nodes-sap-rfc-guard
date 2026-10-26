# Changelog

## 0.6.0 - 2026-10-26

- Reunify the two lines of this package. The business-operations line was installed in
  the Logali n8n instances as 0.4.14 but never published. The published line covers the
  0.4.13 security hardening and the 0.5.x user administration. One package again serves
  read-only, business-operations and user-provisioning credentials.
- Business-operations credentials (invoice create/park and goods receipt) now also get
  the 0.4.13 hardening: unhealthy sidecars fail closed and API tokens need at least
  32 bytes.
- Read-only credentials reject every write alias. Business-operation credentials cannot
  allow any user-administration alias.
- Keep the scoped retry for the operated legacy provisioner (`https://sap-rfc-provision-jco:8080`,
  user-provisioning credentials only). It was installed as a DEV-only client patch on
  2026-10-23: at most two attempts within the original timeout, health retried on
  transient errors and HTTP 502/503/504, user creation retried only when the request
  provably never left n8n, redirects disabled, and one secret-free
  `SAP_PROVISIONER_FAILURE` log line per final failure. Every other destination keeps a
  single attempt.
- The synthetic contract fixture's default token is now `RFC_GUARD_CONTRACT_FIXTURE_ONLY_01`.
  The old 26-byte default could not pass the 32-byte token check from 0.4.13, so the
  fixture was unusable with any hardened release. Set the same value in the credential, or
  pass your own `CONTRACT_TOKEN` of at least 32 bytes.
- JCo sidecar 0.6.0 serves the three modes from one build: `read-only`,
  `business-operations` (reads plus invoice create/park and goods receipt) and
  `user-provisioning` (create, lock, unlock and reset). Each write is accepted only in
  its own mode, with its own `X-RFC-Guard-Mode` header and exact confirmation.

## JCo sidecar 0.5.1 - 2026-10-26 (no npm release)

- Read the SAP `ISLOCKED` codes returned by `BAPI_USER_GET_DETAIL` (`L` locked,
  `U` unlocked). Every real SU01 user was reported as `Unlocked`, so
  `listSu01RiskAccounts` missed locked accounts and `lockSu01User` could not
  verify its own result. Rebuild operated sidecars to get correct lock states.
- Lock, unlock and password reset run their BAPI and commit inside a stateful
  JCo context and verify with an uncached read-back. Verified against A4H/250.
- Runtime healthcheck without a second JVM and an explicit JVM memory budget
  (PR #24).

## 0.5.0 - 2026-10-26

- Add governed user lifecycle operations to **User Administration**: *Lock User*
  (`lockSu01User` → `BAPI_USER_LOCK`), *Unlock User* (`unlockSu01User` →
  `BAPI_USER_UNLOCK`) and *Reset Password* (`resetSu01UserPassword` →
  `BAPI_USER_CHANGE` with `PASSWORD`/`PASSWORDX`). Each one commits, reads the user
  back and requires its own exact confirmation (`LOCK`, `UNLOCK`, `RESET` + username).
- The reset applies the provisioning sidecar's server-held initial password; SAP
  forces a change at next logon and no password is sent to or returned by n8n.
- Lifecycle changes reuse the provisioning sidecar's username prefix, so they cannot
  reach users the guard did not provision; missing users fail with `USER_NOT_FOUND`.
- User-provisioning credentials may now allow any subset of the four write aliases;
  read-only credentials still reject every write alias.
- Synthetic contract fixture: `CONTRACT_MODE=user-provisioning` serves the four
  write aliases over in-memory users, plus contract and end-to-end tests that run
  the published example workflow through the node.
- New examples in `examples/su01/user-admin/` (synthetic and real, both inactive).

## Operated 0.4.14 - 2026-08-25 (installed, never published)

- Add a separately governed `Incoming Invoice / Park` operation backed by
  `BAPI_INCOMINGINVOICE_PARK`, explicit commit and read-back verification.
- Require the exact `PARK INVOICE <reference>` confirmation and a credential policy that
  explicitly allowlists `parkIncomingInvoice`; posting and user provisioning remain separate.
- Return explicit `parked` and `posted` evidence so downstream workflows cannot confuse an
  MIR7 preliminary invoice with an FI document that has already been posted.
- Add a separately allowlisted `Goods Movement / Create Purchase-Order Goods Receipt` operation
  backed by `BAPI_GOODSMVT_CREATE`, fixed movement 101, `GM_CODE 01`, explicit commit/rollback and
  `BAPI_GOODSMVT_GETDETAIL` read-back verification.
- Require `POST GOODS RECEIPT <reference>`, exact open-quantity validation and a credential opt-in
  dedicated to goods receipts; AI Tool execution remains blocked.
- Add an importable customer order self-service contract that requires trusted channel identity
  and an allowlisted sales document before the real SAP read, returning HTTP 403 with zero SAP
  calls for cross-customer or unauthenticated requests.

## Operated 0.4.13 - 2026-08-24 (installed, never published)

- Add a governed `Incoming Invoice / Create` operation backed by
  `BAPI_INCOMINGINVOICE_CREATE`, explicit commit and read-back verification.
- Require a dedicated business-operations credential, explicit opt-in and exact
  `CREATE INVOICE <reference>` confirmation; AI Tool execution remains blocked.
- Preserve governed purchase-order and invoice reads in the same operated test sidecar so a
  workflow can validate, post and verify one real MM invoice end to end.
- Reject unexpected or malformed invoice fields before SAP and avoid duplicate writes by
  reusing an existing document with the same company, date and reference.
- Keep `BAPI_INCOMINGINVOICE_CREATE` and `BAPI_TRANSACTION_COMMIT` in one stateful JCo context;
  the A4H/250 acceptance created `5105600755/2025` and an identical retry reused it.

## 0.4.13 - 2026-08-23

- Fail closed when a read-only or user-provisioning sidecar reports a degraded,
  missing, or otherwise unhealthy status during credential checks and runtime
  connection tests.
- Require RFC sidecar API tokens to contain at least 32 bytes, matching the IDoc
  Guard security baseline. Existing shorter tokens must be rotated before this
  version is installed.
- Add regression coverage for unhealthy sidecars and weak API tokens.

## 0.4.12 - 2026-08-23

- Declare the n8n workflow runtime explicitly as a development dependency so
  npm does not materialize the optional peer as a production dependency during
  package validation.
- Add a production-only dependency audit gate; the published node remains
  host-provided and ships with zero bundled production dependencies.

## 0.4.11 - 2026-08-23

- Derive ATP availability from requested and confirmed quantities instead of
  treating an empty SAP dialog flag as proof of full availability.
- Return distinct `NotAvailable`, `PartiallyAvailable`, `FullyAvailable`, and
  `NotAvailabilityRelevant` states with Java regression coverage.
- Align standalone and BTP sidecar health evidence on JCo sidecar `0.4.5`.

## 0.4.10 - 2026-08-23

- Mark Plant as required in the typed material availability UI so incomplete
  ATP checks fail in the editor instead of reaching the RFC sidecar.
- Keep Plant optional for material-detail reads and add regression coverage for
  both visual contracts.

## 0.4.9 - 2026-08-23

- Validate the requested SAP client against the client resolved from a managed
  BTP Destination instead of the empty local JCo property map.
- Add regression coverage for managed-destination client matching and keep the
  mismatch guard active for every other client.

## 0.4.8 - 2026-08-22

- Add an opt-in `X-RFC-Guard-Token` credential mode for SAP BTP deployments,
  where the XSUAA runtime reserves the standard Authorization bearer header.
- Package the JCo sidecar as both the existing standalone JAR and a Tomcat WAR
  for the SAP Java buildpack, without bundling proprietary JCo libraries.
- Resolve the BTP sidecar token from a bound user-provided service so deployment
  manifests never contain or overwrite the secret.

## 0.4.7 - 2026-08-22

- Adopt the approved high-resolution Logali Guard family artwork for the RFC node and credential.
- Show `RFC` as the connection itself, with one outbound and one inbound directional arrow.
- Use a new versioned PNG asset without changing node behavior, credentials, or sidecar contracts.

## 0.4.6 - 2026-08-22

- Restore the exact HANA Guard family artwork as the RFC node base instead of the visually unrelated replacement frame.
- Replace the dense multi-arrow mark with a larger two-endpoint connection badge that remains legible at n8n canvas size.
- Use a new versioned icon filename so existing n8n and browser caches cannot retain the `0.4.5` artwork.

## 0.4.5 - 2026-08-22

- Reference the corrected RFC artwork through a versioned icon filename so n8n and browser caches cannot keep serving the legacy asset URL.
- Preserve the `0.4.4` smoke-test, acceptance-checklist and roadmap corrections unchanged.

## 0.4.4 - 2026-08-22

- Replace the legacy embedded RFC artwork with the actual HANA Guard family frame and a large bidirectional connection symbol.
- Repair the operated smoke test so it sends the required read-only mode header and fixed n8n client context.
- Mark the verified purchase-order and sales-order acceptance cases complete while keeping message-server/load-balanced mode pending.

## 0.4.3 - 2026-08-22

- Align the SAP RFC Guard node icon with the Logali HANA Guard visual family.
- Replace only the HANA database badge with a bidirectional connection mark for RFC.
- Keep node behavior, credentials and sidecar contracts unchanged.

## 0.4.2 - 2026-08-21

- Normalize the locale-formatted SAP dates returned by purchase-order schedule tables.
- Fix real `BAPI_PO_GETDETAIL1` reads that failed when JCo exposed a date as `dd.MM.yyyy`.
- Add regression coverage for compact SAP dates, ISO dates and common JCo display formats.

## 0.4.1 - 2026-08-21

- Correct the AI Agent workflow to use n8n's generated `sapRfcGuardTool` node type.
- Add a regression test for the importable agent workflow.
- Verify a governed AI Agent end to end against a real sales order in A4H/250 without enabling writes.

## 0.4.0 - 2026-08-21

- Add typed ATP availability checks through `BAPI_MATERIAL_AVAILABILITY`.
- Add bounded incoming-invoice list/detail operations and potential duplicate detection.
- Add vendor/customer open-item reads and currency-grouped overdue summaries.
- Expand purchase-order detail with schedules, confirmations, receipt/invoice history totals and derived open statuses.
- Add governed vendor and customer detail operations with minimized contact, company and block/payment fields.
- Add typed n8n resources and guarded parameter validation for every new alias.

## 0.3.1 - 2026-08-21

- Correct the credential and node documentation links to the public `EskadiaHQ` repository.
- Refresh the roadmap and real-SAP acceptance report after the first controlled A4H/250 executions.
- Keep this patch release limited to documentation and package metadata; the next business aliases remain scheduled for `0.4.0`.

## 0.3.0 - 2026-08-21

- Add first-class Company Code, Material, Purchase Order and Sales Order resources to the n8n UI.
- Add six fixed read aliases backed by `BAPI_COMPANYCODE_GETLIST`, `BAPI_COMPANYCODE_GETDETAIL`, `BAPI_MATERIAL_GETLIST`, `BAPI_MATERIAL_GET_DETAIL`, `BAPI_PO_GETDETAIL1` and `BAPI_SALESORDER_GETSTATUS`.
- Verify every BAPI and its exact signature in A4H/250 before exposing the alias.
- Keep technical function names compiled into the sidecar and continue rejecting them as workflow input.
- Add identifier validation, bounded material search and field-minimized responses for each business object.
- Add real-SAP workflows for company/material reads and placeholder-gated purchasing/sales document reads.
- Document the difference between callable BAPIs and internal BAdI enhancement points.

## 0.2.0 - 2026-08-21

- Add a separately configured `User Administration / Create Communication User` operation.
- Map the governed alias to `BAPI_USER_CREATE1` plus explicit commit and read-back verification.
- Require a dedicated provisioning credential, endpoint mode, token, exact target-bound confirmation and non-AI execution.
- Restrict created accounts to an operated username prefix, Communication type, fixed SAP user group and short validity.
- Keep the initial password only in the sidecar secret and never return it to n8n.
- Return explicit evidence that no roles or profiles were assigned by the operation.
- Keep the existing read-only sidecar and credentials isolated and backward compatible.

## 0.1.2 - 2026-08-21

- Reject unknown and invalid operation parameters before an RFC call.
- Enforce that a workflow's stated SAP client matches the operated destination.
- Apply the requested inactivity threshold instead of silently using the default.
- Preserve last-logon time when the target system exposes `LOGONDATA-LTIME`, without inventing UTC.
- Preserve sanitized `syntheticData` and backend system/client/release evidence in `_rfc`, while removing the backend host.
- Expose sanitized backend identity and business capabilities in the executable connection check.
- Add R/3 compatibility, governed Z snapshot, SAP download request and public roadmap documentation.
- Add executable contract-fixture tests and expand the JCo sidecar suite to 17 tests.
- Add importable last-logon review and cross-release system-readiness workflows.
- Update Jackson to its patched 2.18 line and refresh the n8n development/release tooling.
- Fix JCo volume traversal while keeping the proprietary runtime owned by root and read-only to the service group.

## 0.1.1 - 2026-08-21

- Require an explicit credential opt-in before the node can run as an AI tool.

## 0.1.0 - 2026-08-21

- Add read-only HTTPS sidecar connection testing.
- Add deny-by-default business-operation allowlists.
- Add required response-field projection, row limits, byte limits, correlation, and redaction.
- Add SU01 reporting policy example and contract tests.
