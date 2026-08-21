# Governed SU01 operation matrix

| Business alias | Internal SAP call | Input | Output | Limit |
|---|---|---|---|---|
| `listSu01Users` | `BAPI_USER_GETLIST` + governed detail | `maxRows`, `userType`, `accountStatus` | Approved user identity/status fields | 50 default, 500 hard ceiling |
| `getSu01UserDetail` | `BAPI_USER_GET_DETAIL` | `username` | One approved user record | One user |
| `listSu01RiskAccounts` | Same governed list, classified locally | `maxRows` | Non-active accounts plus `riskReason` | Same list ceiling |
| `summarizeSu01Accounts` | Same governed list, aggregated locally | `dimension` | Counts only | Same list ceiling |

The HTTP caller cannot select an RFC function. RFC names exist only inside the
compiled sidecar. Responses omit roles, profiles, password material, SNC data,
parameters, telephone lists and every BAPI field not explicitly mapped.

Account status precedence is: locked, expired, not yet valid, inactive dialog
user, active. The inactive threshold defaults to 90 days and affects only dialog
users; system and communication users require a separate operational review.
