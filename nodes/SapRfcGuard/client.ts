import { randomUUID } from 'node:crypto';

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
	mode: 'read-only' | 'business-operations' | 'user-provisioning' = 'read-only',
	body?: Record<string, unknown>,
): RfcGuardRequestOptions {
	const baseUrl = normalizeBaseUrl(credentials.baseUrl, credentials.allowInsecureHttp === true);
	const tokenHeader: Record<string, string> = {};
	if (credentials.headerMode === 'xRfcGuardToken') {
		tokenHeader['X-RFC-Guard-Token'] = credentials.apiToken;
	} else {
		tokenHeader.Authorization = `Bearer ${credentials.apiToken}`;
	}
	return {
		method,
		url: `${baseUrl}${path}`,
		headers: {
			Accept: 'application/json',
			...tokenHeader,
			'Content-Type': 'application/json',
			'X-Correlation-ID': correlationId,
			'X-RFC-Guard-Mode': mode,
		},
		...(body ? { body } : {}),
		json: true,
		timeout: method === 'GET' ? Number(credentials.connectionTimeout) : Number(credentials.requestTimeout),
		skipSslCertificateValidation: credentials.rejectUnauthorized === false,
	};
}

type FailureReason =
	| 'connection_refused' | 'dns_failure' | 'timeout' | 'connection_reset'
	| 'http_502' | 'http_503' | 'http_504' | 'http_error' | 'sap_error'
	| 'transport_error' | 'budget_exhausted';

function record(value: unknown): Record<string, unknown> {
	return typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
}

function errorFacts(error: unknown): {
	reason: FailureReason;
	beforeSend: boolean;
	transientRead: boolean;
} {
	const chain: Record<string, unknown>[] = [];
	let current: unknown = error;
	for (let depth = 0; depth < 4 && typeof current === 'object' && current !== null; depth++) {
		const item = record(current);
		if (chain.includes(item)) break;
		chain.push(item);
		current = item.cause;
	}
	// A received response or redirect makes a later connect/DNS error ambiguous for writes.
	const hasResponse = chain.some(item => item.response !== undefined || item.statusCode !== undefined
		|| item.status !== undefined || item.httpCode !== undefined);
	const redirected = chain.some(item => {
		const request = record(item.request);
		return Number(request._redirectCount ?? record(request._redirectable)._redirectCount ?? 0) > 0;
	});
	const status = chain.map(item => Number(record(item.response).status ?? item.statusCode
		?? item.status ?? item.httpCode)).find(value => Number.isInteger(value) && value >= 100);
	const sapFailure = chain.some(item => {
		const code = record(record(item.response).data).error;
		return typeof code === 'string' && code.startsWith('SAP_');
	});
	if (status !== undefined) {
		const reason: FailureReason = status === 502 ? 'http_502' : status === 503 ? 'http_503'
			: status === 504 ? 'http_504' : 'http_error';
		return { reason, beforeSend: false, transientRead: [502, 503, 504].includes(status) };
	}
	if (sapFailure) return { reason: 'sap_error', beforeSend: false, transientRead: false };
	const refused = chain.some(item => item.code === 'ECONNREFUSED' && item.syscall === 'connect');
	const dns = chain.some(item => ['ENOTFOUND', 'EAI_AGAIN'].includes(String(item.code))
		&& item.syscall === 'getaddrinfo');
	if (refused || dns) return {
		reason: refused ? 'connection_refused' : 'dns_failure',
		beforeSend: !hasResponse && !redirected,
		transientRead: !hasResponse,
	};
	const codes = chain.map(item => item.code);
	if (codes.some(code => ['ETIMEDOUT', 'ESOCKETTIMEDOUT', 'ECONNABORTED'].includes(String(code)))) {
		return { reason: 'timeout', beforeSend: false, transientRead: !hasResponse };
	}
	if (codes.includes('ECONNRESET')) return {
		reason: 'connection_reset', beforeSend: false, transientRead: !hasResponse,
	};
	return { reason: 'transport_error', beforeSend: false, transientRead: false };
}

function legacyScope(options: RfcGuardRequestOptions, credentials: SapRfcGuardCredentials): boolean {
	const url = new URL(options.url);
	return credentials.sidecarMode === 'userProvisioning' && url.protocol === 'https:'
		&& url.hostname === 'sap-rfc-provision-jco' && url.port === '8080';
}

function safeCorrelationId(value: string | undefined): string {
	return value && (/^[a-f0-9]{32}$/i.test(value)
		|| /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(value))
		? value : randomUUID();
}

async function performLegacyRequest(
	httpRequest: RfcGuardHttpRequest,
	options: RfcGuardRequestOptions,
	credentials: SapRfcGuardCredentials,
): Promise<unknown> {
	const started = performance.now();
	const budget = options.timeout;
	const correlationId = safeCorrelationId(options.headers['X-Correlation-ID']);
	const path = new URL(options.url).pathname;
	const health = options.method === 'GET' && path === '/v1/health';
	const create = options.method === 'POST'
		&& path === '/v1/operations/createSu01CommunicationUser/execute'
		&& options.body?.operation === 'createSu01CommunicationUser'
		&& record(options.body.context).write === true
		&& record(options.body.context).readOnly === false;
	const operation = health ? 'health' : create ? 'createSu01CommunicationUser' : 'unsupported_operation';
	let attempts = 0;
	let lastError: unknown = new Error('SAP request time budget exhausted');
	let reason: FailureReason = 'budget_exhausted';
	while (attempts < 2) {
		const remaining = Math.floor(budget - (performance.now() - started));
		if (remaining <= 0) { reason = 'budget_exhausted'; break; }
		attempts++;
		try {
			// n8n may follow redirects manually, hiding prior delivery from the last error.
			// The operated legacy endpoint is direct; never resend its POST to a redirect.
			const request = { ...options, timeout: remaining, disableFollowRedirect: true };
			return await httpRequest(request);
		} catch (error) {
			lastError = error;
			const facts = errorFacts(error);
			reason = facts.reason;
			const retry = (health && facts.transientRead) || (create && facts.beforeSend);
			if (attempts >= 2 || !retry) break;
			if (budget - (performance.now() - started) <= 1000) {
				reason = 'budget_exhausted';
				break;
			}
			await new Promise<void>(resolve => setTimeout(resolve, 1000));
		}
	}
	// One operated, secret-free marker per final legacy failure; DEV log monitoring reads it.
	// eslint-disable-next-line no-console
	console.error('SAP_PROVISIONER_FAILURE ' + JSON.stringify({
		service: 'legacy', operation, correlationId, attempts, reason,
	}));
	throw new OperationalError(redactError(lastError, credentials).message);
}

async function performRequest(
	httpRequest: RfcGuardHttpRequest,
	options: RfcGuardRequestOptions,
	credentials: SapRfcGuardCredentials,
): Promise<unknown> {
	if (legacyScope(options, credentials)) return await performLegacyRequest(httpRequest, options, credentials);
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
			credentials.sidecarMode === 'businessOperations' ? 'business-operations' : 'read-only',
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

export async function executeApprovedBusinessWriteOperation(
	credentials: SapRfcGuardCredentials,
	httpRequest: RfcGuardHttpRequest,
	operation: string,
	parameters: Record<string, unknown>,
	correlationId: string,
	confirmation: string,
): Promise<unknown> {
	return await performRequest(
		httpRequest,
		requestOptions(
			credentials,
			'POST',
			`/v1/operations/${encodeURIComponent(operation)}/execute`,
			correlationId,
			'business-operations',
			{
				operation,
				parameters,
				context: {
					client: 'n8n-sap-rfc-guard',
					readOnly: false,
					write: true,
					confirmation,
				},
			},
		),
		credentials,
	);
}

export async function executeApprovedWriteOperation(
	credentials: SapRfcGuardCredentials,
	httpRequest: RfcGuardHttpRequest,
	operation: string,
	parameters: Record<string, unknown>,
	correlationId: string,
	confirmation: string,
): Promise<unknown> {
	return await performRequest(
		httpRequest,
		requestOptions(
			credentials,
			'POST',
			`/v1/operations/${encodeURIComponent(operation)}/execute`,
			correlationId,
			'user-provisioning',
			{
				operation,
				parameters,
				context: {
					client: 'n8n-sap-rfc-guard',
					readOnly: false,
					write: true,
					confirmation,
				},
			},
		),
		credentials,
	);
}

/**
 * Calls a /v1/catalog route and turns sidecar refusals into readable errors that keep the
 * sidecar code (for example PARAMETER_UNKNOWN or SAP_RFC_NOT_AUTHORIZED) and safe details.
 */
export async function catalogRequest(
	credentials: SapRfcGuardCredentials,
	httpRequest: RfcGuardHttpRequest,
	method: 'GET' | 'POST',
	path: string,
	correlationId: string,
	body?: Record<string, unknown>,
): Promise<Record<string, unknown>> {
	const options: RfcGuardRequestOptions = {
		...requestOptions(
			credentials,
			method,
			path,
			correlationId,
			credentials.sidecarMode === 'businessOperations' ? 'business-operations' : 'read-only',
			body,
		),
		timeout: Number(credentials.requestTimeout),
		ignoreHttpStatusErrors: true,
		returnFullResponse: true,
	};
	let response: unknown;
	try {
		response = await httpRequest(options);
	} catch (error) {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError(redactError(error, credentials).message);
	}
	const full = record(response);
	const status = Number(full.statusCode ?? 200);
	const payload = record(full.body !== undefined ? full.body : response);
	if (status >= 400) {
		const code = typeof payload.error === 'string' ? payload.error : `HTTP_${status}`;
		const message = typeof payload.message === 'string'
			? payload.message : 'The RFC sidecar rejected the catalog request.';
		const details = payload.details && typeof payload.details === 'object'
			? ` Details: ${JSON.stringify(payload.details)}` : '';
		throw new OperationalError(`RFC Guard ${code}: ${message}${details}`);
	}
	return payload;
}
