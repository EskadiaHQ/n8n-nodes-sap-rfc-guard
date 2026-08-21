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

export function sanitizeHealthResponse(value: unknown): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar health response');
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
		connected: response.status === 'ok' || response.status === 'healthy',
		status: String(response.status ?? 'unknown'),
		service: String(response.service ?? 'sap-rfc-sidecar'),
		version: String(response.version ?? 'unknown'),
		readOnly: true,
		operations,
		...(backend ? { backend } : {}),
	};
}

export function sanitizeProvisioningHealthResponse(value: unknown): Record<string, unknown> {
	const response = asRecord(value, 'Sidecar health response');
	const capabilities = asRecord(response.capabilities, 'Sidecar health capabilities');
	if (capabilities.readOnly !== false || capabilities.writeEnabled !== true) {
		throw new OperationalError('The configured sidecar does not advertise user-provisioning capability.');
	}
	const rawOperations = capabilities.operations;
	const operations = Array.isArray(rawOperations)
		? rawOperations.map(String).filter((operation) => /^[a-z][a-zA-Z0-9.-]{0,63}$/.test(operation))
		: [];
	if (!operations.includes('createSu01CommunicationUser')) {
		throw new OperationalError('The sidecar does not advertise the governed user-creation alias.');
	}
	const backend = safeBackend(response.backend);
	return {
		connected: response.status === 'ok' || response.status === 'healthy',
		status: String(response.status ?? 'unknown'),
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
