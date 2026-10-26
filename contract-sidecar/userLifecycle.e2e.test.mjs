import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import net from 'node:net';
import { after, before, describe, it } from 'node:test';

import nodeModule from '../nodes/SapRfcGuard/SapRfcGuard.node.ts';

// The node source is CommonJS; read the class from its module object.
const { SapRfcGuard } = nodeModule;

const token = 'RFC_GUARD_PROVISIONING_FIXTURE_ONLY_01';
const operations = [
	'createSu01CommunicationUser',
	'lockSu01User',
	'unlockSu01User',
	'resetSu01UserPassword',
];
const fieldPolicies = {
	createSu01CommunicationUser: ['username', 'created', 'verified', 'accountStatus', 'passwordReturned'],
	lockSu01User: ['username', 'action', 'verified', 'lockStatus', 'passwordReturned'],
	unlockSu01User: ['username', 'action', 'verified', 'lockStatus', 'passwordReturned'],
	resetSu01UserPassword: [
		'username', 'action', 'verified', 'passwordState', 'mustChangeAtNextLogon', 'passwordReturned',
	],
};
let child;
let baseUrl;

async function availablePort() {
	return await new Promise((resolve, reject) => {
		const server = net.createServer();
		server.once('error', reject);
		server.listen(0, '127.0.0.1', () => {
			const address = server.address();
			server.close(() => resolve(typeof address === 'object' && address ? address.port : 0));
		});
	});
}

function fakeContext(parameters) {
	const credentials = {
		sidecarMode: 'userProvisioning',
		baseUrl,
		apiToken: token,
		headerMode: 'bearer',
		allowedOperations: operations.join(', '),
		dataFieldPoliciesJson: JSON.stringify(fieldPolicies),
		allowUserCreation: true,
		rejectUnauthorized: true,
		allowInsecureHttp: true,
		allowAiTool: false,
		maxRows: 50,
		maxRequestBytes: 65536,
		maxResponseBytes: 262144,
		connectionTimeout: 5000,
		requestTimeout: 10000,
	};
	return {
		getInputData: () => [{ json: {} }],
		getNodeParameter: (name, _index, fallback) =>
			name in parameters ? parameters[name] : fallback,
		getCredentials: async () => credentials,
		getNode: () => ({ type: 'n8n-nodes-sap-rfc-guard.sapRfcGuard', name: 'RFC Guard' }),
		continueOnFail: () => false,
		helpers: {
			httpRequest: async (options) => {
				const response = await fetch(options.url, {
					method: options.method,
					headers: options.headers,
					...(options.body ? { body: JSON.stringify(options.body) } : {}),
				});
				const body = await response.json();
				if (!response.ok) throw new Error(`HTTP ${response.status} ${JSON.stringify(body)}`);
				return body;
			},
		},
	};
}

async function run(parameters) {
	const node = new SapRfcGuard();
	const context = fakeContext(parameters);
	// The fake implements only the IExecuteFunctions members the node uses.
	const [items] = await node.execute.call(context);
	return items[0].json;
}

describe('governed user lifecycle example against the provisioning fixture', () => {
	before(async () => {
		const port = await availablePort();
		baseUrl = `http://127.0.0.1:${port}`;
		child = spawn(process.execPath, ['contract-sidecar/server.mjs'], {
			cwd: new URL('..', import.meta.url),
			env: {
				...process.env,
				PORT: String(port),
				CONTRACT_MODE: 'user-provisioning',
				CONTRACT_TOKEN: token,
			},
			stdio: ['ignore', 'pipe', 'pipe'],
		});
		await new Promise((resolve, reject) => {
			const timer = setTimeout(() => reject(new Error('fixture did not start')), 5000);
			child.stdout?.on('data', (chunk) => {
				if (chunk.toString().includes('listening')) {
					clearTimeout(timer);
					resolve();
				}
			});
		});
	});

	after(() => child?.kill('SIGTERM'));

	it('runs the four steps of the published example workflow in order', async () => {
		const workflow = JSON.parse(
			readFileSync(
				new URL('../examples/su01/user-admin/user-lifecycle-contract.json', import.meta.url),
				'utf8',
			),
		);
		const steps = workflow.nodes
			.filter((node) => node.type === 'n8n-nodes-sap-rfc-guard.sapRfcGuard')
			.sort((left, right) => left.name.localeCompare(right.name));
		assert.equal(steps.length, 4);

		const created = await run(steps[0].parameters);
		assert.equal(created.created, true);
		const locked = await run(steps[1].parameters);
		assert.equal(locked.lockStatus, 'LockedByAdministrator');
		const unlocked = await run(steps[2].parameters);
		assert.equal(unlocked.lockStatus, 'Unlocked');
		const reset = await run(steps[3].parameters);
		assert.equal(reset.passwordState, 'initial');
		assert.equal(reset.mustChangeAtNextLogon, true);
		for (const result of [created, locked, unlocked, reset]) {
			assert.equal(result.passwordReturned, false);
			assert.equal(result._rfc.writeOperation, true);
			assert.equal(result._rfc.syntheticData, true);
		}
	});

	it('rejects a step whose confirmation does not match its operation', async () => {
		await assert.rejects(
			run({
				resource: 'userAdministration',
				operation: 'resetPassword',
				username: 'N8N_DEMO_00',
				writeConfirmation: 'UNLOCK N8N_DEMO_00',
			}),
			/Confirmation must be exactly RESET N8N_DEMO_00/,
		);
	});
});
