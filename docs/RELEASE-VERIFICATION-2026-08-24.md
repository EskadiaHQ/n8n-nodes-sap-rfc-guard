# RFC Guard release verification — 2026-08-24

## Outcome

`n8n-nodes-sap-rfc-guard@0.4.13` is published under the npm `latest` tag and
GitHub release `v0.4.13`. Pull request #12 was merged into `main` at commit
`1f57db6`.

## Security changes

- Credential and runtime connection tests now reject degraded, missing, or
  unknown sidecar health states. A capability declaration alone cannot make an
  unhealthy endpoint appear connected.
- Both read-only and user-provisioning health contracts fail closed.
- API tokens must contain at least 32 bytes, matching the IDoc Guard baseline.
- Existing shorter tokens must be rotated in the sidecar and n8n credential
  before upgrading.
- Response projection, secret redaction, request/response byte limits,
  operation allowlists, AI-tool restrictions, and item linking remain intact.

## Verification evidence

- Node tests: 24 passed.
- Contract-sidecar tests: 3 passed.
- Community-node lint: passed.
- TypeScript/package build: passed.
- Package dry run: passed; 66 files, approximately 428 kB compressed.
- Production dependency audit: 0 vulnerabilities.
- Clean registry installation: version `0.4.13` resolved and the compiled node
  artifact was present.
- Pull-request CI: [run 32666299611](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/32666299611).
- Post-merge CI: [run 32723159355](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/32723159355).
- Trusted npm publication: [run 32723269228](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/actions/runs/32723269228).
- GitHub release: [v0.4.13](https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard/releases/tag/v0.4.13).

The CI runs include the Java 21 JCo sidecar test/package job. npm reports
integrity `sha512-4b5A9Ib56RcbJBjI/fKM/fBeyca3Ix5qCQSMH8kAH56vQfpBcooCPwJhHsUpZlfzqzWSytCOXpcN0YUeeFSnLg==`
and SHA-1 `fa67afb1d0553dfa3fe3dd6f92f413e8cea10b9f` for the published tarball.

## Portfolio review

The same review covered HANA Guard, OData Guard, RFC Guard, and IDoc Guard.
HANA Guard passed 87 tests and OData Guard passed 35 tests without an equivalent
health-validation defect, so neither received a speculative code change. Across
all four packages, 177 tests passed and every production-only dependency audit
reported zero vulnerabilities.

Development-toolchain alerts remain separate from the published runtime and are
tracked in issue #5. They must not be suppressed with dependency overrides that
violate the n8n community-node rules.
