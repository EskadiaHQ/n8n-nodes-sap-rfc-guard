import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import net from 'node:net';
import { after, before, test } from 'node:test';

const token = 'RFC_GUARD_CONTRACT_FIXTURE';
let child;
let origin;

function availablePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
  });
}

function waitForReady(process) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Fixture did not start')), 5_000);
    process.once('exit', (code) => {
      clearTimeout(timeout);
      reject(new Error(`Fixture exited with code ${code}`));
    });
    process.stdout.on('data', (chunk) => {
      if (chunk.toString().includes('listening')) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
}

async function request(path, options = {}) {
  return fetch(`${origin}${path}`, {
    ...options,
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      ...(options.headers ?? {}),
    },
  });
}

function operationBody(operation, parameters) {
  return JSON.stringify({ operation, parameters, context: { readOnly: true } });
}

before(async () => {
  const port = await availablePort();
  origin = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['contract-sidecar/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: { ...process.env, PORT: String(port), SAP_CLIENT: '100' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await waitForReady(child);
});

after(() => child?.kill('SIGTERM'));

test('health exposes only governed read operations', async () => {
  const response = await request('/v1/health');
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.capabilities.readOnly, true);
  assert.deepEqual(body.capabilities.operations.sort(), [
    'getSu01UserDetail',
    'listSu01RiskAccounts',
    'listSu01Users',
    'summarizeSu01Accounts',
  ]);
});

test('rejects unknown parameters and a client mismatch', async () => {
  const unknown = await request('/v1/operations/listSu01Users/execute', {
    method: 'POST',
    body: operationBody('listSu01Users', { table: 'USR02' }),
  });
  assert.equal(unknown.status, 400);
  assert.equal((await unknown.json()).error, 'PARAMETER_NOT_ALLOWED');

  const mismatch = await request('/v1/operations/listSu01Users/execute', {
    method: 'POST',
    body: operationBody('listSu01Users', { client: '200' }),
  });
  assert.equal(mismatch.status, 400);
  assert.equal((await mismatch.json()).error, 'CLIENT_MISMATCH');
});

test('applies maxRows before returning synthetic data', async () => {
  const response = await request('/v1/operations/listSu01Users/execute', {
    method: 'POST',
    body: operationBody('listSu01Users', { client: '100', maxRows: 2 }),
  });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.data.length, 2);
  assert.equal(body.meta.rowCount, 2);
  assert.equal(body.meta.syntheticData, true);
  assert.equal(body.data[0].lastLogonAt.endsWith('Z'), false);
});
