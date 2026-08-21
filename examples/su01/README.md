# SU01 workflow collections

Two separate collections are provided. Never bind both collections to the
same credential.

## Synthetic contract collection

These five workflows exercise distinct governed read-only contracts against the local fixture.
They do not connect to SAP and must remain unpublished.

| Workflow | Business alias | Expected rows |
|---|---|---:|
| Active Dialog Users | `listSu01Users` | 1 |
| Technical Users Inventory | `listSu01Users` | 1 |
| Locked and Expired Accounts | `listSu01RiskAccounts` | 3 |
| User Detail Lookup | `getSu01UserDetail` | 1 |
| Account Status Summary | `summarizeSu01Accounts` | 4 |

After importing, select the dedicated synthetic contract credential in each SAP RFC Guard node.
The workflow JSON files intentionally contain no credential IDs or internal URLs.

## Real SAP acceptance collection

The six JSON files in `real/` are inactive acceptance workflows for the JCo
sidecar. They use the same governed business aliases, but require a separate
credential pointing to `https://sap-rfc-guard-jco:8080` with certificate
validation enabled.

| Workflow | Purpose |
|---|---|
| Active Dialog Users | Bounded list of currently active dialog accounts |
| Technical Users Inventory | Inventory of non-dialog account types |
| Locked and Expired Accounts | Accounts requiring review |
| User Detail Lookup | Governed detail for one approved test username |
| Account Status Summary | Local aggregation by status |
| Read-only Acceptance | Positive source/read-only checks and reviewer evidence |
| Last Logon Review | Counts known/unknown last-logon values and preserves real-source evidence |
| System Readiness Check | Verifies transport, backend identity and required business aliases before BAPI acceptance |

Do not import or publish this collection until all acceptance gates are met:

- SAP JCo 3 for Linux x86-64 is mounted in the sidecar.
- A dedicated least-privilege SAP technical user is configured.
- The n8n container trusts the internal CA.
- Authenticated health reports `source` through backend identity and the
  workflow output reports `source=sap-jco`, `syntheticData=false` and
  `readOnly=true`.
- The detail workflow placeholder `REPLACE_WITH_APPROVED_TEST_USER` is replaced
  only with an approved non-sensitive test account.
- Results are compared with SU01 and STAUTHTRACE shows only the expected reads.

The real JSON files intentionally contain no credential IDs, secrets, internal
tokens or bound workflow IDs.
