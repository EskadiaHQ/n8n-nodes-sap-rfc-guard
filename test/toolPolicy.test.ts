import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { assertAiToolAllowed } from '../nodes/SapRfcGuard/toolPolicy';
import type { SapRfcGuardCredentials } from '../nodes/SapRfcGuard/types';

const credentials = {
	baseUrl: 'https://rfc.example.com',
	apiToken: 'not-a-real-secret',
	allowedOperations: 'listSu01Users',
	dataFieldPoliciesJson: '{"listSu01Users":["username"]}',
	connectionTimeout: 15000,
	requestTimeout: 30000,
	maxRows: 100,
	maxRequestBytes: 65536,
	maxResponseBytes: 262144,
} satisfies SapRfcGuardCredentials;

describe('AI tool policy', () => {
	it('allows the normal workflow node without an AI opt-in', () => {
		assert.doesNotThrow(() =>
			assertAiToolAllowed('n8n-nodes-sap-rfc-guard.sapRfcGuard', credentials),
		);
	});

	it('blocks the tool variant by default and permits an explicit opt-in', () => {
		assert.throws(
			() =>
				assertAiToolAllowed('n8n-nodes-sap-rfc-guard.sapRfcGuardTool', credentials),
			/does not allow/,
		);
		assert.doesNotThrow(() =>
			assertAiToolAllowed('n8n-nodes-sap-rfc-guard.sapRfcGuardTool', {
				...credentials,
				allowAiTool: true,
			}),
		);
	});
});
