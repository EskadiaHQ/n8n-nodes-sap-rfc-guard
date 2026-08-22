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
	type INodeCredentialTestResult,
	type INodeExecutionData,
	type INodeType,
	type INodeTypeDescription,
} from 'n8n-workflow';

import {
	executeApprovedOperation,
	executeApprovedWriteOperation,
	testSidecarConnection,
} from './client';
import {
	allowedDataFieldsForOperation,
	assertCreateConfirmation,
	assertOperationAllowed,
	assertOperationId,
	assertProvisioningCredential,
	enforceSerializedByteLimit,
	parseAllowedOperations,
	parseDataFieldPolicies,
	parseParametersJson,
	validateGovernanceConfiguration,
	USER_CREATE_OPERATION,
} from './governance';
import {
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
			'Run governed SAP RFC/BAPI reads or explicitly confirmed user provisioning through isolated HTTPS sidecars',
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
					{ name: 'Company Code', value: 'companyCode' },
					{ name: 'Connection', value: 'connection' },
					{ name: 'Customer', value: 'customer' },
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
				displayOptions: { show: { resource: ['userAdministration'] } },
				options: [
					{
						name: 'Create Communication User',
						value: 'createCommunicationUser',
						action: 'Create a governed SAP communication user',
						description:
							'Creates one prefix-restricted Communication user without roles or profiles',
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
				description: 'Required for ATP; optional for material details',
				displayOptions: {
					show: {
						resource: ['material'],
						operation: ['getDetails', 'checkAvailability'],
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
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'Last Name',
				name: 'lastName',
				type: 'string',
				default: 'Demo User',
				required: true,
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'Email',
				name: 'email',
				type: 'string',
				default: '',
				placeholder: 'demo@example.invalid',
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'Validity (Days)',
				name: 'validDays',
				type: 'number',
				typeOptions: { minValue: 1, maxValue: 7 },
				default: 1,
				description: 'Maximum validity is also constrained by the provisioning sidecar',
				displayOptions: { show: { resource: ['userAdministration'] } },
			},
			{
				displayName: 'Confirmation',
				name: 'writeConfirmation',
				type: 'string',
				default: '',
				placeholder: 'CREATE N8N_DEMO_01',
				description: 'Enter CREATE followed by the exact target username',
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
				displayName: 'Correlation ID',
				name: 'correlationId',
				type: 'string',
				default: '',
				placeholder: 'Generated automatically when empty',
				description: 'Trace identifier shared by n8n, the sidecar, and SAP logs',
				displayOptions: {
					show: {
						resource: [
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
							: sanitizeHealthResponse(result)) as IDataObject,
						pairedItem: { item: itemIndex },
					});
					continue;
				}

				if (resource === 'userAdministration') {
					assertProvisioningCredential(credentials);
					const username = assertCreateConfirmation(
						this.getNodeParameter('username', itemIndex) as string,
						this.getNodeParameter('writeConfirmation', itemIndex) as string,
					);
					const operation = USER_CREATE_OPERATION;
					const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
					assertOperationAllowed(operation, allowedOperations);
					const fieldPolicies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
					const allowedFields = allowedDataFieldsForOperation(operation, fieldPolicies);
					const parameters = {
						username,
						firstName: String(this.getNodeParameter('firstName', itemIndex)).trim(),
						lastName: String(this.getNodeParameter('lastName', itemIndex)).trim(),
						email: String(this.getNodeParameter('email', itemIndex, '')).trim(),
						validDays: this.getNodeParameter('validDays', itemIndex, 1) as number,
					};
					enforceSerializedByteLimit(
						parameters,
						Number(credentials.maxRequestBytes),
						'Operation parameters',
					);
					const traceId = correlationId(
						this.getNodeParameter('correlationId', itemIndex, '') as string,
					);
					const confirmation = `CREATE ${username}`;
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
