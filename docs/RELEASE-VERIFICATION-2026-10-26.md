# RFC Guard release verification — 2026-10-26

## Outcome

`n8n-nodes-sap-rfc-guard@0.5.0` is published under the npm `latest` tag and
GitHub release `v0.5.0`. Pull request #20 was merged into `main` at commit
`0b80154`.

## Functional changes

- **User Administration** adds *Lock User* (`lockSu01User` → `BAPI_USER_LOCK`),
  *Unlock User* (`unlockSu01User` → `BAPI_USER_UNLOCK`) and *Reset Password*
  (`resetSu01UserPassword` → `BAPI_USER_CHANGE` with `PASSWORD`/`PASSWORDX`)
  next to *Create Communication User*. Each change commits and reads the user back.
- The synthetic contract fixture serves the four write aliases with
  `CONTRACT_MODE=user-provisioning`. `examples/su01/user-admin/` adds a synthetic
  lifecycle workflow and a real one, both inactive.

## Security properties kept

- The aliases are served only by the dedicated `user-provisioning` sidecar.
  Read-only credentials reject every write alias.
- The sidecar enforces its username prefix. A user outside the prefix fails with
  `USERNAME_PREFIX_INVALID`, and a missing user fails with `USER_NOT_FOUND`
  after `BAPI_USER_EXISTENCE_CHECK`.
- Each operation needs its own exact confirmation (`CREATE`, `LOCK`, `UNLOCK`,
  `RESET` + username). It is checked in both the node and the sidecar.
- Provisioning credentials cannot be used as AI tools.
- No password is sent to or returned by n8n. The reset applies the sidecar-held
  initial password and SAP forces a change at next logon.

## Verification evidence

- Node tests: 26 passed.
- Contract fixture: 7 passed, read-only and provisioning modes.
- End to end, the node runs the published synthetic example workflow against the
  provisioning fixture: 2 passed.
- JCo sidecar (Java 21): 33 passed.
- Community-node lint and TypeScript/package build: passed.
- Package dry run: passed; 70 files, approximately 432 kB compressed.
- Production dependency audit: 0 vulnerabilities.
- Pull-request CI: [run 40299505422](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/40299505422).
- Post-merge CI: [run 40300184237](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/40300184237).
- Trusted npm publication: [run 40300207513](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/40300207513).
- GitHub release: [v0.5.0](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/releases/tag/v0.5.0).

## Not verified

The lifecycle operations have not been executed against a real SAP system. The
real example stays inactive until an isolated provisioning sidecar, rebuilt from
this release, is operated against the lab system.

An operated sidecar built from an earlier release keeps rejecting the new aliases
with `OPERATION_NOT_ALLOWED`, so upgrading the node alone does not change any SAP
system.
