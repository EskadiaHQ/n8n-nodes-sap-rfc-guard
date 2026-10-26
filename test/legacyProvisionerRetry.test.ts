import assert from 'node:assert/strict';
import { describe, it, type TestContext } from 'node:test';

import {
	executeApprovedBusinessWriteOperation,
	executeApprovedOperation,
	executeApprovedWriteOperation,
	testSidecarConnection,
} from '../nodes/SapRfcGuard/client';
import type { RfcGuardHttpRequest, RfcGuardRequestOptions, SapRfcGuardCredentials } from '../nodes/SapRfcGuard/types';

const credentials: SapRfcGuardCredentials = {
	baseUrl: 'https://sap-rfc-provision-jco:8080', sidecarMode: 'userProvisioning',
	apiToken: 'fixture-secret-never-log', allowedOperations: 'createSu01CommunicationUser',
	dataFieldPoliciesJson: '{}', connectionTimeout: 15000, requestTimeout: 30000,
	maxRows: 1, maxRequestBytes: 65536, maxResponseBytes: 65536,
};
const trace = '28b87aae-ecf7-4db9-9633-cd21be8696cc';
const refused = () => Object.assign(new Error('private raw error'), { code: 'ECONNREFUSED', syscall: 'connect' });

function fixture(t: TestContext) {
	let now = 0;
	const sleeps: number[] = [];
	const logs: string[] = [];
	t.mock.method(performance, 'now', () => now);
	t.mock.method(globalThis, 'setTimeout', (callback: () => void, delay: number) => {
		sleeps.push(delay);
		now += delay;
		callback();
		return 0;
	});
	t.mock.method(console, 'error', (line: string) => logs.push(line));
	return { sleeps, logs, advance: (ms: number) => { now += ms; } };
}

function create(request: RfcGuardHttpRequest, correlation = trace, creds = credentials) {
	return executeApprovedWriteOperation(creds, request, 'createSu01CommunicationUser',
		{ username: 'PRIVATE_USER', email: 'private.student@example.invalid' }, correlation, 'CREATE PRIVATE_USER');
}

function marker(logs: string[]) {
	assert.equal(logs.length, 1);
	assert.ok(logs[0].startsWith('SAP_PROVISIONER_FAILURE '));
	return JSON.parse(logs[0].slice('SAP_PROVISIONER_FAILURE '.length));
}

describe('scoped legacy retry', { concurrency: false }, () => {
	it('retries a refused write once after 1s, preserving request and current total budget', async t => {
		const f = fixture(t);
		const calls: RfcGuardRequestOptions[] = [];
		await create(async options => {
			calls.push(options);
			if (calls.length === 1) { f.advance(250); throw refused(); }
			return { created: true };
		});
		assert.equal(calls.length, 2);
		assert.deepEqual(f.sleeps, [1000]);
		assert.equal(calls[0].timeout, 30000);
		assert.equal(calls[1].timeout, 28750);
		assert.equal((calls[0] as unknown as Record<string, unknown>).disableFollowRedirect, true);
		assert.equal((calls[1] as unknown as Record<string, unknown>).disableFollowRedirect, true);
		assert.equal(calls[1].body, calls[0].body);
		assert.equal(calls[1].headers['X-Correlation-ID'], trace);
		assert.deepEqual(f.logs, []);
	});

	for (const code of ['EAI_AGAIN', 'ENOTFOUND']) {
		it(`allows one retry only for an unequivocal pre-send DNS failure ${code}`, async t => {
			const f = fixture(t); let calls = 0;
			await create(async () => {
				if (++calls === 1) throw { cause: { code, syscall: 'getaddrinfo' } };
				return {};
			});
			assert.equal(calls, 2); assert.deepEqual(f.logs, []);
		});
	}

	it('stops at two failed attempts and logs one bounded marker', async t => {
		const f = fixture(t); let calls = 0;
		await assert.rejects(create(async () => { calls++; throw refused(); }));
		assert.equal(calls, 2); assert.deepEqual(f.sleeps, [1000]);
		assert.deepEqual(marker(f.logs), {
			service: 'legacy', operation: 'createSu01CommunicationUser', correlationId: trace,
			attempts: 2, reason: 'connection_refused',
		});
	});

	for (const [label, error] of [
		['timeout', { code: 'ETIMEDOUT', syscall: 'connect' }],
		['reset', { code: 'ECONNRESET' }],
		['EOF', { code: 'EOF' }],
		['502', { response: { status: 502, data: { error: 'SAP_WRITE_FAILED' } } }],
		['504', { response: { status: 504, data: { error: 'SAP_WRITE_TIMEOUT' } } }],
		['307 redirect', { response: { status: 307 } }],
		['308 redirect', { response: { status: 308 } }],
		['SAP error', { response: { data: { error: 'SAP_WRITE_FAILED' } } }],
		['refusal without pre-send evidence', { code: 'ECONNREFUSED' }],
		['DNS without pre-send evidence', { code: 'ENOTFOUND' }],
		['refusal after redirect', { code: 'ECONNREFUSED', syscall: 'connect', request: { _redirectable: { _redirectCount: 1 } } }],
		['refusal with response', { code: 'ECONNREFUSED', syscall: 'connect', response: {} }],
	] as const) {
		it(`does not repeat a write after ${label}`, async t => {
			const f = fixture(t); let calls = 0;
			await assert.rejects(create(async () => { calls++; throw error; }));
			assert.equal(calls, 1); assert.deepEqual(f.sleeps, []);
			assert.equal(marker(f.logs).attempts, 1);
		});
	}

	for (const error of [
		{ response: { status: 503, data: { error: 'SAP_CONNECTION_UNAVAILABLE' } } },
		{ response: { status: 502 } }, { response: { status: 504 } },
		{ code: 'ETIMEDOUT' }, { code: 'ECONNRESET' },
	]) {
		it('retries a classified transient health failure once', async t => {
			const f = fixture(t); let calls = 0;
			await testSidecarConnection(credentials, async () => {
				if (++calls === 1) throw error;
				return { status: 'ok' };
			}, trace);
			assert.equal(calls, 2); assert.deepEqual(f.sleeps, [1000]); assert.deepEqual(f.logs, []);
		});
	}

	for (const status of [301, 307, 400, 401, 403, 404, 422]) {
		it(`does not retry health HTTP ${status}`, async t => {
			const f = fixture(t); let calls = 0;
			await assert.rejects(testSidecarConnection(credentials, async () => {
				calls++; throw { response: { status } };
			}, trace));
			assert.equal(calls, 1); assert.deepEqual(f.sleeps, []);
			assert.equal(marker(f.logs).reason, 'http_error');
		});
	}

	it('does not retry TLS validation failures', async t => {
		const f = fixture(t); let calls = 0;
		await assert.rejects(testSidecarConnection(credentials, async () => {
			calls++; throw { code: 'SELF_SIGNED_CERT_IN_CHAIN' };
		}, trace));
		assert.equal(calls, 1); assert.deepEqual(f.sleeps, []); assert.equal(marker(f.logs).reason, 'transport_error');
	});

	it('does not sleep or issue another request when the current budget cannot fit the pause', async t => {
		const f = fixture(t); let calls = 0;
		await assert.rejects(create(async () => { calls++; f.advance(29500); throw refused(); }));
		assert.equal(calls, 1); assert.deepEqual(f.sleeps, []);
		assert.equal(marker(f.logs).reason, 'budget_exhausted');
	});

	it('checks the deadline again if the event loop wakes late', async t => {
		const f = fixture(t); let calls = 0;
		t.mock.method(globalThis, 'setTimeout', (callback: () => void) => { f.advance(30001); callback(); return 0; });
		await assert.rejects(create(async () => { calls++; throw refused(); }));
		assert.equal(calls, 1); assert.equal(marker(f.logs).reason, 'budget_exhausted');
	});

	it('replaces arbitrary correlation text and never logs payload, token or raw errors', async t => {
		const f = fixture(t);
		await assert.rejects(create(async () => { throw new Error(`PRIVATE_USER private.student@example.invalid ${credentials.apiToken}`); },
			'private.student@example.invalid'));
		const m = marker(f.logs);
		assert.match(m.correlationId, /^[a-f0-9-]{36}$/);
		for (const text of ['PRIVATE_USER', 'private.student', credentials.apiToken, 'Error:']) assert.ok(!f.logs.join('').includes(text));
		assert.deepEqual(Object.keys(m).sort(), ['attempts', 'correlationId', 'operation', 'reason', 'service']);
	});

	it('preserves a safe hex correlation identifier', async t => {
		const f = fixture(t); const hex = 'a'.repeat(32);
		await assert.rejects(create(async () => { throw new Error('failed'); }, hex));
		assert.equal(marker(f.logs).correlationId, hex);
	});

	it('does not retry unsupported operations even on the legacy host', async t => {
		const f = fixture(t); let calls = 0;
		await assert.rejects(executeApprovedOperation(credentials, async () => { calls++; throw refused(); },
			'getPurchaseOrderDetail', { purchaseOrder: 'PRIVATE_REFERENCE' }, trace));
		assert.equal(calls, 1); assert.equal(marker(f.logs).operation, 'unsupported_operation');
	});

	for (const changed of [
		{ baseUrl: 'https://sap-rfc-guard-jco:8080' },
		{ baseUrl: 'https://sap-rfc-provision-jco:443' },
		{ baseUrl: 'http://sap-rfc-provision-jco:8080', allowInsecureHttp: true },
		{ sidecarMode: 'businessOperations' as const },
	]) {
		it('leaves other services and modes on their existing one-call path', async t => {
			const f = fixture(t); let calls = 0;
			await assert.rejects(create(async () => { calls++; throw refused(); }, trace, { ...credentials, ...changed }));
			assert.equal(calls, 1); assert.deepEqual(f.sleeps, []); assert.deepEqual(f.logs, []);
		});
	}

	it('preserves the deployed business-write contract without retries or markers', async t => {
		const f = fixture(t); let captured: RfcGuardRequestOptions | undefined;
		await executeApprovedBusinessWriteOperation({ ...credentials, baseUrl: 'https://sap-rfc-guard-jco:8080', sidecarMode: 'businessOperations' },
			async options => { captured = options; return {}; }, 'parkIncomingInvoice', { reference: 'fixture' }, trace, 'PARK fixture');
		assert.equal(captured?.headers['X-RFC-Guard-Mode'], 'business-operations');
		assert.deepEqual(captured?.body?.context, { client: 'n8n-sap-rfc-guard', readOnly: false, write: true, confirmation: 'PARK fixture' });
		assert.deepEqual(f.logs, []);
	});
});
