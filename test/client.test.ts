import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { executeApprovedOperation, testSidecarConnection } from '../nodes/SapRfcGuard/client';
import type { RfcGuardRequestOptions, SapRfcGuardCredentials } from '../nodes/SapRfcGuard/types';

const credentials: SapRfcGuardCredentials = {
	baseUrl: 'https://rfc.example.com/',
	apiToken: 'not-a-real-secret',
	allowedOperations: 'listSu01Users',
	dataFieldPoliciesJson: '{"listSu01Users":["username"]}',
	rejectUnauthorized: true,
	connectionTimeout: 15000,
	requestTimeout: 30000,
	maxRows: 100,
	maxRequestBytes: 65536,
	maxResponseBytes: 262144,
};

describe('sidecar HTTP contract', () => {
	it('calls the fixed health endpoint with read-only evidence', async () => {
		let captured: RfcGuardRequestOptions | undefined;
		await testSidecarConnection(
			credentials,
			async (options) => {
				captured = options;
				return { status: 'ok' };
			},
			'trace-health',
		);
		assert.equal(captured?.url, 'https://rfc.example.com/v1/health');
		assert.equal(captured?.method, 'GET');
		assert.equal(captured?.headers['X-RFC-Guard-Mode'], 'read-only');
	});

	it('calls only the governed operation route and never sends an SAP password', async () => {
		let captured: RfcGuardRequestOptions | undefined;
		await executeApprovedOperation(
			credentials,
			async (options) => {
				captured = options;
				return { data: [] };
			},
			'listSu01Users',
			{ client: '100' },
			'trace-1',
		);
		assert.equal(
			captured?.url,
			'https://rfc.example.com/v1/operations/listSu01Users/execute',
		);
		assert.deepEqual(captured?.body, {
			operation: 'listSu01Users',
			parameters: { client: '100' },
			context: { client: 'n8n-sap-rfc-guard', readOnly: true },
		});
		assert.equal(JSON.stringify(captured).includes('SAP_PASSWORD'), false);
	});

	it('redacts the API token from transport errors', async () => {
		await assert.rejects(
			() =>
				testSidecarConnection(
					credentials,
					async () => {
						throw new Error(`Rejected token ${credentials.apiToken}`);
					},
					'trace-1',
				),
			(error: Error) =>
				error.message.includes('[REDACTED]') && !error.message.includes(credentials.apiToken),
		);
	});
});
