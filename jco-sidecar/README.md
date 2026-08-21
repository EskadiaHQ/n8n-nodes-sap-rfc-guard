# SAP RFC Guard JCo sidecar

Production-oriented, read-only SAP RFC sidecar for the Logali SAP RFC Guard n8n
node. The public API accepts only four business aliases; technical RFC names are
not accepted from callers.

## Required proprietary runtime

Download the supported SAP Java Connector 3 package for Linux x86-64 from the
SAP Software Download Center. Place these files on the server with mode `0640`
or stricter:

- `/srv/sap-rfc-guard/vendor/sap-jco/sapjco3.jar`
- `/srv/sap-rfc-guard/vendor/sap-jco/libsapjco3.so`

The files are mounted read-only and are intentionally excluded from this
repository and from the container image.

## SAP functions

- `listSu01Users` uses `BAPI_USER_GETLIST` and governed detail reads.
- `getSu01UserDetail` uses `BAPI_USER_GET_DETAIL`.
- `listSu01RiskAccounts` classifies the governed result in the sidecar.
- `summarizeSu01Accounts` aggregates the governed result in the sidecar.

The runtime never exposes password hashes, SNC identities, profiles, roles or
arbitrary BAPI fields. The configured technical user still needs the relevant
business display authorizations in addition to narrowly scoped `S_RFC` access.
Each SAP operation is isolated in a bounded worker pool and must complete within
`RFC_GUARD_REQUEST_TIMEOUT_SECONDS` (30 seconds by default); a stalled call
returns `SAP_READ_TIMEOUT` instead of holding the n8n request indefinitely.

## Build and test

```bash
docker build -t logali-sap-rfc-guard-jco:0.1.0 .
```

The image build runs the unit tests. It does not need the proprietary JCo files;
they are checked only at runtime. Missing JCo, missing SAP configuration or an
unreachable backend keeps health in `503 unavailable` and never falls back to
synthetic data.

## Deployment

1. Copy `.env.example` to `/srv/sap-rfc-guard/secrets/sap-rfc-guard.env` with mode
   `0600`; replace every placeholder outside version control.
2. Copy and review `compose.example.yml` in a separate stack directory.
3. Build the image and start the stack.
4. Verify authenticated `/v1/health` from the n8n container.
5. Create a separate n8n credential; do not overwrite the fixture credential.

Keep the existing synthetic contract container available for regression tests.
The JCo sidecar must have no published host ports.

The real sidecar requires TLS. Generate an internal CA and a PKCS#12 server
keystore whose SAN contains `sap-rfc-guard-jco`, mount the keystore read-only,
and add the CA certificate to n8n through `NODE_EXTRA_CA_CERTS`. Do not disable
certificate validation for the real credential.

`n8n-ca.compose.override.yml` contains the reviewed CA mount and environment
setting. It is a template, not an instruction to restart n8n immediately:
first obtain a fresh n8n backup, validate the combined Compose configuration,
start the sidecar, verify its health, and only then recreate n8n with the CA
override.

## Fail-closed readiness check

Run `scripts/deploy-server.sh` as root only when deployment is intended. Before
starting a container it validates that both JCo files exist, their architecture
and JAR contents are correct, the secret has no placeholders, and the Compose
file is valid. A missing dependency exits non-zero and leaves the synthetic
contract service untouched.
