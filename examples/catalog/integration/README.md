# Isolated integration test (logali-prod, 2026-10-31)

Real SAP, no mocks. Everything runs next to the student n8n without touching it.

- `CatalogProbe.java`: read-only JCo probe used to discover which metadata RFCs each
  technical user may call (compile against the sidecar jar, run in the JCo runtime image).
- `catcall.sh`: calls a test sidecar from a throwaway curl container on the internal test
  network; the token stays on the server.
- `negative-cases.py`: prints the direct-sidecar matrix (28 cases) as shell lines.
- `n8n-setup.mjs`, `n8n-import.mjs`, `n8n-last.mjs`: owner/API key setup, test credentials and
  workflows, and execution readout inside the disposable n8n 2.43.7.

Topology used: network `c415-catalog-test-net` (internal, 10.215.31.0/28); sidecars
`c415-rfc-catalog-test` (N8N_LOGALI, forced `read-only`) and `c415-rfc-catalog-t422`
(T422_RFC), image `logali-sap-rfc-guard-jco:0.7.0-catalog-rc1`; n8n `c415-n8n-catalog-test`
with this package as a community node. Test token, TLS key and owner login are generated on
the server under `/tmp/c415-catalog-test/` (root only) and never leave it.
