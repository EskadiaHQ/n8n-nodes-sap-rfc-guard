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

export class SapRfcGuard implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'Logali SAP RFC Guard',
		name: 'sapRfcGuard',
		icon: {
			light: 'file:sapRfcGuard.svg',
			dark: 'file:sapRfcGuard.dark.svg',
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
			{ name: 'sapRfcGuardApi', required: true, testedBy: 'sapRfcGuardConnectionTest' },
		],
		properties: [
			{
				displayName: 'Resource',
				name: 'resource',
				type: 'options',
				noDataExpression: true,
				options: [
					{ name: 'Connection', value: 'connection' },
					{ name: 'Read Operation', value: 'readOperation' },
					{ name: 'User Administration', value: 'userAdministration' },
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
				displayName: 'SAP Username',
				name: 'username',
				type: 'string',
				default: '',
				placeholder: 'N8N_DEMO_01',
				description: '1-12 uppercase letters, numbers, or underscores; the sidecar also enforces its configured prefix',
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
				displayOptions: { show: { resource: ['readOperation', 'userAdministration'] } },
			},
			{
				displayName: 'Row Limit',
				name: 'limit',
				type: 'number',
				typeOptions: { minValue: 1, maxValue: 1000 },
				default: 50,
				description: 'Max number of results to return',
				displayOptions: { show: { resource: ['readOperation'] } },
			},
			{
				displayName: 'Include Result Metadata',
				name: 'includeMetadata',
				type: 'boolean',
				default: true,
				description: 'Whether to include operation, correlation, limit, and read-only evidence',
				displayOptions: { show: { resource: ['readOperation'] } },
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
						return { status: 'OK', message: 'Governed SAP user-provisioning sidecar connection successful' };
					}
					sanitizeHealthResponse(result);
					return { status: 'OK', message: 'Read-only RFC sidecar connection successful' };
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
					const result = await testSidecarConnection(
						credentials,
						httpRequest,
						randomUUID(),
					);
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
					enforceSerializedByteLimit(parameters, Number(credentials.maxRequestBytes), 'Operation parameters');
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
					enforceSerializedByteLimit(response, Number(credentials.maxResponseBytes), 'Sidecar response');
					outputItems.push({
						json: sanitizeWriteResponse(response, operation, traceId, allowedFields) as IDataObject,
						pairedItem: { item: itemIndex },
					});
					continue;
				}

				const operation = assertOperationId(
					this.getNodeParameter('businessOperationId', itemIndex) as string,
				);
				const allowedOperations = parseAllowedOperations(credentials.allowedOperations);
				assertOperationAllowed(operation, allowedOperations);
				const fieldPolicies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
				const allowedFields = allowedDataFieldsForOperation(operation, fieldPolicies);
				const parameters = parseParametersJson(
					this.getNodeParameter('parametersJson', itemIndex, '{}') as string,
				);
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
					this.getNodeParameter('limit', itemIndex, 50) as number,
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
						json: { error: error instanceof Error ? error.message : String(error) },
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
