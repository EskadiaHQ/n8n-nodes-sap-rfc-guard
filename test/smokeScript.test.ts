import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const smokeScript = readFileSync(
	new URL('../jco-sidecar/scripts/smoke-test.sh', import.meta.url),
	'utf8',
);

describe('operated smoke-test contract', () => {
	it('sends the same read-only mode and client context as the n8n node', () => {
		assert.match(smokeScript, /X-RFC-Guard-Mode: read-only/);
		assert.match(smokeScript, /"client":"n8n-sap-rfc-guard","readOnly":true/);
	});
});
