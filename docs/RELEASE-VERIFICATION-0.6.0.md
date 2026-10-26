# RFC Guard release verification — 0.6.0 (2026-10-26)

## Outcome

`0.6.0` reunifies the two lines of this package. The publication evidence is
added below once the trusted npm workflow has run.

## Why this release exists

Two lines had diverged since August 2026:

| Line | Content | Status before 0.6.0 |
|---|---|---|
| Operated 0.4.13 and 0.4.14 | Incoming invoice create and park, purchase-order goods receipt, business-operations sidecar mode | Installed in the Logali n8n instances, never published |
| Published 0.4.13 to 0.5.x | Fail-closed health, 32-byte token minimum, user lock, unlock and password reset, JCo lock-flag and stateful-commit fixes | On npm `latest` |

Installing `0.5.0` over the operated `0.4.14` removed the business operations, and
staying on `0.4.14` kept the business line without the security hardening.
`0.6.0` carries both lines plus the scoped retry for the operated legacy
provisioner that was patched into n8n DEV on 2026-10-23.

## Functional changes

- One node serves read-only, business-operations and user-provisioning credentials.
  Read-only credentials reject every write alias. Business credentials cannot allow
  a user-administration alias, and provisioning credentials cannot allow a business write.
- Business credentials now fail closed on an unhealthy sidecar and need a token of at
  least 32 bytes, like the other modes.
- One JCo sidecar build serves the three modes. Each write is accepted only in its own
  mode, with its own `X-RFC-Guard-Mode` header and exact confirmation.
- The legacy provisioner retry stays scoped to one HTTPS host and port, user-provisioning
  credentials only. It allows at most two attempts within the original timeout, retries
  user creation only when the request provably never left n8n, disables redirects and
  writes one secret-free `SAP_PROVISIONER_FAILURE` log line per final failure.
- The synthetic contract fixture now defaults to a 34-byte token. The old 26-byte default
  was rejected by every hardened release.

## Verification evidence

- Node tests: 73 passed, including 39 for the scoped legacy retry.
- Contract fixture: 7 passed. End-to-end synthetic lifecycle through the node: 2 passed.
- JCo sidecar (Java 21): 38 passed, including a test that keeps user-administration
  and business writes on separate sidecar modes.
- Community-node lint, TypeScript build and production dependency audit: passed,
  0 vulnerabilities.
- Package dry run: 72 files, about 444 kB compressed.
- The compiled `client.js` is byte-identical to the patched client operated in n8n DEV
  since 2026-10-23, SHA-256 `6e22f54d024091388881905f61b331cbd4bca52e10ddb2f11fd3f666ee8eae10`.

### Real credentials, before installation

The release was loaded next to the installed `0.4.14` in the two Logali n8n
instances, without installing it, and run against all 16 real RFC Guard credentials.
For each credential the check ran the governance validation, the credential test
and, where allowed, a `listCompanyCodes` read.

- 14 credentials gave the same result with both versions.
- 2 credentials had a token shorter than 32 bytes and are now rejected before any
  request. One was the synthetic fixture, rotated to the new default. The other
  already failed with HTTP 401 on `0.4.14`.
- Every resource and operation used by the 177 RFC Guard nodes in both instances
  exists in `0.6.0`.

### n8n DEV installation

- `0.6.0` replaced `0.4.14` in n8n DEV after draining executions and a graceful
  stop. The 12 waiting executions were preserved and the students instance was
  not touched.
- After the restart every credential gave the same result as in the pre-check.
- The user-administration sidecar was attached to DEV through a dedicated internal
  network, with TLS verified against the internal CA.
- End to end against SAP A4H client 250, from the installed node, on a new
  prefix-restricted Communication user:

| Step | Result |
|---|---|
| Create | `created=true`, `verified=true`, no roles or profiles, `passwordReturned=false` |
| Lock with the `UNLOCK` confirmation | Rejected by the node before any request |
| Lock | `LockedByAdministrator`, verified by read-back |
| Unlock | `Unlocked`, verified by read-back |
| Reset password | `passwordState=initial`, `mustChangeAtNextLogon=true`, `passwordReturned=false` |
| Lock a user outside the prefix | Rejected by the sidecar with `USERNAME_PREFIX_INVALID` |

## Not verified

- The business writes (invoice create and park, goods receipt) were not executed
  again for this release. Their node and sidecar code is the operated `0.4.14` code,
  and the read paths of the business credential were verified.
- The operated read and business sidecars still run builds older than this release.
  They keep working, but the lock-state fix of JCo sidecar 0.5.1 reaches them only
  when they are rebuilt from this release.
