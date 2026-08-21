import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	allowedDataFieldsForOperation,
	assertOperationAllowed,
	assertOperationId,
	normalizeBaseUrl,
	parseAllowedOperations,
	parseDataFieldPolicies,
	parseParametersJson,
	validateGovernanceConfiguration,
} from '../nodes/SapRfcGuard/governance';

const credentials = {
	baseUrl: 'https://rfc.example.com',
	apiToken: 'not-a-real-secret',
	allowedOperations: 'listSu01Users, getSu01UserDetail',
	dataFieldPoliciesJson:
		'{"listSu01Users":["username","accountStatus"],"getSu01UserDetail":["username","email"]}',
	rejectUnauthorized: true,
	connectionTimeout: 15000,
	requestTimeout: 30000,
	maxRows: 100,
	maxRequestBytes: 65536,
	maxResponseBytes: 262144,
};

describe('operation governance', () => {
	it('uses business aliases and blocks technical RFC names', () => {
		assert.equal(assertOperationId('listSu01Users'), 'listSu01Users');
		assert.throws(() => assertOperationId('BAPI_USER_GETLIST'), /business alias/);
		assert.throws(() => assertOperationId('Z_GET_USERS'), /business alias/);
	});

	it('denies any operation outside the credential allowlist', () => {
		const allowed = parseAllowedOperations('listSu01Users, getSu01UserDetail');
		assert.doesNotThrow(() => assertOperationAllowed('listSu01Users', allowed));
		assert.throws(
			() => assertOperationAllowed('createPurchaseOrder', allowed),
			/not allowed/,
		);
	});
});

describe('data field policies', () => {
	it('requires a field policy for every allowed operation', () => {
		assert.throws(
			() =>
				validateGovernanceConfiguration({
					...credentials,
					dataFieldPoliciesJson: '{"listSu01Users":["username"]}',
				}),
			/No data field policy is configured/,
		);
	});

	it('returns only the approved data fields for one operation', () => {
		const policies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
		assert.deepEqual(allowedDataFieldsForOperation('listSu01Users', policies), [
			'username',
			'accountStatus',
		]);
	});
});

describe('connection and input validation', () => {
	it('requires HTTPS unless local HTTP was explicitly enabled', () => {
		assert.equal(normalizeBaseUrl('https://rfc.example.com/'), 'https://rfc.example.com');
		assert.throws(() => normalizeBaseUrl('http://rfc.example.com'), /must use HTTPS/);
		assert.equal(
			normalizeBaseUrl('http://127.0.0.1:8080', true),
			'http://127.0.0.1:8080',
		);
	});

	it('accepts only an object as Parameters JSON', () => {
		assert.deepEqual(parseParametersJson('{"client":"100"}'), { client: '100' });
		assert.throws(() => parseParametersJson('[]'), /must be an object/);
	});
});
