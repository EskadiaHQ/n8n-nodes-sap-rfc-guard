import { OperationalError } from 'n8n-workflow';

import { normalizeBaseUrl } from './governance';
import type {
	RfcGuardHttpRequest,
	RfcGuardRequestOptions,
	SapRfcGuardCredentials,
} from './types';

function redactError(error: unknown, credentials: SapRfcGuardCredentials): Error {
	const original = error instanceof Error ? error.message : String(error);
	const secrets = [credentials.apiToken].filter(Boolean);
	const redacted = secrets.reduce(
		(message, secret) => message.split(secret).join('[REDACTED]'),
		original,
	);
	return new Error(`SAP RFC sidecar request failed: ${redacted}`);
}

function requestOptions(
	credentials: SapRfcGuardCredentials,
	method: 'GET' | 'POST',
	path: string,
	correlationId: string,
	body?: Record<string, unknown>,
): RfcGuardRequestOptions {
	const baseUrl = normalizeBaseUrl(credentials.baseUrl, credentials.allowInsecureHttp === true);
	return {
		method,
		url: `${baseUrl}${path}`,
		headers: {
			Accept: 'application/json',
			Authorization: `Bearer ${credentials.apiToken}`,
			'Content-Type': 'application/json',
			'X-Correlation-ID': correlationId,
			'X-RFC-Guard-Mode': 'read-only',
		},
		...(body ? { body } : {}),
		json: true,
		timeout: method === 'GET' ? Number(credentials.connectionTimeout) : Number(credentials.requestTimeout),
		skipSslCertificateValidation: credentials.rejectUnauthorized === false,
	};
}

async function performRequest(
	httpRequest: RfcGuardHttpRequest,
	options: RfcGuardRequestOptions,
	credentials: SapRfcGuardCredentials,
): Promise<unknown> {
	try {
		return await httpRequest(options);
	} catch (error) {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError(redactError(error, credentials).message);
	}
}

export async function testSidecarConnection(
	credentials: SapRfcGuardCredentials,
	httpRequest: RfcGuardHttpRequest,
	correlationId: string,
): Promise<unknown> {
	return await performRequest(
		httpRequest,
		requestOptions(credentials, 'GET', '/v1/health', correlationId),
		credentials,
	);
}

export async function executeApprovedOperation(
	credentials: SapRfcGuardCredentials,
	httpRequest: RfcGuardHttpRequest,
	operation: string,
	parameters: Record<string, unknown>,
	correlationId: string,
): Promise<unknown> {
	return await performRequest(
		httpRequest,
		requestOptions(
			credentials,
			'POST',
			`/v1/operations/${encodeURIComponent(operation)}/execute`,
			correlationId,
			{
				operation,
				parameters,
				context: {
					client: 'n8n-sap-rfc-guard',
					readOnly: true,
				},
			},
		),
		credentials,
	);
}
