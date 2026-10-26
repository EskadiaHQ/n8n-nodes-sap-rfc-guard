import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
	allowedDataFieldsForOperation,
	assertBusinessOperationsCredential,
	assertCreateConfirmation,
	assertGoodsReceiptConfirmation,
	assertInvoiceCreateConfirmation,
	assertInvoiceParkConfirmation,
	assertOperationAllowed,
	assertOperationId,
	normalizeBaseUrl,
	parseAllowedOperations,
	parseDataFieldPolicies,
	parseParametersJson,
	validateGovernanceConfiguration,
	USER_CREATE_OPERATION,
	INVOICE_CREATE_OPERATION,
	INVOICE_PARK_OPERATION,
	GOODS_RECEIPT_CREATE_OPERATION,
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

	it('requires a dedicated opted-in credential and target-bound confirmation for creation', () => {
		const provisioning = {
			...credentials,
			sidecarMode: 'userProvisioning' as const,
			allowUserCreation: true,
			allowedOperations: USER_CREATE_OPERATION,
			dataFieldPoliciesJson: `{"${USER_CREATE_OPERATION}":["username","created"]}`,
		};
		assert.doesNotThrow(() => validateGovernanceConfiguration(provisioning));
		assert.throws(
			() => validateGovernanceConfiguration({ ...provisioning, allowUserCreation: false }),
			/explicit credential opt-in/,
		);
		assert.throws(
			() => validateGovernanceConfiguration({ ...provisioning, allowAiTool: true }),
			/cannot be enabled for AI tools/,
		);
		assert.equal(
			assertCreateConfirmation('n8n_demo_01', 'CREATE N8N_DEMO_01'),
			'N8N_DEMO_01',
		);
		assert.throws(
			() => assertCreateConfirmation('N8N_DEMO_01', 'CREATE OTHER_USER'),
			/Confirmation must be exactly/,
		);
	});

	it('isolates real invoice creation behind business mode and an exact reference confirmation', () => {
		const business = {
			...credentials,
			sidecarMode: 'businessOperations' as const,
			allowIncomingInvoiceCreation: true,
			allowedOperations: `getPurchaseOrderDetail, ${INVOICE_CREATE_OPERATION}`,
			dataFieldPoliciesJson:
				`{"getPurchaseOrderDetail":["purchaseOrder"],"${INVOICE_CREATE_OPERATION}":["invoiceDocument","fiscalYear","created","verified"]}`,
		};
		assert.doesNotThrow(() => validateGovernanceConfiguration(business));
		assert.doesNotThrow(() => assertBusinessOperationsCredential(business));
		assert.equal(
			assertInvoiceCreateConfirmation('wb030926-inv01', 'CREATE INVOICE WB030926-INV01'),
			'WB030926-INV01',
		);
		assert.throws(
			() => assertInvoiceCreateConfirmation('WB030926-INV01', 'CREATE INVOICE OTHER'),
			/Confirmation must be exactly/,
		);
		const parking = {
			...business,
			allowedOperations: `getPurchaseOrderDetail, ${INVOICE_PARK_OPERATION}`,
			dataFieldPoliciesJson:
				`{"getPurchaseOrderDetail":["purchaseOrder"],"${INVOICE_PARK_OPERATION}":["invoiceDocument","fiscalYear","parked","verified"]}`,
		};
		assert.doesNotThrow(() => validateGovernanceConfiguration(parking));
		assert.equal(
			assertInvoiceParkConfirmation('wb030926-inv02', 'PARK INVOICE WB030926-INV02'),
			'WB030926-INV02',
		);
		assert.throws(
			() => assertInvoiceParkConfirmation('WB030926-INV02', 'CREATE INVOICE WB030926-INV02'),
			/Confirmation must be exactly/,
		);
		assert.throws(
			() => validateGovernanceConfiguration({ ...business, allowIncomingInvoiceCreation: false }),
			/explicit invoice-creation opt-in/,
		);
		assert.throws(
			() => validateGovernanceConfiguration({ ...business, allowAiTool: true }),
			/cannot be enabled for AI tools/,
		);
		const goodsReceipt = {
			...credentials,
			sidecarMode: 'businessOperations' as const,
			allowGoodsReceiptCreation: true,
			allowedOperations: `getPurchaseOrderDetail, ${GOODS_RECEIPT_CREATE_OPERATION}`,
			dataFieldPoliciesJson:
				`{"getPurchaseOrderDetail":["purchaseOrder"],"${GOODS_RECEIPT_CREATE_OPERATION}":["materialDocument","materialDocumentYear","created","verified"]}`,
		};
		assert.doesNotThrow(() => validateGovernanceConfiguration(goodsReceipt));
		assert.doesNotThrow(() => assertBusinessOperationsCredential(goodsReceipt, 'goodsReceipt'));
		assert.equal(
			assertGoodsReceiptConfirmation('wb030926-gr01', 'POST GOODS RECEIPT WB030926-GR01'),
			'WB030926-GR01',
		);
		assert.throws(
			() => assertGoodsReceiptConfirmation('WB030926-GR01', 'POST GOODS RECEIPT OTHER'),
			/Confirmation must be exactly/,
		);
		assert.throws(
			() => validateGovernanceConfiguration({ ...goodsReceipt, allowGoodsReceiptCreation: false }),
			/explicit goods-receipt opt-in/,
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
