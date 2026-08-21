# Changelog

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

## 0.1.1 - 2026-08-21

- Require an explicit credential opt-in before the node can run as an AI tool.

## 0.1.0 - 2026-08-21

- Add read-only HTTPS sidecar connection testing.
- Add deny-by-default business-operation allowlists.
- Add required response-field projection, row limits, byte limits, correlation, and redaction.
- Add SU01 reporting policy example and contract tests.
