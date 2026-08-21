import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { sanitizeExecutionResponse, sanitizeHealthResponse } from '../nodes/SapRfcGuard/response';

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
});
