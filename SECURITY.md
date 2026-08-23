# Security policy

Do not report credentials, tokens, SAP destinations, internal hosts, or production payloads in a
public issue. Rotate an exposed secret immediately and use a private security channel.

SAP RFC Guard does not accept technical function-module names from workflows. New business
operations belong in the sidecar allowlist and require input validation, SAP authorization,
response sanitization, tests, and an explicit read/write classification.

Generate a random API token containing at least 32 bytes and rotate it through a
coordinated sidecar/n8n credential change.

Use GitHub's private security-advisory form for vulnerabilities. Do not attach
real workflow exports, traces, environment files or SAP user records to a
public issue.
