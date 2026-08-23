import type { ICredentialType, INodeProperties } from 'n8n-workflow';

export class SapRfcGuardApi implements ICredentialType {
	name = 'sapRfcGuardApi';

	displayName = 'Logali SAP RFC Guard API';

	icon = 'file:sapRfcGuardCredential-v047.svg' as const;

	documentationUrl =
		'https://github.com/EskadiaHQ/n8n-nodes-sap-rfc-guard#credential-configuration';

	properties: INodeProperties[] = [
		{
			displayName: 'Sidecar Mode',
			name: 'sidecarMode',
			type: 'options',
			options: [
				{ name: 'Read Only', value: 'readOnly' },
				{ name: 'User Provisioning', value: 'userProvisioning' },
			],
			default: 'readOnly',
			description:
				'Use a separate sidecar and credential for provisioning; never reuse a read-only endpoint for writes',
		},
		{
			displayName: 'Sidecar Base URL',
			name: 'baseUrl',
			type: 'string',
			default: '',
			placeholder: 'https://sap-rfc.example.com',
			description: 'HTTPS base URL of the operated RFC sidecar; do not include an endpoint path',
			required: true,
		},
		{
			displayName: 'API Token',
			name: 'apiToken',
			type: 'string',
			typeOptions: { password: true },
			default: '',
			description: 'Random sidecar token containing at least 32 bytes',
			required: true,
		},
		{
			displayName: 'Token Header',
			name: 'headerMode',
			type: 'options',
			options: [
				{ name: 'Authorization: Bearer (Private Sidecar)', value: 'bearer' },
				{ name: 'X-RFC-Guard-Token (SAP BTP)', value: 'xRfcGuardToken' },
			],
			default: 'bearer',
			description:
				'Use the custom header for SAP BTP, where XSUAA reserves the Authorization bearer header',
		},
		{
			displayName: 'Allowed Operations',
			name: 'allowedOperations',
			type: 'string',
			typeOptions: { rows: 4 },
			default: '',
			placeholder: 'listSu01Users, getSu01UserDetail',
			description:
				'Comma- or line-separated business operation aliases. BAPI, RFC, Z, and Y function-module names are rejected.',
			required: true,
		},
		{
			displayName: 'Data Field Policies JSON',
			name: 'dataFieldPoliciesJson',
			type: 'string',
			typeOptions: { rows: 9 },
			default: '',
			placeholder:
				'{"listSu01Users":["username","userType","lastLogonAt","accountStatus"]}',
			description:
				'Required operation-to-fields map. Every returned data object is projected to these approved fields.',
			required: true,
		},
		{
			displayName: 'Allow Communication-User Creation',
			name: 'allowUserCreation',
			type: 'boolean',
			default: false,
			description:
				'Whether this credential can create a Communication user through the dedicated provisioning sidecar',
			displayOptions: { show: { sidecarMode: ['userProvisioning'] } },
		},
		{
			displayName: 'Validate TLS Certificate',
			name: 'rejectUnauthorized',
			type: 'boolean',
			default: true,
			description: 'Whether to reject a sidecar certificate that cannot be validated',
		},
		{
			displayName: 'Allow Insecure HTTP',
			name: 'allowInsecureHttp',
			type: 'boolean',
			default: false,
			description:
				'Allow plain HTTP only for an isolated local contract test. Keep disabled for every real SAP connection.',
		},
		{
			displayName: 'Allow AI Tool Use',
			name: 'allowAiTool',
			type: 'boolean',
			default: false,
			description:
				'Whether this credential may be attached to the SAP RFC Guard Tool variant. Provisioning credentials are always rejected as AI tools.',
		},
		{
			displayName: 'Maximum Rows',
			name: 'maxRows',
			type: 'number',
			typeOptions: { minValue: 1, maxValue: 1000 },
			default: 100,
			description: 'Credential-level maximum number of data rows returned by one operation',
		},
		{
			displayName: 'Maximum Request Size (Bytes)',
			name: 'maxRequestBytes',
			type: 'number',
			typeOptions: { minValue: 1024, maxValue: 1048576 },
			default: 65536,
			description: 'Maximum serialized size of operation parameters',
		},
		{
			displayName: 'Maximum Response Size (Bytes)',
			name: 'maxResponseBytes',
			type: 'number',
			typeOptions: { minValue: 1024, maxValue: 5242880 },
			default: 262144,
			description: 'Maximum serialized sidecar response size accepted by the node',
		},
		{
			displayName: 'Connection Timeout (ms)',
			name: 'connectionTimeout',
			type: 'number',
			typeOptions: { minValue: 1000, maxValue: 120000 },
			default: 15000,
			description: 'Maximum time allowed for the health and credential check',
		},
		{
			displayName: 'Operation Timeout (ms)',
			name: 'requestTimeout',
			type: 'number',
			typeOptions: { minValue: 1000, maxValue: 300000 },
			default: 30000,
			description: 'Maximum time allowed for one approved read operation',
		},
	];
}
