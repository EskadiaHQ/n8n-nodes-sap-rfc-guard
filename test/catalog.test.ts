import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { catalogRequest } from '../nodes/SapRfcGuard/client';
import {
	assertCatalogCredential,
	assertCatalogFunctionAllowed,
	normalizeCatalogFunction,
	parseCatalogFunctions,
	validateGovernanceConfiguration,
} from '../nodes/SapRfcGuard/governance';
import { sanitizeCatalogExecution, sanitizeCatalogList } from '../nodes/SapRfcGuard/response';
import type { RfcGuardRequestOptions, SapRfcGuardCredentials } from '../nodes/SapRfcGuard/types';

const credentials: SapRfcGuardCredentials = {
	baseUrl: 'https://rfc.example.com/',
	apiToken: 'test-'.repeat(8),
	allowedOperations: 'listCompanyCodes',
	dataFieldPoliciesJson: '{"listCompanyCodes":["companyCode"]}',
	rejectUnauthorized: true,
	connectionTimeout: 15000,
	requestTimeout: 30000,
	maxRows: 100,
	maxRequestBytes: 65536,
	maxResponseBytes: 262144,
	allowFunctionCatalog: true,
	catalogFunctions: 'BAPI_PO_GETDETAIL1, bapi_vendor_getdetail\n/ABC/BAPI_X',
};

const meta = {
	source: 'sap-jco',
	syntheticData: false,
	readOnly: true,
	write: false,
	function: 'BAPI_PO_GETDETAIL1',
	correlationId: 'trace-1',
	backend: { systemId: 'A4H', client: '250', host: 'internal.example', release: '816' },
};

describe('approved function catalog', () => {
	it('is deny-by-default in the credential', () => {
		assert.throws(() => assertCatalogCredential({ ...credentials, allowFunctionCatalog: false }), /does not enable/);
		assert.throws(() => assertCatalogCredential({ ...credentials, catalogFunctions: '' }), /is empty/);
		assert.throws(
			() => assertCatalogCredential({ ...credentials, sidecarMode: 'userProvisioning' }),
			/not available on user-provisioning/,
		);
		const allowed = assertCatalogCredential(credentials);
		assert.deepEqual([...allowed], ['BAPI_PO_GETDETAIL1', 'BAPI_VENDOR_GETDETAIL', '/ABC/BAPI_X']);
		assert.throws(() => assertCatalogFunctionAllowed('RFC_READ_TABLE', allowed), /not listed/);
		assert.throws(() => parseCatalogFunctions('BAPI_A BAPI_A'), /duplicate/);
		assert.throws(() => normalizeCatalogFunction('BAPI;DROP'), /approved SAP function/);
		validateGovernanceConfiguration(credentials);
	});

	it('surfaces the sidecar code and details instead of a bare HTTP status', async () => {
		let captured: RfcGuardRequestOptions | undefined;
		await assert.rejects(
			catalogRequest(credentials, async (options) => {
				captured = options;
				return {
					statusCode: 400,
					body: {
						error: 'PARAMETER_UNKNOWN',
						message: 'BAPI_PO_GETDETAIL1 has no parameter PURCHASE_ORDER. Did you mean PURCHASEORDER?',
						details: { parameter: 'PURCHASE_ORDER' },
					},
				};
			}, 'POST', '/v1/catalog/functions/BAPI_PO_GETDETAIL1/execute', 'trace-1', { function: 'BAPI_PO_GETDETAIL1' }),
			/RFC Guard PARAMETER_UNKNOWN: .*Did you mean PURCHASEORDER\? Details: \{"parameter":"PURCHASE_ORDER"\}/,
		);
		assert.equal(captured?.ignoreHttpStatusErrors, true);
		assert.equal(captured?.headers['X-RFC-Guard-Mode'], 'read-only');
		assert.equal(captured?.timeout, 30000);
		assert.equal(captured?.headers.Authorization, `Bearer ${credentials.apiToken}`);
	});

	it('accepts only read-only SAP JCo evidence for the requested function and drops the host', () => {
		const response = {
			function: 'BAPI_PO_GETDETAIL1',
			outcome: 'SUCCESS',
			messages: [],
			exports: { POHEADER: { PO_NUMBER: '4500000008' } },
			tables: {},
			meta,
		};
		const output = sanitizeCatalogExecution(response, 'BAPI_PO_GETDETAIL1', 'trace-1', true);
		assert.deepEqual((output.meta as Record<string, unknown>).backend, { systemId: 'A4H', client: '250', release: '816' });
		assert.throws(
			() => sanitizeCatalogExecution({ ...response, meta: { ...meta, syntheticData: true } }, 'BAPI_PO_GETDETAIL1', 't', true),
			/not evidence from SAP JCo/,
		);
		assert.throws(
			() => sanitizeCatalogExecution({ ...response, meta: { ...meta, write: true } }, 'BAPI_PO_GETDETAIL1', 't', true),
			/read-only/,
		);
		assert.throws(
			() => sanitizeCatalogExecution(response, 'BAPI_VENDOR_GETDETAIL', 't', true),
			/does not match/,
		);
		assert.equal(sanitizeCatalogExecution(response, 'BAPI_PO_GETDETAIL1', 't', false).meta, undefined);
	});

	it('keeps pagination from the sidecar listing', () => {
		const listing = sanitizeCatalogList({
			functions: [{ name: 'BAPI_PO_GETDETAIL1', title: 'Read purchase order', secret: 'x' }],
			page: { nextCursor: 20 },
			policySha256: 'abc',
		});
		assert.equal(listing.nextCursor, 20);
		assert.deepEqual(listing.functions[0], { name: 'BAPI_PO_GETDETAIL1', title: 'Read purchase order' });
	});
});
