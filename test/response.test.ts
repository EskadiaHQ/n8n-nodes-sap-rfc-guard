import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	sanitizeExecutionResponse,
	sanitizeHealthResponse,
	sanitizeProvisioningHealthResponse,
	sanitizeWriteResponse,
} from '../nodes/SapRfcGuard/response';

describe('sidecar response governance', () => {
	it('requires an explicit read-only health capability', () => {
		assert.deepEqual(
			sanitizeHealthResponse({
				status: 'ok',
				service: 'sap-rfc-sidecar',
				version: '1.0.0',
				backend: { systemId: 'E6D', client: '100', host: 'private.example', release: '7.50' },
				capabilities: {
					readOnly: true,
					operations: ['listSu01Users', 'BAPI_USER_GETLIST'],
				},
			}),
			{
				connected: true,
				status: 'ok',
				service: 'sap-rfc-sidecar',
				version: '1.0.0',
				readOnly: true,
				operations: ['listSu01Users'],
				backend: { systemId: 'E6D', client: '100', release: '7.50' },
			},
		);
		assert.throws(
			() => sanitizeHealthResponse({ status: 'ok', capabilities: { readOnly: false } }),
			/does not advertise read-only/,
		);
		assert.throws(
			() =>
				sanitizeHealthResponse({
					status: 'degraded',
					capabilities: { readOnly: true, operations: ['listSu01Users'] },
				}),
			/did not report a healthy status/,
		);
	});

	it('projects fields, enforces the row limit, and adds safe metadata', () => {
		const rows = sanitizeExecutionResponse(
			{
				operation: 'listSu01Users',
				correlationId: 'trace-1',
				data: [
					{ username: 'USER1', accountStatus: 'active', passwordHash: 'secret-1' },
					{ username: 'USER2', accountStatus: 'locked', passwordHash: 'secret-2' },
				],
				meta: {
					readOnly: true,
					source: 'sap-jco',
					syntheticData: false,
					backend: { systemId: 'S4D', client: '100', host: 'private.example', release: '2025' },
					durationMs: 12,
				},
			},
			'listSu01Users',
			'trace-1',
			['username', 'accountStatus'],
			1,
			100,
			true,
		);
		assert.deepEqual(rows, [
			{
				username: 'USER1',
				accountStatus: 'active',
				_rfc: {
					operation: 'listSu01Users',
					correlationId: 'trace-1',
					rowCount: 1,
					rowLimit: 1,
					truncated: true,
					readOnly: true,
					source: 'sap-jco',
					syntheticData: false,
					backend: { systemId: 'S4D', client: '100', release: '2025' },
					durationMs: 12,
				},
			},
		]);
		assert.equal('passwordHash' in rows[0], false);
		assert.equal(JSON.stringify(rows).includes('private.example'), false);
	});

	it('rejects malformed backend evidence instead of forwarding it', () => {
		assert.throws(
			() =>
				sanitizeExecutionResponse(
					{
						operation: 'listSu01Users',
						data: [],
						meta: {
							readOnly: true,
							backend: { systemId: 'S4D', client: '../', release: '2025' },
						},
					},
					'listSu01Users',
					'trace-1',
					['username'],
					10,
					10,
					true,
				),
			/endpoint client is invalid|backend client is invalid/,
		);
	});

	it('rejects a response without read-only attestation or matching correlation', () => {
		assert.throws(
			() =>
				sanitizeExecutionResponse(
					{ operation: 'listSu01Users', data: [], meta: { readOnly: false } },
					'listSu01Users',
					'trace-1',
					['username'],
					10,
					10,
					true,
				),
			/readOnly=true/,
		);
		assert.throws(
			() =>
				sanitizeExecutionResponse(
					{
						operation: 'listSu01Users',
						correlationId: 'other-trace',
						data: [],
						meta: { readOnly: true },
					},
					'listSu01Users',
					'trace-1',
					['username'],
					10,
					10,
					true,
				),
			/correlation ID does not match/,
		);
	});

	it('accepts only attested provisioning health and projects a governed write result', () => {
		assert.deepEqual(
			sanitizeProvisioningHealthResponse({
				status: 'ok',
				service: 'sap-rfc-guard-jco-provisioning',
				version: '0.2.0',
				backend: { systemId: 'A4H', client: '250', release: '754' },
				capabilities: {
					readOnly: false,
					writeEnabled: true,
					operations: ['createSu01CommunicationUser'],
				},
			}),
			{
				connected: true,
				status: 'ok',
				service: 'sap-rfc-guard-jco-provisioning',
				version: '0.2.0',
				readOnly: false,
				writeEnabled: true,
				operations: ['createSu01CommunicationUser'],
				backend: { systemId: 'A4H', client: '250', release: '754' },
			},
		);
		const result = sanitizeWriteResponse(
			{
				operation: 'createSu01CommunicationUser',
				correlationId: 'trace-write-1',
				data: [{ username: 'N8N_DEMO_01', created: true, internalSecret: 'blocked' }],
				meta: {
					readOnly: false,
					write: true,
					source: 'sap-jco',
					syntheticData: false,
					backend: { systemId: 'A4H', client: '250', release: '754' },
				},
			},
			'createSu01CommunicationUser',
			'trace-write-1',
			['username', 'created'],
		);
		assert.equal(result.username, 'N8N_DEMO_01');
		assert.equal(result.created, true);
		assert.equal('internalSecret' in result, false);
		assert.deepEqual(result._rfc, {
			operation: 'createSu01CommunicationUser',
			correlationId: 'trace-write-1',
			readOnly: false,
			writeOperation: true,
			source: 'sap-jco',
			syntheticData: false,
			backend: { systemId: 'A4H', client: '250', release: '754' },
		});
		assert.throws(
			() => sanitizeProvisioningHealthResponse({ status: 'ok', capabilities: { readOnly: true } }),
			/does not advertise user-provisioning/,
		);
		assert.throws(
			() =>
				sanitizeProvisioningHealthResponse({
					status: 'degraded',
					capabilities: {
						readOnly: false,
						writeEnabled: true,
						operations: ['createSu01CommunicationUser'],
					},
				}),
			/did not report a healthy status/,
		);
	});
});
