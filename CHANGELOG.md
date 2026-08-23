# Changelog

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
