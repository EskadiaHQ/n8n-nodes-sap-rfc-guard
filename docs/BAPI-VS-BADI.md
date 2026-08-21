# BAPI and BAdI are different SAP extension mechanisms

## BAPI

A BAPI is a stable business interface exposed by SAP. An external consumer such
as n8n can invoke an RFC-enabled BAPI through SAP JCo, subject to authentication
and SAP authorization.

Examples used by this node:

- `BAPI_COMPANYCODE_GETLIST` returns company codes.
- `BAPI_MATERIAL_GET_DETAIL` returns governed material fields.
- `BAPI_PO_GETDETAIL1` reads one purchase order.
- `BAPI_USER_CREATE1` creates one user only through the isolated provisioning mode.

The workflow never supplies these technical names. It selects a business
operation such as `getMaterialDetail`; the compiled sidecar owns the fixed
mapping, validates inputs and projects approved outputs.

## BAdI

A BAdI is an enhancement point executed inside SAP. An ABAP implementation is
called when the corresponding SAP process reaches that extension point. n8n
does not normally invoke a BAdI directly.

A BAdI can validate, enrich or react to a process inside SAP. If n8n must be
involved, the BAdI implementation can call an approved outbound API/event, or a
governed RFC/API can execute SAP logic that eventually triggers the BAdI.

## Decision rule

| Requirement | Use |
|---|---|
| n8n needs to read or execute a SAP business function | Governed BAPI/API |
| SAP must insert custom logic into its own standard process | BAdI implementation |
| n8n needs an SAP event | Prefer an event/outbound API; a BAdI may be the internal trigger |

A BAPI is therefore a callable interface. A BAdI is an internal plug-in point.
Neither removes the need for least-privilege authorizations, input validation,
auditing and a release-specific compatibility check.
