import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	executeApprovedOperation,
	executeApprovedWriteOperation,
	testSidecarConnection,
} from '../nodes/SapRfcGuard/client';
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
		assert.equal(captured?.headers.Authorization, `Bearer ${credentials.apiToken}`);
	});

	it('uses the non-XSUAA token header for the SAP BTP sidecar', async () => {
		let captured: RfcGuardRequestOptions | undefined;
		await testSidecarConnection(
			{ ...credentials, headerMode: 'xRfcGuardToken' },
			async (options) => {
				captured = options;
				return { status: 'ok' };
			},
			'trace-btp',
		);
		assert.equal(captured?.headers['X-RFC-Guard-Token'], credentials.apiToken);
		assert.equal(captured?.headers.Authorization, undefined);
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

	it('uses a separate provisioning contract and never sends the new user password', async () => {
		let captured: RfcGuardRequestOptions | undefined;
		await executeApprovedWriteOperation(
			{ ...credentials, sidecarMode: 'userProvisioning', allowUserCreation: true },
			async (options) => {
				captured = options;
				return { data: { created: true } };
			},
			'createSu01CommunicationUser',
			{ username: 'N8N_DEMO_01', firstName: 'n8n', lastName: 'Demo User', validDays: 1 },
			'trace-write-1',
			'CREATE N8N_DEMO_01',
		);
		assert.equal(captured?.headers['X-RFC-Guard-Mode'], 'user-provisioning');
		assert.deepEqual(captured?.body?.context, {
			client: 'n8n-sap-rfc-guard',
			readOnly: false,
			write: true,
			confirmation: 'CREATE N8N_DEMO_01',
		});
		assert.equal(JSON.stringify(captured).toLowerCase().includes('password'), false);
	});
});
