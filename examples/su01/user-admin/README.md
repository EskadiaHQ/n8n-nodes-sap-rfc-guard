# Governed user administration examples

Two inactive workflows show the complete governed lifecycle of one SAP user with
the **User Administration** resource of Logali SAP RFC Guard:

| Step | Node operation | Business alias | SAP BAPI (fixed in the sidecar) | Confirmation |
|---|---|---|---|---|
| 1 | Create Communication User | `createSu01CommunicationUser` | `BAPI_USER_CREATE1` + commit + read-back | `CREATE <user>` |
| 2 | Lock User | `lockSu01User` | `BAPI_USER_LOCK` + commit + read-back | `LOCK <user>` |
| 3 | Unlock User | `unlockSu01User` | `BAPI_USER_UNLOCK` + commit + read-back | `UNLOCK <user>` |
| 4 | Reset Password | `resetSu01UserPassword` | `BAPI_USER_CHANGE` (`PASSWORD`/`PASSWORDX`) + commit | `RESET <user>` |

- `user-lifecycle-contract.json` runs against the synthetic fixture, no SAP needed:

  ```bash
  CONTRACT_MODE=user-provisioning CONTRACT_TOKEN=RFC_GUARD_PROVISIONING_FIXTURE_ONLY_01 \
    PORT=8081 node contract-sidecar/server.mjs
  ```

  Create a *User Provisioning* credential with Base URL `http://127.0.0.1:8081`,
  that fixture token (the node requires at least 32 bytes), *Allow Insecure HTTP* (fixture only), *Allow Governed User
  Administration* and the four aliases in **Allowed Operations**. The fixture
  pre-creates `N8N_DEMO_00` for repeated lock/unlock/reset runs.
- `user-lifecycle-real.json` targets an operated provisioning sidecar. It changes a
  real SAP user: keep it inactive and change the username and every confirmation.

Example **Data Field Policies JSON** for the provisioning credential:

```json
{
  "createSu01CommunicationUser": ["username", "created", "verified", "validFrom", "validTo", "accountStatus", "rolesAssigned", "profilesAssigned", "passwordReturned"],
  "lockSu01User": ["username", "action", "verified", "lockStatus", "accountStatus", "passwordReturned"],
  "unlockSu01User": ["username", "action", "verified", "lockStatus", "accountStatus", "passwordReturned"],
  "resetSu01UserPassword": ["username", "action", "verified", "passwordState", "mustChangeAtNextLogon", "passwordReturned"]
}
```

Security model kept for every step: dedicated sidecar and token, deny by default,
username prefix enforced in the sidecar, exact operation-specific confirmation, no
AI-tool use, no roles or profiles, and **no password ever enters n8n**. The reset
applies the sidecar-held initial password and SAP forces a change at next logon;
delivering that password to the person is an operated process outside n8n.
