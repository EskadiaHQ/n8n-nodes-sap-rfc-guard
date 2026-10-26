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
	assertWriteConfirmation,
	assertOperationId,
	normalizeBaseUrl,
	parseAllowedOperations,
	parseDataFieldPolicies,
	parseParametersJson,
	userWriteOperationForNodeOperation,
	validateGovernanceConfiguration,
	USER_CREATE_OPERATION,
	USER_LOCK_OPERATION,
	USER_LOGON_RESET_OPERATION,
	USER_UNLOCK_OPERATION,
	INVOICE_CREATE_OPERATION,
	INVOICE_PARK_OPERATION,
	GOODS_RECEIPT_CREATE_OPERATION,
} from '../nodes/SapRfcGuard/governance';

const credentials = {
	baseUrl: 'https://rfc.example.com',
	apiToken: 'test-'.repeat(8),
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

	it('allows the lifecycle aliases only on an opted-in provisioning credential', () => {
		const lifecycle = [USER_LOGON_RESET_OPERATION, USER_LOCK_OPERATION, USER_UNLOCK_OPERATION];
		const provisioning = {
			...credentials,
			sidecarMode: 'userProvisioning' as const,
			allowUserCreation: true,
			allowedOperations: [USER_CREATE_OPERATION, ...lifecycle].join(', '),
			dataFieldPoliciesJson: JSON.stringify(
				Object.fromEntries(
					[USER_CREATE_OPERATION, ...lifecycle].map((operation) => [operation, ['username']]),
				),
			),
		};
		assert.doesNotThrow(() => validateGovernanceConfiguration(provisioning));
		assert.throws(
			() =>
				validateGovernanceConfiguration({
					...credentials,
					allowedOperations: 'listSu01Users, resetSu01UserPassword',
					dataFieldPoliciesJson:
						'{"listSu01Users":["username"],"resetSu01UserPassword":["username"]}',
				}),
			/read-only credential cannot allow a write operation/,
		);
		assert.throws(
			() =>
				validateGovernanceConfiguration({
					...provisioning,
					allowedOperations: `${USER_LOCK_OPERATION}, listSu01Users`,
					dataFieldPoliciesJson: `{"${USER_LOCK_OPERATION}":["username"],"listSu01Users":["username"]}`,
				}),
			/may allow only/,
		);
	});

	it('binds each lifecycle change to its own verb and the exact username', () => {
		assert.deepEqual(assertWriteConfirmation(USER_LOGON_RESET_OPERATION, 'n8n_demo_01', 'reset n8n_demo_01'), {
			username: 'N8N_DEMO_01',
			confirmation: 'RESET N8N_DEMO_01',
		});
		assert.equal(assertWriteConfirmation(USER_LOCK_OPERATION, 'N8N_DEMO_01', 'LOCK N8N_DEMO_01').confirmation, 'LOCK N8N_DEMO_01');
		assert.equal(assertWriteConfirmation(USER_UNLOCK_OPERATION, 'N8N_DEMO_01', 'UNLOCK N8N_DEMO_01').confirmation, 'UNLOCK N8N_DEMO_01');
		assert.throws(
			() => assertWriteConfirmation(USER_LOGON_RESET_OPERATION, 'N8N_DEMO_01', 'CREATE N8N_DEMO_01'),
			/Confirmation must be exactly RESET N8N_DEMO_01/,
		);
		assert.throws(() => assertWriteConfirmation('listSu01Users', 'N8N_DEMO_01', 'LIST N8N_DEMO_01'), /not a governed/);
		assert.equal(userWriteOperationForNodeOperation('resetPassword'), USER_LOGON_RESET_OPERATION);
		assert.throws(() => userWriteOperationForNodeOperation('deleteUser'), /Unsupported/);
	});

	it('keeps business-operation and user-administration aliases on separate credentials', () => {
		const business = {
			...credentials,
			sidecarMode: 'businessOperations' as const,
			allowIncomingInvoiceCreation: true,
			allowedOperations: `${INVOICE_CREATE_OPERATION}, ${USER_LOCK_OPERATION}`,
			dataFieldPoliciesJson: `{"${INVOICE_CREATE_OPERATION}":["invoiceDocument"],"${USER_LOCK_OPERATION}":["username"]}`,
		};
		assert.throws(() => validateGovernanceConfiguration(business), /cannot provision users/);
		const provisioning = {
			...credentials,
			sidecarMode: 'userProvisioning' as const,
			allowUserCreation: true,
			allowedOperations: `${USER_LOCK_OPERATION}, ${INVOICE_PARK_OPERATION}`,
			dataFieldPoliciesJson: `{"${USER_LOCK_OPERATION}":["username"],"${INVOICE_PARK_OPERATION}":["invoiceDocument"]}`,
		};
		assert.throws(() => validateGovernanceConfiguration(provisioning), /may allow only/);
		assert.throws(
			() =>
				validateGovernanceConfiguration({
					...credentials,
					allowedOperations: `listSu01Users, ${GOODS_RECEIPT_CREATE_OPERATION}`,
					dataFieldPoliciesJson: `{"listSu01Users":["username"],"${GOODS_RECEIPT_CREATE_OPERATION}":["materialDocument"]}`,
				}),
			/read-only credential cannot allow a write operation/,
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
	it('requires an API token with at least 32 bytes', () => {
		assert.doesNotThrow(() => validateGovernanceConfiguration(credentials));
		assert.throws(
			() => validateGovernanceConfiguration({ ...credentials, apiToken: 'too-short' }),
			/at least 32 bytes/,
		);
	});

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
