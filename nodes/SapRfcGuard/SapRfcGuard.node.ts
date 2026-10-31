import { randomUUID } from 'node:crypto';

import {
	NodeConnectionTypes,
	NodeOperationError,
	OperationalError,
	type ICredentialDataDecryptedObject,
	type ICredentialTestFunctions,
	type ICredentialsDecrypted,
	type IDataObject,
	type IExecuteFunctions,
	type IHttpRequestOptions,
	type ILoadOptionsFunctions,
	type INodeCredentialTestResult,
	type INodeExecutionData,
	type INodeListSearchResult,
	type INodeType,
	type INodeTypeDescription,
} from 'n8n-workflow';

import {
	catalogRequest,
	executeApprovedBusinessWriteOperation,
	executeApprovedOperation,
	executeApprovedWriteOperation,
	testSidecarConnection,
} from './client';
import {
	allowedDataFieldsForOperation,
	assertBusinessOperationsCredential,
	assertCatalogCredential,
	assertCatalogFunctionAllowed,
	assertGoodsReceiptConfirmation,
	assertInvoiceCreateConfirmation,
	assertInvoiceParkConfirmation,
	assertOperationAllowed,
	assertOperationId,
	assertProvisioningCredential,
	assertWriteConfirmation,
	enforceSerializedByteLimit,
	normalizeCatalogFunction,
	parseAllowedOperations,
	parseDataFieldPolicies,
	parseParametersJson,
	userWriteOperationForNodeOperation,
	validateGovernanceConfiguration,
	INVOICE_CREATE_OPERATION,
	INVOICE_PARK_OPERATION,
	GOODS_RECEIPT_CREATE_OPERATION,
	USER_CREATE_OPERATION,
} from './governance';
import {
	sanitizeBusinessHealthResponse,
	sanitizeCatalogExecution,
	sanitizeCatalogList,
	sanitizeCatalogSignature,
	sanitizeExecutionResponse,
	sanitizeHealthResponse,
	sanitizeProvisioningHealthResponse,
	sanitizeWriteResponse,
} from './response';
import { assertAiToolAllowed } from './toolPolicy';
import type { RfcGuardHttpRequest, SapRfcGuardCredentials } from './types';

function httpRequestAdapter(
	request: (options: IHttpRequestOptions) => Promise<unknown>,
): RfcGuardHttpRequest {
	return async (options) => await request(options as IHttpRequestOptions);
}

function correlationId(value: string): string {
	const normalized = value.trim();
	if (normalized === '') return randomUUID();
	if (!/^[A-Za-z0-9._:-]{1,128}$/.test(normalized)) {
		throw new OperationalError(
			'Correlation ID may contain only letters, numbers, dots, underscores, colons, or hyphens.',
		);
	}
	return normalized;
}

function setOptionalParameter(
	parameters: Record<string, unknown>,
	name: string,
	value: unknown,
): void {
	const normalized = String(value ?? '').trim();
	if (normalized !== '') parameters[name] = normalized;
}

/** Keeps the user's JSON text as typed so the sidecar parses decimals and IDs exactly. */
function catalogParametersText(value: unknown): string {
	const text = typeof value === 'string' ? value.trim() || '{}' : JSON.stringify(value ?? {});
	let parsed: unknown;
	try {
		parsed = JSON.parse(text);
	} catch {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError('SAP Parameters (JSON) must be valid JSON.');
	}
	if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
		throw new OperationalError(
			'SAP Parameters (JSON) must be an object keyed by SAP parameter name, for example {"PURCHASEORDER":"4500000008"}.',
		);
	}
	return text;
}

export class SapRfcGuard implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'Logali SAP RFC Guard',
		name: 'sapRfcGuard',
		icon: {
			light: 'file:sapRfcGuard-v047.svg',
			dark: 'file:sapRfcGuard-v047.dark.svg',
		},
		group: ['input'],
		version: 1,
		subtitle: '={{$parameter["resource"] + ": " + $parameter["operation"]}}',
		description:
			'Run governed SAP RFC/BAPI reads or explicitly confirmed user administration through isolated HTTPS sidecars',
		usableAsTool: {
			replacements: {
				description:
					'Run one credential-allowlisted, read-only SAP business operation with bounded input and projected output fields',
			},
		},
		defaults: { name: 'Logali SAP RFC Guard' },
		inputs: [NodeConnectionTypes.Main],
		outputs: [NodeConnectionTypes.Main],
		credentials: [
			{
				name: 'sapRfcGuardApi',
				required: true,
				testedBy: 'sapRfcGuardConnectionTest',
			},
		],
		properties: [
			{
				displayName: 'Resource',
				name: 'resource',
				type: 'options',
				noDataExpression: true,
				options: [
					{ name: 'Approved Function', value: 'approvedFunction' },
					{ name: 'Company Code', value: 'companyCode' },
					{ name: 'Connection', value: 'connection' },
					{ name: 'Customer', value: 'customer' },
					{ name: 'Goods Movement', value: 'goodsMovement' },
					{ name: 'Incoming Invoice', value: 'incomingInvoice' },
					{ name: 'Material', value: 'material' },
					{ name: 'Open Item', value: 'openItem' },
					{ name: 'Purchase Order', value: 'purchaseOrder' },
					{ name: 'Read Operation', value: 'readOperation' },
					{ name: 'Sales Order', value: 'salesOrder' },
					{ name: 'User Administration', value: 'userAdministration' },
					{ name: 'Vendor', value: 'vendor' },
				],
				default: 'connection',
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
				options: [
					{
						name: 'Create Purchase-Order Goods Receipt',
						value: 'createGoodsReceipt',
						action: 'Post a governed 101 goods receipt',
						description:
							'Validates the open purchase-order quantity, posts movement 101, commits, and reads the material document back',
					},
				],
				default: 'createGoodsReceipt',
			},
			{
				displayName: 'Document Date',
				name: 'goodsReceiptDocumentDate',
				type: 'string',
				default: '',
				placeholder: '2026-08-25',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Posting Date',
				name: 'goodsReceiptPostingDate',
				type: 'string',
				default: '',
				placeholder: '2026-08-25',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'External Reference',
				name: 'goodsReceiptReference',
				type: 'string',
				default: '',
				placeholder: 'WB030926-GR01',
				required: true,
				description: 'Stable delivery-note or intake reference, maximum 16 characters',
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Purchase Order',
				name: 'goodsReceiptPurchaseOrder',
				type: 'string',
				default: '',
				placeholder: '4500000009',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Purchase Order Item',
				name: 'goodsReceiptPurchaseOrderItem',
				type: 'string',
				default: '00010',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Quantity',
				name: 'goodsReceiptQuantity',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 1,
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Unit',
				name: 'goodsReceiptUnit',
				type: 'string',
				default: 'PC',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Plant',
				name: 'goodsReceiptPlant',
				type: 'string',
				default: '1010',
				required: true,
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Storage Location',
				name: 'goodsReceiptStorageLocation',
				type: 'string',
				default: '101A',
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Item Text',
				name: 'goodsReceiptItemText',
				type: 'string',
				default: '',
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Write Confirmation',
				name: 'goodsReceiptConfirmation',
				type: 'string',
				default: '',
				placeholder: 'POST GOODS RECEIPT WB030926-GR01',
				required: true,
				description: 'Must exactly match POST GOODS RECEIPT followed by the external reference',
				displayOptions: { show: { resource: ['goodsMovement'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['userAdministration'] } },
				options: [
					{
						name: 'Create Communication User',
						value: 'createCommunicationUser',
						action: 'Create a governed SAP communication user',
						description:
							'Creates one prefix-restricted Communication user without roles or profiles',
					},
					{
						name: 'Lock User',
						value: 'lockUser',
						action: 'Lock a governed SAP user',
						description: 'Locks one prefix-restricted user (administrator lock) and reads the lock back',
					},
					{
						name: 'Reset Password',
						value: 'resetPassword',
						action: 'Reset the password of a governed SAP user',
						description:
							'Sets the sidecar-held initial password; the user must change it at next logon and n8n never sees it',
					},
					{
						name: 'Unlock User',
						value: 'unlockUser',
						action: 'Unlock a governed SAP user',
						description: 'Removes the administrator or failed-logon lock of one prefix-restricted user',
					},
				],
				default: 'createCommunicationUser',
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['companyCode'] } },
				options: [
					{
						name: 'Get Details',
						value: 'getDetails',
						action: 'Get company code details',
						description: 'Uses the fixed BAPI_COMPANYCODE_GETDETAIL mapping',
					},
					{
						name: 'Get Many',
						value: 'getMany',
						action: 'List company codes',
						description: 'Uses the fixed BAPI_COMPANYCODE_GETLIST mapping',
					},
				],
				default: 'getMany',
			},
			{
				displayName: 'Company Code',
				name: 'createInvoiceCompanyCode',
				type: 'string',
				default: '1010',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Document Date',
				name: 'createInvoiceDocumentDate',
				type: 'string',
				default: '',
				placeholder: '2025-02-15',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Posting Date',
				name: 'createInvoicePostingDate',
				type: 'string',
				default: '',
				placeholder: '2025-02-15',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Reference',
				name: 'createInvoiceReference',
				type: 'string',
				default: '',
				placeholder: 'WB030926-INV01',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Currency',
				name: 'createInvoiceCurrency',
				type: 'string',
				default: 'EUR',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Gross Amount',
				name: 'createInvoiceGrossAmount',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 0,
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Purchase Order',
				name: 'createInvoicePurchaseOrder',
				type: 'string',
				default: '',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Purchase Order Item',
				name: 'createInvoicePurchaseOrderItem',
				type: 'string',
				default: '00010',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Item Amount',
				name: 'createInvoiceItemAmount',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 0,
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Quantity',
				name: 'createInvoiceQuantity',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 1,
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Purchase Order Unit',
				name: 'createInvoiceUnit',
				type: 'string',
				default: 'ST',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Tax Code',
				name: 'createInvoiceTaxCode',
				type: 'string',
				default: '',
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Goods Receipt Document',
				name: 'createInvoiceReferenceDocument',
				type: 'string',
				default: '',
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Goods Receipt Year',
				name: 'createInvoiceReferenceDocumentYear',
				type: 'string',
				default: '',
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Goods Receipt Item',
				name: 'createInvoiceReferenceDocumentItem',
				type: 'string',
				default: '',
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Write Confirmation',
				name: 'createInvoiceConfirmation',
				type: 'string',
				default: '',
				placeholder: 'CREATE INVOICE WB030926-INV01',
				description: 'Must exactly match CREATE INVOICE or PARK INVOICE followed by the reference',
				required: true,
				displayOptions: { show: { resource: ['incomingInvoice'], operation: ['create', 'park'] } },
			},
			{
				displayName: 'Company Code',
				name: 'companyCode',
				type: 'string',
				default: '',
				placeholder: '1000',
				required: true,
				displayOptions: {
					show: { resource: ['companyCode'], operation: ['getDetails'] },
				},
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['material'] } },
				options: [
					{
						name: 'Check Availability',
						value: 'checkAvailability',
						action: 'Check material availability',
						description: 'Uses the fixed BAPI_MATERIAL_AVAILABILITY ATP mapping',
					},
					{
						name: 'Get Details',
						value: 'getDetails',
						action: 'Get material details',
						description: 'Uses the fixed BAPI_MATERIAL_GET_DETAIL mapping',
					},
					{
						name: 'Search',
						value: 'search',
						action: 'Search materials',
						description: 'Uses the fixed BAPI_MATERIAL_GETLIST mapping with a row limit',
					},
				],
				default: 'search',
			},
			{
				displayName: 'Material Pattern',
				name: 'materialPattern',
				type: 'string',
				default: '',
				placeholder: 'TG*',
				description: 'Optional SAP-style material pattern; use * as the wildcard',
				displayOptions: {
					show: { resource: ['material'], operation: ['search'] },
				},
			},
			{
				displayName: 'Description Contains',
				name: 'descriptionPattern',
				type: 'string',
				default: '',
				placeholder: 'bike',
				description: 'Optional text filter; wildcards are added when omitted',
				displayOptions: {
					show: { resource: ['material'], operation: ['search'] },
				},
			},
			{
				displayName: 'Material',
				name: 'materialId',
				type: 'string',
				default: '',
				required: true,
				displayOptions: {
					show: {
						resource: ['material'],
						operation: ['getDetails', 'checkAvailability'],
					},
				},
			},
			{
				displayName: 'Plant',
				name: 'plant',
				type: 'string',
				default: '',
				placeholder: '1000',
				description: 'Optional plant context for material details',
				displayOptions: {
					show: {
						resource: ['material'],
						operation: ['getDetails'],
					},
				},
			},
			{
				displayName: 'Plant',
				name: 'plant',
				type: 'string',
				default: '',
				placeholder: '1000',
				description: 'Plant where SAP checks the requested material availability',
				required: true,
				displayOptions: {
					show: {
						resource: ['material'],
						operation: ['checkAvailability'],
					},
				},
			},
			{
				displayName: 'Storage Location',
				name: 'storageLocation',
				type: 'string',
				default: '',
				placeholder: '0001',
				description: 'Optional storage location for the ATP check',
				displayOptions: {
					show: { resource: ['material'], operation: ['checkAvailability'] },
				},
			},
			{
				displayName: 'Requested Date',
				name: 'requestedDate',
				type: 'string',
				default: '',
				placeholder: '2026-08-25',
				required: true,
				displayOptions: {
					show: { resource: ['material'], operation: ['checkAvailability'] },
				},
			},
			{
				displayName: 'Requested Quantity',
				name: 'requestedQuantity',
				type: 'number',
				typeOptions: { minValue: 0.000001 },
				default: 1,
				required: true,
				displayOptions: {
					show: { resource: ['material'], operation: ['checkAvailability'] },
				},
			},
			{
				displayName: 'Unit',
				name: 'unit',
				type: 'string',
				default: '',
				placeholder: 'EA',
				description: 'Optional SAP unit of measure',
				displayOptions: {
					show: { resource: ['material'], operation: ['checkAvailability'] },
				},
			},
			{
				displayName: 'Check Rule',
				name: 'checkRule',
				type: 'string',
				default: '',
				description: 'Optional ATP checking rule configured in SAP',
				displayOptions: {
					show: { resource: ['material'], operation: ['checkAvailability'] },
				},
			},
			{
				displayName: 'Valuation Area',
				name: 'valuationArea',
				type: 'string',
				default: '',
				placeholder: '1000',
				description: 'Optional valuation area for price data',
				displayOptions: {
					show: { resource: ['material'], operation: ['getDetails'] },
				},
			},
			{
				displayName: 'Valuation Type',
				name: 'valuationType',
				type: 'string',
				default: '',
				description: 'Optional split-valuation type',
				displayOptions: {
					show: { resource: ['material'], operation: ['getDetails'] },
				},
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['purchaseOrder'] } },
				options: [
					{
						name: 'Get Details',
						value: 'getDetails',
						action: 'Get purchase order details',
						description: 'Uses the fixed BAPI_PO_GETDETAIL1 mapping',
					},
				],
				default: 'getDetails',
			},
			{
				displayName: 'Purchase Order',
				name: 'purchaseOrderId',
				type: 'string',
				default: '',
				placeholder: '4500000001',
				required: true,
				displayOptions: { show: { resource: ['purchaseOrder'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['salesOrder'] } },
				options: [
					{
						name: 'Get Status',
						value: 'getStatus',
						action: 'Get sales order status',
						description: 'Uses the fixed BAPI_SALESORDER_GETSTATUS mapping',
					},
				],
				default: 'getStatus',
			},
			{
				displayName: 'Sales Document',
				name: 'salesDocumentId',
				type: 'string',
				default: '',
				placeholder: '5000000001',
				required: true,
				displayOptions: { show: { resource: ['salesOrder'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['incomingInvoice'] } },
				options: [
					{
						name: 'Create',
						value: 'create',
						action: 'Create a governed incoming invoice',
						description: 'Creates and commits one MM supplier invoice, then reads it back',
					},
					{
						name: 'Detect Potential Duplicates',
						value: 'detectDuplicates',
						action: 'Detect potential duplicate incoming invoices',
						description: 'Compares vendor, reference, amount, currency, and a bounded date window',
					},
					{
						name: 'Get Details',
						value: 'getDetails',
						action: 'Get incoming invoice details',
						description: 'Uses the fixed BAPI_INCOMINGINVOICE_GETDETAIL mapping',
					},
					{
						name: 'Get Many',
						value: 'getMany',
						action: 'List incoming invoices',
						description:
							'Uses the fixed BAPI_INCOMINGINVOICE_GETLIST mapping and a bounded date window',
					},
					{
						name: 'Park',
						value: 'park',
						action: 'Park a governed incoming invoice',
						description: 'Parks one MM supplier invoice without posting it, then reads it back',
					},
				],
				default: 'getMany',
			},
			{
				displayName: 'Invoice Document',
				name: 'invoiceDocumentId',
				type: 'string',
				default: '',
				placeholder: '5100000001',
				required: true,
				displayOptions: {
					show: { resource: ['incomingInvoice'], operation: ['getDetails'] },
				},
			},
			{
				displayName: 'Fiscal Year',
				name: 'fiscalYear',
				type: 'string',
				default: '',
				placeholder: '2026',
				required: true,
				displayOptions: {
					show: { resource: ['incomingInvoice'], operation: ['getDetails'] },
				},
			},
			{
				displayName: 'Date From',
				name: 'dateFrom',
				type: 'string',
				default: '',
				placeholder: '2026-08-01',
				required: true,
				description: 'Document-date range start; the sidecar allows at most 31 days',
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['getMany', 'detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Date To',
				name: 'dateTo',
				type: 'string',
				default: '',
				placeholder: '2026-08-21',
				required: true,
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['getMany', 'detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Vendor',
				name: 'vendorId',
				type: 'string',
				default: '',
				placeholder: '100012',
				required: true,
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Vendor',
				name: 'vendorFilter',
				type: 'string',
				default: '',
				placeholder: '100012',
				description: 'Optional vendor filter',
				displayOptions: {
					show: { resource: ['incomingInvoice'], operation: ['getMany'] },
				},
			},
			{
				displayName: 'Reference',
				name: 'invoiceReference',
				type: 'string',
				default: '',
				placeholder: 'SUP-INV-2026-0042',
				required: true,
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Reference',
				name: 'referenceFilter',
				type: 'string',
				default: '',
				description: 'Optional exact SAP reference filter',
				displayOptions: {
					show: { resource: ['incomingInvoice'], operation: ['getMany'] },
				},
			},
			{
				displayName: 'Gross Amount',
				name: 'invoiceAmount',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 0,
				required: true,
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Currency',
				name: 'invoiceCurrency',
				type: 'string',
				default: 'EUR',
				required: true,
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Amount Tolerance',
				name: 'amountTolerance',
				type: 'number',
				typeOptions: { minValue: 0 },
				default: 0.01,
				description: 'Maximum absolute amount difference for a duplicate candidate',
				displayOptions: {
					show: {
						resource: ['incomingInvoice'],
						operation: ['detectDuplicates'],
					},
				},
			},
			{
				displayName: 'Company Code',
				name: 'invoiceCompanyCode',
				type: 'string',
				default: '',
				placeholder: '1000',
				description: 'Optional post-read company-code filter',
				displayOptions: {
					show: { resource: ['incomingInvoice'], operation: ['getMany'] },
				},
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['openItem'] } },
				options: [
					{
						name: 'Get Customer Open Items',
						value: 'getCustomer',
						action: 'Get customer open items',
					},
					{
						name: 'Get Vendor Open Items',
						value: 'getVendor',
						action: 'Get vendor open items',
					},
					{
						name: 'Summarize Overdue Items',
						value: 'summarize',
						action: 'Summarize overdue open items',
					},
				],
				default: 'getVendor',
			},
			{
				displayName: 'Account Type',
				name: 'accountType',
				type: 'options',
				options: [
					{ name: 'Customer', value: 'customer' },
					{ name: 'Vendor', value: 'vendor' },
				],
				default: 'vendor',
				displayOptions: {
					show: { resource: ['openItem'], operation: ['summarize'] },
				},
			},
			{
				displayName: 'Account',
				name: 'accountId',
				type: 'string',
				default: '',
				placeholder: '100012',
				required: true,
				displayOptions: { show: { resource: ['openItem'] } },
			},
			{
				displayName: 'Company Code',
				name: 'openItemCompanyCode',
				type: 'string',
				default: '',
				placeholder: '1000',
				required: true,
				displayOptions: { show: { resource: ['openItem'] } },
			},
			{
				displayName: 'Key Date',
				name: 'keyDate',
				type: 'string',
				default: '',
				placeholder: '2026-08-21',
				required: true,
				displayOptions: { show: { resource: ['openItem'] } },
			},
			{
				displayName: 'Include Noted Items',
				name: 'notedItems',
				type: 'boolean',
				default: false,
				displayOptions: { show: { resource: ['openItem'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['vendor', 'customer'] } },
				options: [
					{
						name: 'Get Details',
						value: 'getDetails',
						action: 'Get governed account details',
					},
				],
				default: 'getDetails',
			},
			{
				displayName: 'Account Number',
				name: 'masterAccountId',
				type: 'string',
				default: '',
				placeholder: '100012',
				required: true,
				displayOptions: { show: { resource: ['vendor', 'customer'] } },
			},
			{
				displayName: 'Company Code',
				name: 'masterCompanyCode',
				type: 'string',
				default: '',
				placeholder: '1000',
				required: true,
				displayOptions: { show: { resource: ['vendor', 'customer'] } },
			},
			{
				displayName: 'SAP Username',
				name: 'username',
				type: 'string',
				default: '',
				placeholder: 'N8N_DEMO_01',
				description:
					'1-12 uppercase letters, numbers, or underscores; the sidecar also enforces its configured prefix',
				required: true,
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'First Name',
				name: 'firstName',
				type: 'string',
				default: 'n8n',
				required: true,
				displayOptions: {
					show: { resource: ['userAdministration'], operation: ['createCommunicationUser'] },
				},
			},
			{
				displayName: 'Last Name',
				name: 'lastName',
				type: 'string',
				default: 'Demo User',
				required: true,
				displayOptions: {
					show: { resource: ['userAdministration'], operation: ['createCommunicationUser'] },
				},
			},
			{
				displayName: 'Email',
				name: 'email',
				type: 'string',
				default: '',
				placeholder: 'demo@example.invalid',
				displayOptions: {
					show: { resource: ['userAdministration'], operation: ['createCommunicationUser'] },
				},
			},
			{
				displayName: 'Validity (Days)',
				name: 'validDays',
				type: 'number',
				typeOptions: { minValue: 1, maxValue: 7 },
				default: 1,
				description: 'Maximum validity is also constrained by the provisioning sidecar',
				displayOptions: {
					show: { resource: ['userAdministration'], operation: ['createCommunicationUser'] },
				},
			},
			{
				displayName: 'Confirmation',
				name: 'writeConfirmation',
				type: 'string',
				default: '',
				placeholder: 'CREATE N8N_DEMO_01',
				description:
					'Enter CREATE, RESET, LOCK or UNLOCK (matching the operation) followed by the exact target username',
				required: true,
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['connection'] } },
				options: [
					{
						name: 'Test Connection',
						value: 'testConnection',
						action: 'Test the RFC sidecar connection',
						description: 'Verify HTTPS authentication and the sidecar read-only capability',
					},
				],
				default: 'testConnection',
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['readOperation'] } },
				options: [
					{
						name: 'Execute Approved Read',
						value: 'executeRead',
						action: 'Execute an approved read operation',
						description:
							'Call one allowlisted business alias; direct BAPI and RFC function names are rejected',
					},
				],
				default: 'executeRead',
			},
			{
				displayName: 'Business Operation ID',
				name: 'businessOperationId',
				type: 'string',
				default: '',
				placeholder: 'listSu01Users',
				description:
					'Approved business alias configured in the credential, not a technical BAPI or function-module name',
				required: true,
				displayOptions: { show: { resource: ['readOperation'] } },
			},
			{
				displayName: 'Parameters JSON',
				name: 'parametersJson',
				type: 'string',
				typeOptions: { rows: 8 },
				default: '{}',
				placeholder: '{"client":"100","inactiveDays":90}',
				description: 'JSON object validated again by the sidecar before any RFC call',
				displayOptions: { show: { resource: ['readOperation'] } },
			},
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				displayOptions: { show: { resource: ['approvedFunction'] } },
				options: [
					{
						name: 'Execute',
						value: 'execute',
						action: 'Execute an approved SAP function',
						description:
							'Run one read-only SAP function approved in the sidecar catalog; the sidecar validates the parameters against the real SAP signature',
					},
					{
						name: 'Get Signature',
						value: 'getSignature',
						action: 'Get the signature of an approved SAP function',
						description:
							'Return the real IMPORT, EXPORT, CHANGING and TABLES parameters, the approved inputs and the SAP documentation',
					},
					{
						name: 'Search',
						value: 'search',
						action: 'Search approved SAP functions',
						description: 'List the approved functions that this SAP system really exposes',
					},
				],
				default: 'execute',
			},
			{
				displayName: 'Search Text',
				name: 'catalogQuery',
				type: 'string',
				default: '',
				placeholder: 'purchase order',
				description: 'Filter by function name, title, description or tag; empty lists all approved functions',
				displayOptions: { show: { resource: ['approvedFunction'], operation: ['search'] } },
			},
			{
				displayName: 'Function',
				name: 'catalogFunction',
				type: 'resourceLocator',
				default: { mode: 'list', value: '' },
				required: true,
				description: 'Approved SAP function module. Only functions in the sidecar policy and in this credential are offered.',
				displayOptions: {
					show: { resource: ['approvedFunction'], operation: ['getSignature', 'execute'] },
				},
				modes: [
					{
						displayName: 'From Approved Catalog',
						name: 'list',
						type: 'list',
						typeOptions: { searchListMethod: 'searchApprovedFunctions', searchable: true },
					},
					{
						displayName: 'By Name',
						name: 'name',
						type: 'string',
						placeholder: 'BAPI_PO_GETDETAIL1',
						validation: [
							{
								type: 'regex',
								properties: {
									regex: '^[A-Za-z0-9_/]{1,30}$',
									errorMessage: 'Enter an SAP function module name',
								},
							},
						],
					},
				],
			},
			{
				displayName: 'Use Get Signature first: it returns the real parameters and an input template. Values for CHAR and NUMC fields must be JSON strings, so leading zeros are kept; send amounts as text such as "12.50".',
				name: 'catalogNotice',
				type: 'notice',
				default: '',
				displayOptions: { show: { resource: ['approvedFunction'], operation: ['execute'] } },
			},
			{
				displayName: 'SAP Parameters (JSON)',
				name: 'catalogParametersJson',
				type: 'json',
				default: '{}',
				description:
					'Object keyed by SAP parameter name: scalars as text, structures as objects, tables as arrays of row objects',
				displayOptions: { show: { resource: ['approvedFunction'], operation: ['execute'] } },
			},
			{
				displayName: 'Validate Only',
				name: 'catalogDryRun',
				type: 'boolean',
				default: false,
				description: 'Whether to let the sidecar validate the parameters against the real signature without calling the function',
				displayOptions: { show: { resource: ['approvedFunction'], operation: ['execute'] } },
			},
			{
				displayName: 'Fail on SAP Error Message',
				name: 'catalogFailOnSapError',
				type: 'boolean',
				default: true,
				description: 'Whether an error or abort message (type E or A) returned by SAP stops the node; disable to branch on the outcome field',
				displayOptions: { show: { resource: ['approvedFunction'], operation: ['execute'] } },
			},
			{
				displayName: 'Correlation ID',
				name: 'correlationId',
				type: 'string',
				default: '',
				placeholder: 'Generated automatically when empty',
				description: 'Trace identifier shared by n8n, the sidecar, and SAP logs',
				displayOptions: {
					show: {
						resource: [
							'approvedFunction',
							'readOperation',
							'userAdministration',
							'companyCode',
							'material',
							'purchaseOrder',
							'salesOrder',
							'incomingInvoice',
							'openItem',
							'vendor',
							'customer',
						],
					},
				},
			},
			{
				displayName: 'Row Limit',
				name: 'limit',
				type: 'number',
				typeOptions: { minValue: 1, maxValue: 1000 },
				default: 50,
				description: 'Max number of results to return',
				displayOptions: {
					show: {
						resource: [
							'approvedFunction',
							'readOperation',
							'companyCode',
							'material',
							'purchaseOrder',
							'salesOrder',
							'incomingInvoice',
							'openItem',
							'vendor',
							'customer',
						],
					},
				},
			},
			{
				displayName: 'Include Result Metadata',
				name: 'includeMetadata',
				type: 'boolean',
				default: true,
				description: 'Whether to include operation, correlation, limit, and read-only evidence',
				displayOptions: {
					show: {
						resource: [
							'approvedFunction',
							'readOperation',
							'companyCode',
							'material',
							'purchaseOrder',
							'salesOrder',
							'incomingInvoice',
							'openItem',
							'vendor',
							'customer',
						],
					},
				},
			},
		],
	};

	methods = {
		credentialTest: {
			async sapRfcGuardConnectionTest(
				this: ICredentialTestFunctions,
				credential: ICredentialsDecrypted<ICredentialDataDecryptedObject>,
			): Promise<INodeCredentialTestResult> {
				try {
					const credentials = credential.data as unknown as SapRfcGuardCredentials;
					validateGovernanceConfiguration(credentials);
					const credentialHttpRequest: RfcGuardHttpRequest = async (options) =>
						// ICredentialTestFunctions in this n8n SDK exposes only the legacy request helper.
						// eslint-disable-next-line @n8n/community-nodes/no-deprecated-workflow-functions
						await this.helpers.request({
							method: options.method,
							uri: options.url,
							headers: options.headers,
							...(options.body ? { body: options.body } : {}),
							json: true,
							timeout: options.timeout,
							rejectUnauthorized: !options.skipSslCertificateValidation,
						});
					const result = await testSidecarConnection(
						credentials,
						credentialHttpRequest,
						randomUUID(),
					);
					if ((credentials.sidecarMode ?? 'readOnly') === 'userProvisioning') {
						sanitizeProvisioningHealthResponse(result);
						return {
							status: 'OK',
							message: 'Governed SAP user-provisioning sidecar connection successful',
						};
					}
					if (credentials.sidecarMode === 'businessOperations') {
						sanitizeBusinessHealthResponse(result);
						return {
							status: 'OK',
							message: 'Governed SAP business-operation sidecar connection successful',
						};
					}
					sanitizeHealthResponse(result);
					return {
						status: 'OK',
						message: 'Read-only RFC sidecar connection successful',
					};
				} catch (error) {
					return {
						status: 'Error',
						message: error instanceof Error ? error.message : String(error),
					};
				}
			},
		},
		listSearch: {
			/** Approved AND present in SAP (sidecar) AND listed in this credential. */
			async searchApprovedFunctions(
				this: ILoadOptionsFunctions,
				filter?: string,
				paginationToken?: string,
			): Promise<INodeListSearchResult> {
				const credentials = (await this.getCredentials(
					'sapRfcGuardApi',
				)) as unknown as SapRfcGuardCredentials;
				validateGovernanceConfiguration(credentials);
				const allowed = assertCatalogCredential(credentials);
				const cursor = Number(paginationToken ?? 0) || 0;
				const query = new URLSearchParams({
					query: String(filter ?? '').trim().slice(0, 60),
					cursor: String(cursor),
					limit: '20',
				});
				const listing = sanitizeCatalogList(
					await catalogRequest(
						credentials,
						httpRequestAdapter(this.helpers.httpRequest),
						'GET',
						`/v1/catalog/functions?${query.toString()}`,
						randomUUID(),
					),
				);
				return {
					results: listing.functions
						.filter((entry) => allowed.has(String(entry.name)))
						.map((entry) => ({
							name: `${String(entry.name)} · ${String(entry.title ?? '')}`,
							value: String(entry.name),
							description: `${String(entry.kind)} · ${entry.allowExecute === true ? 'executable read' : 'signature only'}`,
						})),
					paginationToken: listing.nextCursor === null ? undefined : String(listing.nextCursor),
				};
			},
		},
	};

	async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
		const inputItems = this.getInputData();
		const outputItems: INodeExecutionData[] = [];

		for (let itemIndex = 0; itemIndex < inputItems.length; itemIndex += 1) {
			try {
				const resource = this.getNodeParameter('resource', itemIndex) as string;
				const credentials = (await this.getCredentials(
					'sapRfcGuardApi',
					itemIndex,
				)) as unknown as SapRfcGuardCredentials;
				validateGovernanceConfiguration(credentials);
				assertAiToolAllowed(this.getNode().type, credentials);
				const httpRequest = httpRequestAdapter(this.helpers.httpRequest);

				if (resource === 'connection') {
					const result = await testSidecarConnection(credentials, httpRequest, randomUUID());
					enforceSerializedByteLimit(
						result,
						Number(credentials.maxResponseBytes),
						'Sidecar response',
					);
					outputItems.push({
						json: ((credentials.sidecarMode ?? 'readOnly') === 'userProvisioning'
							? sanitizeProvisioningHealthResponse(result)
							: credentials.sidecarMode === 'businessOperations'
								? sanitizeBusinessHealthResponse(result)
								: sanitizeHealthResponse(result)) as IDataObject,
						pairedItem: { item: itemIndex },
					});
					continue;
				}

				if (resource === 'approvedFunction') {
					const allowedFunctions = assertCatalogCredential(credentials);
					const selected = this.getNodeParameter('operation', itemIndex, 'execute') as string;
					const traceId = correlationId(
						this.getNodeParameter('correlationId', itemIndex, '') as string,
					);
					if (selected === 'search') {
						const text = String(this.getNodeParameter('catalogQuery', itemIndex, '')).trim();
						const limit = this.getNodeParameter('limit', itemIndex, 50) as number;
						const found: Record<string, unknown>[] = [];
						let cursor: number | null = 0;
						for (let page = 0; cursor !== null && page < 10 && found.length < limit; page += 1) {
							const query = new URLSearchParams({ query: text, cursor: String(cursor), limit: '50' });
							const response = await catalogRequest(
								credentials, httpRequest, 'GET', `/v1/catalog/functions?${query.toString()}`, traceId,
							);
							enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
							const listing = sanitizeCatalogList(response);
							for (const entry of listing.functions) {
								if (allowedFunctions.has(String(entry.name)) && found.length < limit) {
									found.push({ ...entry, policySha256: listing.policySha256, correlationId: traceId });
								}
							}
							cursor = listing.nextCursor;
						}
						outputItems.push(
							...found.map((json) => ({ json: json as IDataObject, pairedItem: { item: itemIndex } })),
						);
						continue;
					}
					const functionName = normalizeCatalogFunction(
						this.getNodeParameter('catalogFunction', itemIndex, '', { extractValue: true }),
					);
					assertCatalogFunctionAllowed(functionName, allowedFunctions);
					const path = `/v1/catalog/functions/${encodeURIComponent(functionName)}`;
					if (selected === 'getSignature') {
						const response = await catalogRequest(credentials, httpRequest, 'GET', path, traceId);
						enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
						outputItems.push({
							json: sanitizeCatalogSignature(response, functionName) as IDataObject,
							pairedItem: { item: itemIndex },
						});
						continue;
					}
					const parametersJson = catalogParametersText(
						this.getNodeParameter('catalogParametersJson', itemIndex, '{}'),
					);
					if (Buffer.byteLength(parametersJson, 'utf8') > Number(credentials.maxRequestBytes)) {
						throw new OperationalError('SAP Parameters (JSON) exceeds the credential request-size limit.');
					}
					const requestedLimit = this.getNodeParameter('limit', itemIndex, 50) as number;
					const response = await catalogRequest(credentials, httpRequest, 'POST', `${path}/execute`, traceId, {
						function: functionName,
						parametersJson,
						options: {
							maxRows: Math.min(requestedLimit, Number(credentials.maxRows)),
							dryRun: this.getNodeParameter('catalogDryRun', itemIndex, false) as boolean,
						},
						context: { client: 'n8n-sap-rfc-guard', readOnly: true },
					});
					enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
					const result = sanitizeCatalogExecution(
						response,
						functionName,
						traceId,
						this.getNodeParameter('includeMetadata', itemIndex, true) as boolean,
					);
					if (
						result.outcome === 'BUSINESS_ERROR' &&
						(this.getNodeParameter('catalogFailOnSapError', itemIndex, true) as boolean)
					) {
						const first = Array.isArray(result.messages)
							? (result.messages.find((message) => ['E', 'A', 'X'].includes(
								String((message as IDataObject).type))) as IDataObject | undefined)
							: undefined;
						throw new OperationalError(
							`SAP returned an error message for ${functionName}: ${String(first?.message ?? 'see messages')}` +
								(first?.id ? ` (${String(first.id)} ${String(first.number ?? '')})` : ''),
						);
					}
					outputItems.push({ json: result as IDataObject, pairedItem: { item: itemIndex } });
					continue;
				}

				if (resource === 'userAdministration') {
					assertProvisioningCredential(credentials);
					const operation = userWriteOperationForNodeOperation(
						this.getNodeParameter('operation', itemIndex, 'createCommunicationUser') as string,
					);
					const { username, confirmation } = assertWriteConfirmation(
						operation,
						this.getNodeParameter('username', itemIndex) as string,
						this.getNodeParameter('writeConfirmation', itemIndex) as string,
					);
					const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
					assertOperationAllowed(operation, allowedOperations);
					const fieldPolicies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
					const allowedFields = allowedDataFieldsForOperation(operation, fieldPolicies);
					const parameters: Record<string, unknown> =
						operation === USER_CREATE_OPERATION
							? {
									username,
									firstName: String(this.getNodeParameter('firstName', itemIndex)).trim(),
									lastName: String(this.getNodeParameter('lastName', itemIndex)).trim(),
									email: String(this.getNodeParameter('email', itemIndex, '')).trim(),
									validDays: this.getNodeParameter('validDays', itemIndex, 1) as number,
								}
							: { username };
					enforceSerializedByteLimit(
						parameters,
						Number(credentials.maxRequestBytes),
						'Operation parameters',
					);
					const traceId = correlationId(
						this.getNodeParameter('correlationId', itemIndex, '') as string,
					);
					const response = await executeApprovedWriteOperation(
						credentials,
						httpRequest,
						operation,
						parameters,
						traceId,
						confirmation,
					);
					enforceSerializedByteLimit(
						response,
						Number(credentials.maxResponseBytes),
						'Sidecar response',
					);
					outputItems.push({
						json: sanitizeWriteResponse(response, operation, traceId, allowedFields) as IDataObject,
						pairedItem: { item: itemIndex },
					});
					continue;
				}

				let operation: string;
				let parameters: Record<string, unknown>;
				const requestedLimit = this.getNodeParameter('limit', itemIndex, 50) as number;
				if (resource === 'readOperation') {
					operation = assertOperationId(
						this.getNodeParameter('businessOperationId', itemIndex) as string,
					);
					parameters = parseParametersJson(
						this.getNodeParameter('parametersJson', itemIndex, '{}') as string,
					);
				} else if (resource === 'companyCode') {
					const selected = this.getNodeParameter('operation', itemIndex) as string;
					operation = selected === 'getDetails' ? 'getCompanyCodeDetail' : 'listCompanyCodes';
					parameters = {};
					if (selected === 'getDetails') {
						parameters.companyCode = String(this.getNodeParameter('companyCode', itemIndex))
							.trim()
							.toUpperCase();
					}
				} else if (resource === 'material') {
					const selected = this.getNodeParameter('operation', itemIndex) as string;
					operation =
						selected === 'getDetails'
							? 'getMaterialDetail'
							: selected === 'checkAvailability'
								? 'checkMaterialAvailability'
								: 'searchMaterials';
					parameters = {};
					if (selected === 'getDetails') {
						parameters.material = String(this.getNodeParameter('materialId', itemIndex))
							.trim()
							.toUpperCase();
						setOptionalParameter(
							parameters,
							'plant',
							this.getNodeParameter('plant', itemIndex, ''),
						);
						setOptionalParameter(
							parameters,
							'valuationArea',
							this.getNodeParameter('valuationArea', itemIndex, ''),
						);
						setOptionalParameter(
							parameters,
							'valuationType',
							this.getNodeParameter('valuationType', itemIndex, ''),
						);
					} else if (selected === 'checkAvailability') {
						parameters = {
							maxRows: Math.min(requestedLimit, Number(credentials.maxRows)),
							material: String(this.getNodeParameter('materialId', itemIndex)).trim().toUpperCase(),
							plant: String(this.getNodeParameter('plant', itemIndex)).trim().toUpperCase(),
							requestedDate: String(this.getNodeParameter('requestedDate', itemIndex)).trim(),
							requestedQuantity: this.getNodeParameter('requestedQuantity', itemIndex),
						};
						setOptionalParameter(
							parameters,
							'storageLocation',
							this.getNodeParameter('storageLocation', itemIndex, ''),
						);
						setOptionalParameter(parameters, 'unit', this.getNodeParameter('unit', itemIndex, ''));
						setOptionalParameter(
							parameters,
							'checkRule',
							this.getNodeParameter('checkRule', itemIndex, ''),
						);
					} else {
						parameters.maxRows = Math.min(requestedLimit, Number(credentials.maxRows));
						setOptionalParameter(
							parameters,
							'materialPattern',
							this.getNodeParameter('materialPattern', itemIndex, ''),
						);
						setOptionalParameter(
							parameters,
							'descriptionPattern',
							this.getNodeParameter('descriptionPattern', itemIndex, ''),
						);
					}
					} else if (resource === 'goodsMovement') {
						assertBusinessOperationsCredential(credentials, 'goodsReceipt');
						const reference = assertGoodsReceiptConfirmation(
							String(this.getNodeParameter('goodsReceiptReference', itemIndex)),
							String(this.getNodeParameter('goodsReceiptConfirmation', itemIndex)),
						);
						const writeParameters: Record<string, unknown> = {
							documentDate: String(this.getNodeParameter('goodsReceiptDocumentDate', itemIndex)).trim(),
							postingDate: String(this.getNodeParameter('goodsReceiptPostingDate', itemIndex)).trim(),
							reference,
							purchaseOrder: String(this.getNodeParameter('goodsReceiptPurchaseOrder', itemIndex)).trim(),
							purchaseOrderItem: String(this.getNodeParameter('goodsReceiptPurchaseOrderItem', itemIndex)).trim(),
							quantity: this.getNodeParameter('goodsReceiptQuantity', itemIndex),
							unit: String(this.getNodeParameter('goodsReceiptUnit', itemIndex)).trim().toUpperCase(),
							plant: String(this.getNodeParameter('goodsReceiptPlant', itemIndex)).trim().toUpperCase(),
						};
						setOptionalParameter(writeParameters, 'storageLocation', this.getNodeParameter('goodsReceiptStorageLocation', itemIndex, ''));
						setOptionalParameter(writeParameters, 'itemText', this.getNodeParameter('goodsReceiptItemText', itemIndex, ''));
						const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
						assertOperationAllowed(GOODS_RECEIPT_CREATE_OPERATION, allowedOperations);
						const allowedFields = allowedDataFieldsForOperation(
							GOODS_RECEIPT_CREATE_OPERATION,
							parseDataFieldPolicies(credentials.dataFieldPoliciesJson),
						);
						enforceSerializedByteLimit(writeParameters, Number(credentials.maxRequestBytes), 'Operation parameters');
						const traceId = correlationId(this.getNodeParameter('correlationId', itemIndex, '') as string);
						const response = await executeApprovedBusinessWriteOperation(
							credentials,
							httpRequest,
							GOODS_RECEIPT_CREATE_OPERATION,
							writeParameters,
							traceId,
							`POST GOODS RECEIPT ${reference}`,
						);
						enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
						outputItems.push({
							json: sanitizeWriteResponse(response, GOODS_RECEIPT_CREATE_OPERATION, traceId, allowedFields) as IDataObject,
							pairedItem: { item: itemIndex },
						});
						continue;
					} else if (resource === 'purchaseOrder') {
					operation = 'getPurchaseOrderDetail';
					parameters = {
						purchaseOrder: String(this.getNodeParameter('purchaseOrderId', itemIndex)).trim(),
					};
				} else if (resource === 'salesOrder') {
					operation = 'getSalesOrderStatus';
					parameters = {
						salesDocument: String(this.getNodeParameter('salesDocumentId', itemIndex)).trim(),
					};
				} else if (resource === 'incomingInvoice') {
					const selected = this.getNodeParameter('operation', itemIndex) as string;
					if (selected === 'create' || selected === 'park') {
						assertBusinessOperationsCredential(credentials);
						const reference = (selected === 'park'
							? assertInvoiceParkConfirmation
							: assertInvoiceCreateConfirmation)(
								String(this.getNodeParameter('createInvoiceReference', itemIndex)),
								String(this.getNodeParameter('createInvoiceConfirmation', itemIndex)),
							);
						const writeParameters: Record<string, unknown> = {
							companyCode: String(this.getNodeParameter('createInvoiceCompanyCode', itemIndex)).trim().toUpperCase(),
							documentDate: String(this.getNodeParameter('createInvoiceDocumentDate', itemIndex)).trim(),
							postingDate: String(this.getNodeParameter('createInvoicePostingDate', itemIndex)).trim(),
							reference,
							currency: String(this.getNodeParameter('createInvoiceCurrency', itemIndex)).trim().toUpperCase(),
							grossAmount: this.getNodeParameter('createInvoiceGrossAmount', itemIndex),
							purchaseOrder: String(this.getNodeParameter('createInvoicePurchaseOrder', itemIndex)).trim(),
							purchaseOrderItem: String(this.getNodeParameter('createInvoicePurchaseOrderItem', itemIndex)).trim(),
							itemAmount: this.getNodeParameter('createInvoiceItemAmount', itemIndex),
							quantity: this.getNodeParameter('createInvoiceQuantity', itemIndex),
							unit: String(this.getNodeParameter('createInvoiceUnit', itemIndex)).trim().toUpperCase(),
						};
						setOptionalParameter(writeParameters, 'taxCode', this.getNodeParameter('createInvoiceTaxCode', itemIndex, ''));
						setOptionalParameter(writeParameters, 'referenceDocument', this.getNodeParameter('createInvoiceReferenceDocument', itemIndex, ''));
						setOptionalParameter(writeParameters, 'referenceDocumentYear', this.getNodeParameter('createInvoiceReferenceDocumentYear', itemIndex, ''));
						setOptionalParameter(writeParameters, 'referenceDocumentItem', this.getNodeParameter('createInvoiceReferenceDocumentItem', itemIndex, ''));
						const writeOperation = selected === 'park' ? INVOICE_PARK_OPERATION : INVOICE_CREATE_OPERATION;
						const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
						assertOperationAllowed(writeOperation, allowedOperations);
						const allowedFields = allowedDataFieldsForOperation(
							writeOperation,
							parseDataFieldPolicies(credentials.dataFieldPoliciesJson),
						);
						enforceSerializedByteLimit(writeParameters, Number(credentials.maxRequestBytes), 'Operation parameters');
						const traceId = correlationId(this.getNodeParameter('correlationId', itemIndex, '') as string);
						const response = await executeApprovedBusinessWriteOperation(
							credentials,
							httpRequest,
							writeOperation,
							writeParameters,
							traceId,
							`${selected === 'park' ? 'PARK' : 'CREATE'} INVOICE ${reference}`,
						);
						enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
						outputItems.push({
							json: sanitizeWriteResponse(response, writeOperation, traceId, allowedFields) as IDataObject,
							pairedItem: { item: itemIndex },
						});
						continue;
					}
					operation =
						selected === 'getDetails'
							? 'getIncomingInvoiceDetail'
							: selected === 'detectDuplicates'
								? 'detectPotentialDuplicateInvoices'
								: 'listIncomingInvoices';
					if (selected === 'getDetails') {
						parameters = {
							invoiceDocument: String(this.getNodeParameter('invoiceDocumentId', itemIndex)).trim(),
							fiscalYear: String(this.getNodeParameter('fiscalYear', itemIndex)).trim(),
						};
					} else {
						parameters = {
							maxRows: Math.min(requestedLimit, Number(credentials.maxRows)),
							dateFrom: String(this.getNodeParameter('dateFrom', itemIndex)).trim(),
							dateTo: String(this.getNodeParameter('dateTo', itemIndex)).trim(),
						};
						if (selected === 'detectDuplicates') {
							parameters.vendor = String(this.getNodeParameter('vendorId', itemIndex)).trim();
							parameters.reference = String(
								this.getNodeParameter('invoiceReference', itemIndex),
							).trim();
							parameters.amount = this.getNodeParameter('invoiceAmount', itemIndex);
							parameters.currency = String(this.getNodeParameter('invoiceCurrency', itemIndex))
								.trim()
								.toUpperCase();
							parameters.amountTolerance = this.getNodeParameter(
								'amountTolerance',
								itemIndex,
								0.01,
							);
						} else {
							setOptionalParameter(
								parameters,
								'vendor',
								this.getNodeParameter('vendorFilter', itemIndex, ''),
							);
							setOptionalParameter(
								parameters,
								'reference',
								this.getNodeParameter('referenceFilter', itemIndex, ''),
							);
							setOptionalParameter(
								parameters,
								'companyCode',
								this.getNodeParameter('invoiceCompanyCode', itemIndex, ''),
							);
						}
					}
				} else if (resource === 'openItem') {
					const selected = this.getNodeParameter('operation', itemIndex) as string;
					operation =
						selected === 'getCustomer'
							? 'getCustomerOpenItems'
							: selected === 'summarize'
								? 'summarizeOverdueItems'
								: 'getVendorOpenItems';
					parameters = {
						maxRows: Math.min(requestedLimit, Number(credentials.maxRows)),
						companyCode: String(this.getNodeParameter('openItemCompanyCode', itemIndex))
							.trim()
							.toUpperCase(),
						keyDate: String(this.getNodeParameter('keyDate', itemIndex)).trim(),
						notedItems: this.getNodeParameter('notedItems', itemIndex, false),
					};
					const account = String(this.getNodeParameter('accountId', itemIndex)).trim();
					if (selected === 'getCustomer') parameters.customer = account;
					else if (selected === 'getVendor') parameters.vendor = account;
					else {
						parameters.account = account;
						parameters.accountType = this.getNodeParameter('accountType', itemIndex);
					}
				} else if (resource === 'vendor' || resource === 'customer') {
					operation = resource === 'vendor' ? 'getVendorDetail' : 'getCustomerDetail';
					parameters = {
						[resource]: String(this.getNodeParameter('masterAccountId', itemIndex)).trim(),
						companyCode: String(this.getNodeParameter('masterCompanyCode', itemIndex))
							.trim()
							.toUpperCase(),
					};
				} else {
					throw new OperationalError('Unsupported SAP RFC Guard resource.');
				}
				const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
				assertOperationAllowed(operation, allowedOperations);
				const fieldPolicies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
				const allowedFields = allowedDataFieldsForOperation(operation, fieldPolicies);
				enforceSerializedByteLimit(
					parameters,
					Number(credentials.maxRequestBytes),
					'Operation parameters',
				);
				const traceId = correlationId(
					this.getNodeParameter('correlationId', itemIndex, '') as string,
				);
				const response = await executeApprovedOperation(
					credentials,
					httpRequest,
					operation,
					parameters,
					traceId,
				);
				enforceSerializedByteLimit(
					response,
					Number(credentials.maxResponseBytes),
					'Sidecar response',
				);
				const rows = sanitizeExecutionResponse(
					response,
					operation,
					traceId,
					allowedFields,
					requestedLimit,
					Number(credentials.maxRows),
					this.getNodeParameter('includeMetadata', itemIndex, true) as boolean,
				);
				outputItems.push(
					...rows.map((json) => ({
						json: json as IDataObject,
						pairedItem: { item: itemIndex },
					})),
				);
			} catch (error) {
				if (this.continueOnFail()) {
					outputItems.push({
						json: {
							error: error instanceof Error ? error.message : String(error),
						},
						pairedItem: { item: itemIndex },
					});
					continue;
				}
				throw new NodeOperationError(
					this.getNode(),
					error instanceof Error ? error : new Error(String(error)),
					{ itemIndex },
				);
			}
		}

		return [outputItems];
	}
}
