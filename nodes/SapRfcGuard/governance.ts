import { OperationalError } from 'n8n-workflow';

import type { SapRfcGuardCredentials } from './types';

const OPERATION_ID = /^[a-z][a-zA-Z0-9.-]{0,63}$/;
const DATA_FIELD = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/;
export const USER_CREATE_OPERATION = 'createSu01CommunicationUser';
export const USER_LOGON_RESET_OPERATION = 'resetSu01UserPassword';
export const USER_LOCK_OPERATION = 'lockSu01User';
export const USER_UNLOCK_OPERATION = 'unlockSu01User';

/** Governed write aliases served only by a dedicated user-provisioning sidecar. */
export const USER_WRITE_OPERATIONS: ReadonlyMap<string, { verb: string; nodeOperation: string }> =
	new Map([
		[USER_CREATE_OPERATION, { verb: 'CREATE', nodeOperation: 'createCommunicationUser' }],
		[USER_LOGON_RESET_OPERATION, { verb: 'RESET', nodeOperation: 'resetPassword' }],
		[USER_LOCK_OPERATION, { verb: 'LOCK', nodeOperation: 'lockUser' }],
		[USER_UNLOCK_OPERATION, { verb: 'UNLOCK', nodeOperation: 'unlockUser' }],
	]);

export function userWriteOperationForNodeOperation(nodeOperation: string): string {
	for (const [alias, definition] of USER_WRITE_OPERATIONS) {
		if (definition.nodeOperation === nodeOperation) return alias;
	}
	throw new OperationalError(`Unsupported user administration operation ${nodeOperation}.`);
}
export const INVOICE_CREATE_OPERATION = 'createIncomingInvoice';
export const INVOICE_PARK_OPERATION = 'parkIncomingInvoice';
export const GOODS_RECEIPT_CREATE_OPERATION = 'createGoodsReceipt';

export function parseAllowedOperations(value: string): Set<string> {
	const operations = String(value ?? '')
		.split(/[\s,]+/)
		.map((entry) => entry.trim())
		.filter(Boolean);
	if (operations.length === 0) {
		throw new OperationalError('Allowed Operations must contain at least one business operation.');
	}
	const unique = new Set<string>();
	for (const operation of operations) {
		assertOperationId(operation);
		if (unique.has(operation)) {
			throw new OperationalError(`Allowed Operations contains the duplicate ${operation}.`);
		}
		unique.add(operation);
	}
	return unique;
}

export function assertOperationId(value: string): string {
	const operation = String(value ?? '').trim();
	if (/^(BAPI|RFC|Z_|Y_)/i.test(operation)) {
		throw new OperationalError(
			'Operation ID must be a governed business alias, not a BAPI, RFC, Z, or Y function-module name.',
		);
	}
	if (!OPERATION_ID.test(operation)) {
		throw new OperationalError(
			'Operation ID must start with a lowercase letter and contain only letters, numbers, dots, or hyphens (maximum 64 characters).',
		);
	}
	return operation;
}

export function assertOperationAllowed(operation: string, allowed: Set<string>): void {
	if (!allowed.has(operation)) {
		throw new OperationalError(`Operation ${operation} is not allowed by these credentials.`);
	}
}

export function parseDataFieldPolicies(value: string): Map<string, string[]> {
	let parsed: unknown;
	try {
		parsed = JSON.parse(String(value ?? '').trim() || '{}');
	} catch {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError('Data Field Policies JSON must be valid JSON.');
	}
	if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
		throw new OperationalError('Data Field Policies JSON must be an operation-to-fields object.');
	}
	const policies = new Map<string, string[]>();
	for (const [operation, fields] of Object.entries(parsed)) {
		assertOperationId(operation);
		if (!Array.isArray(fields) || fields.length === 0) {
			throw new OperationalError(`Data field policy for ${operation} must be a non-empty array.`);
		}
		const normalized: string[] = [];
		for (const field of fields) {
			if (typeof field !== 'string' || !DATA_FIELD.test(field)) {
				throw new OperationalError(`Invalid data field in policy for ${operation}.`);
			}
			if (!normalized.includes(field)) normalized.push(field);
		}
		policies.set(operation, normalized);
	}
	return policies;
}

export function allowedDataFieldsForOperation(
	operation: string,
	policies: Map<string, string[]>,
): string[] {
	const fields = policies.get(operation);
	if (!fields) {
		throw new OperationalError(`No data field policy is configured for ${operation}.`);
	}
	return fields;
}

export function normalizeBaseUrl(
	value: string,
	allowInsecureHttp = false,
): string {
	let url: URL;
	try {
		url = new URL(String(value ?? '').trim());
	} catch {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError('Sidecar Base URL must be a valid absolute URL.');
	}
	if (url.username || url.password || url.search || url.hash) {
		throw new OperationalError(
			'Sidecar Base URL cannot contain credentials, query parameters, or a fragment.',
		);
	}
	if (url.protocol !== 'https:' && !(allowInsecureHttp && url.protocol === 'http:')) {
		throw new OperationalError('Sidecar Base URL must use HTTPS.');
	}
	url.pathname = url.pathname.replace(/\/+$/, '');
	return url.toString().replace(/\/$/, '');
}

function assertIntegerRange(value: unknown, label: string, minimum: number, maximum: number): void {
	const numeric = Number(value);
	if (!Number.isInteger(numeric) || numeric < minimum || numeric > maximum) {
		throw new OperationalError(`${label} must be an integer between ${minimum} and ${maximum}.`);
	}
}

export function validateGovernanceConfiguration(credentials: SapRfcGuardCredentials): void {
	normalizeBaseUrl(credentials.baseUrl, credentials.allowInsecureHttp === true);
	if (Buffer.byteLength(String(credentials.apiToken ?? ''), 'utf8') < 32) {
		throw new OperationalError('API Token must contain at least 32 bytes.');
	}
	const allowed = parseAllowedOperations(credentials.allowedOperations);
	const policies = parseDataFieldPolicies(credentials.dataFieldPoliciesJson);
	for (const operation of allowed) allowedDataFieldsForOperation(operation, policies);
	for (const operation of policies.keys()) {
		if (!allowed.has(operation)) {
			throw new OperationalError(
				`Data field policy ${operation} is not present in Allowed Operations.`,
			);
		}
	}
	const mode = credentials.sidecarMode ?? 'readOnly';
	const allowsUserWrite = [...allowed].some((operation) => USER_WRITE_OPERATIONS.has(operation));
	const businessWriteOperations = [
		INVOICE_CREATE_OPERATION,
		INVOICE_PARK_OPERATION,
		GOODS_RECEIPT_CREATE_OPERATION,
	];
	if (
		mode === 'readOnly' &&
		(allowsUserWrite || businessWriteOperations.some((operation) => allowed.has(operation)))
	) {
		throw new OperationalError('A read-only credential cannot allow a write operation.');
	}
	if (mode === 'businessOperations') {
		const allowsInvoice =
			allowed.has(INVOICE_CREATE_OPERATION) || allowed.has(INVOICE_PARK_OPERATION);
		const allowsGoodsReceipt = allowed.has(GOODS_RECEIPT_CREATE_OPERATION);
		if (allowsInvoice && credentials.allowIncomingInvoiceCreation !== true) {
			throw new OperationalError('Business operations require explicit invoice-creation opt-in.');
		}
		if (allowsGoodsReceipt && credentials.allowGoodsReceiptCreation !== true) {
			throw new OperationalError('Business operations require explicit goods-receipt opt-in.');
		}
		if (credentials.allowAiTool === true) {
			throw new OperationalError('Business-operation credentials cannot be enabled for AI tools.');
		}
		if ((!allowsInvoice && !allowsGoodsReceipt) || allowsUserWrite) {
			throw new OperationalError(
				'Business-operation credentials must allow an approved invoice or goods-receipt write and cannot provision users.',
			);
		}
	}
	if (mode === 'userProvisioning') {
		if (credentials.allowUserCreation !== true) {
			throw new OperationalError('User provisioning requires explicit credential opt-in.');
		}
		if (credentials.allowAiTool === true) {
			throw new OperationalError('User-provisioning credentials cannot be enabled for AI tools.');
		}
		if ([...allowed].some((operation) => !USER_WRITE_OPERATIONS.has(operation))) {
			throw new OperationalError(
				`User-provisioning credentials may allow only ${[...USER_WRITE_OPERATIONS.keys()].join(', ')}.`,
			);
		}
	}
	assertIntegerRange(credentials.connectionTimeout, 'Connection Timeout', 1000, 120000);
	assertIntegerRange(credentials.requestTimeout, 'Request Timeout', 1000, 300000);
	assertIntegerRange(credentials.maxRows, 'Maximum Rows', 1, 1000);
	assertIntegerRange(credentials.maxRequestBytes, 'Maximum Request Size', 1024, 1048576);
	assertIntegerRange(credentials.maxResponseBytes, 'Maximum Response Size', 1024, 5242880);
}

export function assertProvisioningCredential(credentials: SapRfcGuardCredentials): void {
	if ((credentials.sidecarMode ?? 'readOnly') !== 'userProvisioning') {
		throw new OperationalError('Select a dedicated User Provisioning credential for this operation.');
	}
	if (credentials.allowUserCreation !== true) {
		throw new OperationalError('The selected credential does not allow user creation.');
	}
}

export function assertBusinessOperationsCredential(
	credentials: SapRfcGuardCredentials,
	operation: 'invoice' | 'goodsReceipt' = 'invoice',
): void {
	if ((credentials.sidecarMode ?? 'readOnly') !== 'businessOperations') {
		throw new OperationalError('Select a dedicated Business Operations credential for this operation.');
	}
	if (operation === 'invoice' && credentials.allowIncomingInvoiceCreation !== true) {
		throw new OperationalError('The selected credential does not allow incoming-invoice creation.');
	}
	if (operation === 'goodsReceipt' && credentials.allowGoodsReceiptCreation !== true) {
		throw new OperationalError('The selected credential does not allow goods-receipt creation.');
	}
}

export function assertGoodsReceiptConfirmation(reference: string, confirmation: string): string {
	const normalized = reference.trim().toUpperCase();
	if (!/^[A-Z0-9._/-]{1,16}$/.test(normalized)) {
		throw new OperationalError('Goods-receipt reference must contain 1-16 safe characters.');
	}
	const expected = `POST GOODS RECEIPT ${normalized}`;
	if (confirmation.trim().toUpperCase() !== expected) {
		throw new OperationalError(`Confirmation must be exactly ${expected}.`);
	}
	return normalized;
}

export function assertInvoiceCreateConfirmation(reference: string, confirmation: string): string {
	return assertInvoiceWriteConfirmation(reference, confirmation, 'CREATE');
}

export function assertInvoiceParkConfirmation(reference: string, confirmation: string): string {
	return assertInvoiceWriteConfirmation(reference, confirmation, 'PARK');
}

function assertInvoiceWriteConfirmation(
	reference: string,
	confirmation: string,
	verb: 'CREATE' | 'PARK',
): string {
	const normalized = reference.trim().toUpperCase();
	if (!/^[A-Z0-9._/-]{1,16}$/.test(normalized)) {
		throw new OperationalError('Invoice reference must contain 1-16 safe characters.');
	}
	const expected = `${verb} INVOICE ${normalized}`;
	if (confirmation.trim().toUpperCase() !== expected) {
		throw new OperationalError(`Confirmation must be exactly ${expected}.`);
	}
	return normalized;
}

export function assertWriteConfirmation(
	operation: string,
	username: string,
	confirmation: string,
): { username: string; confirmation: string } {
	const definition = USER_WRITE_OPERATIONS.get(operation);
	if (!definition) {
		throw new OperationalError(`Operation ${operation} is not a governed user-administration alias.`);
	}
	const normalizedUsername = username.trim().toUpperCase();
	if (!/^[A-Z0-9_]{1,12}$/.test(normalizedUsername)) {
		throw new OperationalError('SAP username must contain 1-12 uppercase letters, numbers, or underscores.');
	}
	const expected = `${definition.verb} ${normalizedUsername}`;
	if (confirmation.trim().toUpperCase() !== expected) {
		throw new OperationalError(`Confirmation must be exactly ${expected}.`);
	}
	return { username: normalizedUsername, confirmation: expected };
}

export function assertCreateConfirmation(username: string, confirmation: string): string {
	return assertWriteConfirmation(USER_CREATE_OPERATION, username, confirmation).username;
}

export function parseParametersJson(value: string): Record<string, unknown> {
	let parsed: unknown;
	try {
		parsed = JSON.parse(String(value ?? '').trim() || '{}');
	} catch {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError('Parameters JSON must be valid JSON.');
	}
	if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
		throw new OperationalError('Parameters JSON must be an object.');
	}
	return parsed as Record<string, unknown>;
}

export function enforceSerializedByteLimit(
	value: unknown,
	maximum: number,
	label: string,
): void {
	let serialized: string;
	try {
		serialized = JSON.stringify(value);
	} catch {
		// Converted to NodeOperationError at the execute boundary, which has node context.
		// eslint-disable-next-line @n8n/community-nodes/require-node-api-error
		throw new OperationalError(`${label} must be JSON serializable.`);
	}
	const bytes = Buffer.byteLength(serialized, 'utf8');
	if (bytes > maximum) {
		throw new OperationalError(`${label} is ${bytes} bytes and exceeds the ${maximum}-byte limit.`);
	}
}
