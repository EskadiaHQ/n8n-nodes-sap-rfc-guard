import { OperationalError } from 'n8n-workflow';

import type { RfcGuardMetadata } from './types';

function asRecord(value: unknown, label: string): Record<string, unknown> {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw new OperationalError(`${label} must be a JSON object.`);
	}
	return value as Record<string, unknown>;
}

function projectRecord(
	row: Record<string, unknown>,
	allowedFields: string[],
): Record<string, unknown> {
	const projected: Record<string, unknown> = {};
	for (const field of allowedFields) {
		if (Object.prototype.hasOwnProperty.call(row, field)) projected[field] = row[field];
	}
	return projected;
}

function readOnlyAttestation(response: Record<string, unknown>): true {
	const meta = asRecord(response.meta, 'Sidecar response meta');
	if (meta.readOnly !== true) {
		throw new OperationalError('Sidecar response did not attest readOnly=true.');
	}
	return true;
}

function safeBackend(value: unknown): RfcGuardMetadata['backend'] | undefined {
	if (value === undefined) return undefined;
	const backend = asRecord(value, 'Sidecar response backend');
	const systemId = String(backend.systemId ?? '');
	const client = String(backend.client ?? '');
	const release = String(backend.release ?? '');
	if (!/^[A-Za-z0-9_-]{1,16}$/.test(systemId)) {
		throw new OperationalError('Sidecar backend system ID is invalid.');
	}
	if (!/^[0-9]{3}$/.test(client)) {
		throw new OperationalError('Sidecar backend client is invalid.');
	}
	if (!/^[A-Za-z0-9._-]{1,32}$/.test(release)) {
		throw new OperationalError('Sidecar backend release is invalid.');
	}
	return { systemId, client, release };
}

function assertHealthyStatus(response: Record<string, unknown>): 'ok' | 'healthy' {
	if (response.status !== 'ok' && response.status !== 'healthy') {
		throw new OperationalError('The configured sidecar did not report a healthy status.');
	}
	return response.status;
}

export function sanitizeHealthResponse(value: unknown): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar health response');
	const status = assertHealthyStatus(response);
	const capabilities = asRecord(response.capabilities, 'Sidecar health capabilities');
	if (capabilities.readOnly !== true) {
		throw new OperationalError('The configured sidecar does not advertise read-only capability.');
	}
	const rawOperations = capabilities.operations;
	const operations = Array.isArray(rawOperations)
		? rawOperations.map(String).filter((operation) => /^[a-z][a-zA-Z0-9.-]{0,63}$/.test(operation))
		: [];
	const backend = safeBackend(response.backend);
	return {
		connected: true,
		status,
		service: String(response.service ?? 'sap-rfc-sidecar'),
		version: String(response.version ?? 'unknown'),
		readOnly: true,
		operations,
		...(backend ? { backend } : {}),
	};
}

export function sanitizeProvisioningHealthResponse(value: unknown): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar health response');
	const status = assertHealthyStatus(response);
	const capabilities = asRecord(response.capabilities, 'Sidecar health capabilities');
	if (capabilities.readOnly !== false || capabilities.writeEnabled !== true) {
		throw new OperationalError('The configured sidecar does not advertise user-provisioning capability.');
	}
	const rawOperations = capabilities.operations;
	const operations = Array.isArray(rawOperations)
		? rawOperations.map(String).filter((operation) => /^[a-z][a-zA-Z0-9.-]{0,63}$/.test(operation))
		: [];
	const writeAliases = [
		'createSu01CommunicationUser',
		'resetSu01UserPassword',
		'lockSu01User',
		'unlockSu01User',
	];
	if (!operations.some((operation) => writeAliases.includes(operation))) {
		throw new OperationalError('The sidecar does not advertise any governed user-administration alias.');
	}
	const backend = safeBackend(response.backend);
	return {
		connected: true,
		status,
		service: String(response.service ?? 'sap-rfc-sidecar'),
		version: String(response.version ?? 'unknown'),
		readOnly: false,
		writeEnabled: true,
		operations,
		...(backend ? { backend } : {}),
	};
}

export function sanitizeBusinessHealthResponse(value: unknown): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar health response');
	const status = assertHealthyStatus(response);
	const capabilities = asRecord(response.capabilities, 'Sidecar health capabilities');
	if (capabilities.readOnly !== false || capabilities.writeEnabled !== true) {
		throw new OperationalError('The configured sidecar does not advertise business-operation capability.');
	}
	const operations = Array.isArray(capabilities.operations)
		? capabilities.operations.map(String).filter((operation) => /^[a-z][a-zA-Z0-9.-]{0,63}$/.test(operation))
		: [];
	if (
		!operations.includes('createIncomingInvoice') &&
		!operations.includes('parkIncomingInvoice') &&
		!operations.includes('createGoodsReceipt')
	) {
		throw new OperationalError('The sidecar does not advertise a governed business-write alias.');
	}
	const backend = safeBackend(response.backend);
	return {
		connected: true,
		status,
		service: String(response.service ?? 'sap-rfc-sidecar'),
		version: String(response.version ?? 'unknown'),
		readOnly: false,
		writeEnabled: true,
		operations,
		...(backend ? { backend } : {}),
	};
}

export function sanitizeExecutionResponse(
	value: unknown,
	operation: string,
	correlationId: string,
	allowedFields: string[],
	requestedLimit: number,
	credentialLimit: number,
	includeMetadata: boolean,
): Record<string, unknown>[] {
	const response = asRecord(value, 'Sidecar execution response');
	readOnlyAttestation(response);
	if (response.operation !== operation) {
		throw new OperationalError('Sidecar response operation does not match the requested operation.');
	}
	if (
		response.correlationId !== undefined &&
		String(response.correlationId) !== correlationId
	) {
		throw new OperationalError('Sidecar response correlation ID does not match the request.');
	}

	const rawData = response.data;
	const records = Array.isArray(rawData)
		? rawData.map((row, index) => asRecord(row, `Sidecar data row ${index}`))
		: [asRecord(rawData, 'Sidecar data')];
	const limit = Math.min(requestedLimit, credentialLimit);
	const truncated = records.length > limit;
	const rows = records.slice(0, limit).map((row) => projectRecord(row, allowedFields));
	if (!includeMetadata) return rows;

	const rawMeta = asRecord(response.meta, 'Sidecar response meta');
	const metadata: RfcGuardMetadata = {
		operation,
		correlationId,
		rowCount: rows.length,
		rowLimit: limit,
		truncated,
		readOnly: true,
	};
	if (typeof rawMeta.source === 'string') metadata.source = rawMeta.source;
	if (typeof rawMeta.syntheticData === 'boolean') metadata.syntheticData = rawMeta.syntheticData;
	const backend = safeBackend(rawMeta.backend);
	if (backend) metadata.backend = backend;
	if (typeof rawMeta.durationMs === 'number') metadata.durationMs = rawMeta.durationMs;
	if (rows.length === 0) return [{ _rfc: metadata }];
	return rows.map((row, index) => (index === 0 ? { ...row, _rfc: metadata } : row));
}

export function sanitizeWriteResponse(
	value: unknown,
	operation: string,
	correlationId: string,
	allowedFields: string[],
): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar execution response');
	const meta = asRecord(response.meta, 'Sidecar response meta');
	if (meta.readOnly !== false || meta.write !== true) {
		throw new OperationalError('Sidecar response did not attest write=true and readOnly=false.');
	}
	if (response.operation !== operation) {
		throw new OperationalError('Sidecar response operation does not match the requested operation.');
	}
	if (response.correlationId !== undefined && String(response.correlationId) !== correlationId) {
		throw new OperationalError('Sidecar response correlation ID does not match the request.');
	}
	const rawData = Array.isArray(response.data) ? response.data[0] : response.data;
	const projected = projectRecord(asRecord(rawData, 'Sidecar data'), allowedFields);
	const backend = safeBackend(meta.backend);
	return {
		...projected,
		_rfc: {
			operation,
			correlationId,
			readOnly: false,
			writeOperation: true,
			...(typeof meta.source === 'string' ? { source: meta.source } : {}),
			...(typeof meta.syntheticData === 'boolean' ? { syntheticData: meta.syntheticData } : {}),
			...(backend ? { backend } : {}),
		},
	};
}

const CATALOG_SUMMARY_FIELDS = [
	'name', 'title', 'description', 'kind', 'bor', 'effect', 'transaction',
	'allowMetadata', 'allowExecute', 'tags',
];

function pick(source: Record<string, unknown>, keys: string[]): Record<string, unknown> {
	const output: Record<string, unknown> = {};
	for (const key of keys) if (source[key] !== undefined) output[key] = source[key];
	return output;
}

export function sanitizeCatalogList(value: unknown): {
	functions: Record<string, unknown>[];
	nextCursor: number | null;
	excluded: unknown[];
	policySha256: string;
} {
	const response = asRecord(value, 'Sidecar catalog response');
	if (!Array.isArray(response.functions)) {
		throw new OperationalError('Sidecar catalog response did not contain a function list.');
	}
	const page = asRecord(response.page ?? {}, 'Sidecar catalog page');
	const next = page.nextCursor === null || page.nextCursor === undefined ? null : Number(page.nextCursor);
	return {
		functions: response.functions.map((row) => pick(asRecord(row, 'Catalog entry'), CATALOG_SUMMARY_FIELDS)),
		nextCursor: Number.isInteger(next) ? next : null,
		excluded: Array.isArray(response.excluded) ? response.excluded : [],
		policySha256: String(response.policySha256 ?? ''),
	};
}

export function sanitizeCatalogSignature(value: unknown, functionName: string): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar catalog signature');
	if (response.name !== functionName) {
		throw new OperationalError('Sidecar catalog signature does not match the requested function.');
	}
	const backend = safeBackend(response.backend);
	return {
		...pick(response, [
			...CATALOG_SUMMARY_FIELDS, 'remote', 'parameters', 'exceptions', 'policy', 'bapi',
			'documentation', 'permissions', 'signatureSource', 'policySha256', 'correlationId',
		]),
		...(backend ? { backend } : {}),
	};
}

export function sanitizeCatalogExecution(
	value: unknown,
	functionName: string,
	correlationId: string,
	includeMetadata: boolean,
): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar catalog response');
	const meta = asRecord(response.meta, 'Sidecar catalog response meta');
	if (meta.readOnly !== true || meta.write !== false) {
		throw new OperationalError('Sidecar catalog response did not attest a read-only call.');
	}
	if (meta.source !== 'sap-jco' || meta.syntheticData !== false) {
		throw new OperationalError('Sidecar catalog response is not evidence from SAP JCo.');
	}
	if (response.function !== functionName || meta.function !== functionName) {
		throw new OperationalError('Sidecar catalog response does not match the requested function.');
	}
	const output: Record<string, unknown> = pick(response, [
		'function', 'title', 'outcome', 'messages', 'exports', 'changing', 'tables', 'validatedParameters',
	]);
	if (includeMetadata) {
		const backend = safeBackend(meta.backend);
		output.meta = {
			...pick(meta, [
				'source', 'syntheticData', 'readOnly', 'write', 'dryRun', 'function', 'kind', 'kindVerified',
				'effect', 'transactionPolicy', 'transaction', 'rowLimit', 'tableRows', 'truncated',
				'policySha256', 'approval', 'signatureSource', 'executedAt', 'durationMs',
			]),
			correlationId: String(meta.correlationId ?? correlationId),
			...(backend ? { backend } : {}),
		};
	}
	return output;
}
