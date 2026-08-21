# Governed SU01 operation matrix

| Business alias | Internal SAP call | Input | Output | Limit |
|---|---|---|---|---|
| `listSu01Users` | `BAPI_USER_GETLIST` + governed detail | `client`, `maxRows`, `inactiveDays`, `userType`, `accountStatus` | Approved user identity/status fields | 50 default, 500 hard ceiling |
| `getSu01UserDetail` | `BAPI_USER_GET_DETAIL` | `client`, `username` | One approved user record | One user |
| `listSu01RiskAccounts` | Same governed list, classified locally | `client`, `maxRows`, `inactiveDays` | Non-active accounts plus `riskReason` | Same list ceiling |
| `summarizeSu01Accounts` | Same governed list, aggregated locally | `client`, `maxRows`, `inactiveDays`, `dimension` | Counts only | Same list ceiling |

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
