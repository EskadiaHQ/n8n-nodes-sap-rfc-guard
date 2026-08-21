# SAP RFC Guard contract fixture

This container is a local, synthetic test double for the `Logali SAP RFC Guard`
node. It does **not** connect to SAP and it does **not** implement RFC.

It exposes one health endpoint and four authenticated read-only business aliases:

- `GET /v1/health`
- `POST /v1/operations/listSu01Users/execute`
- `POST /v1/operations/getSu01UserDetail/execute`
- `POST /v1/operations/listSu01RiskAccounts/execute`
- `POST /v1/operations/summarizeSu01Accounts/execute`

The returned SU01-style records are fictitious, use `example.com` addresses, and
are marked with `meta.syntheticData: true`. Every other operation is rejected.

The development deployment is attached only to the n8n Docker network and does
not publish a host port. Its fixed token is a non-secret fixture value and must
never be reused for a real sidecar.
