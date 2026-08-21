# Governed operation matrix

| Business alias | Internal SAP call | Input | Output | Limit |
|---|---|---|---|---|
| `listSu01Users` | `BAPI_USER_GETLIST` + governed detail | `client`, `maxRows`, `inactiveDays`, `userType`, `accountStatus` | Approved user identity/status fields | 50 default, 500 hard ceiling |
| `getSu01UserDetail` | `BAPI_USER_GET_DETAIL` | `client`, `username` | One approved user record | One user |
| `listSu01RiskAccounts` | Same governed list, classified locally | `client`, `maxRows`, `inactiveDays` | Non-active accounts plus `riskReason` | Same list ceiling |
| `summarizeSu01Accounts` | Same governed list, aggregated locally | `client`, `maxRows`, `inactiveDays`, `dimension` | Counts only | Same list ceiling |
| `listCompanyCodes` | `BAPI_COMPANYCODE_GETLIST` | `client` | Company code and name | Sidecar row ceiling |
| `getCompanyCodeDetail` | `BAPI_COMPANYCODE_GETDETAIL` | `client`, `companyCode` | Approved organization, locale and address fields | One company code |
| `searchMaterials` | `BAPI_MATERIAL_GETLIST` | `client`, `maxRows`, `materialPattern`, `descriptionPattern` | Material number and description | Caller and sidecar row ceiling |
| `getMaterialDetail` | `BAPI_MATERIAL_GET_DETAIL` | `client`, `material`, optional `plant`, `valuationArea`, `valuationType` | Approved general, plant and valuation fields | One material context |
| `getPurchaseOrderDetail` | `BAPI_PO_GETDETAIL1` | `client`, `purchaseOrder` | Approved header fields repeated with bounded item fields | One document, bounded items |
| `getSalesOrderStatus` | `BAPI_SALESORDER_GETSTATUS` | `client`, `salesDocument` | Approved header/item status and delivery fields | One document, bounded items |
| `createSu01CommunicationUser` | `BAPI_USER_CREATE1` + `BAPI_TRANSACTION_COMMIT` + governed detail verification | `client`, `username`, `firstName`, `lastName`, `email`, `validDays` | Creation and verification evidence only | One prefix-restricted Communication user |

The HTTP caller cannot select an RFC function. RFC names exist only inside the
compiled sidecar. Responses omit roles, profiles, password material, SNC data,
parameters, telephone lists and every BAPI field not explicitly mapped.

Account status precedence is: locked, expired, not yet valid, inactive dialog
user, active. The inactive threshold defaults to 90 days and affects only dialog
users; system and communication users require a separate operational review.

Unknown parameters, invalid enumerations and out-of-range limits are rejected
before an RFC call. `client` is evidence, not routing: when supplied it must
match the client fixed in the JCo destination. `lastLogonAt` contains SAP local
date and, when `LOGONDATA-LTIME` exists, local time without an invented UTC
offset.

The creation alias exists only in a separate `user-provisioning` sidecar. It
requires `write=true`, `readOnly=false`, the header mode
`user-provisioning`, and `CREATE <exact username>` confirmation. The sidecar
fixes the SAP user type and group, supplies the initial password from its own
root-protected secret, assigns no roles or profiles and never returns password
material.
