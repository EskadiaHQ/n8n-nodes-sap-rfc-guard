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
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

function waitForReady(process) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Fixture did not start')), 5_000);
    process.once('exit', (code) => { clearTimeout(timeout); reject(new Error(`Fixture exited with code ${code}`)); });
    process.stdout.on('data', (chunk) => {
      if (chunk.toString().includes('listening')) { clearTimeout(timeout); resolve(); }
    });
  });
}

const verbs = {
  createSu01CommunicationUser: 'CREATE',
  resetSu01UserPassword: 'RESET',
  lockSu01User: 'LOCK',
  unlockSu01User: 'UNLOCK',
};

async function write(operation, parameters, confirmation = `${verbs[operation]} ${parameters.username}`) {
  const response = await fetch(`${origin}/v1/operations/${operation}/execute`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      'x-rfc-guard-mode': 'user-provisioning',
    },
    body: JSON.stringify({ operation, parameters, context: { readOnly: false, write: true, confirmation } }),
  });
  const text = await response.text();
  return { status: response.status, text, body: JSON.parse(text) };
}

before(async () => {
  const port = await availablePort();
  origin = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, ['contract-sidecar/server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: { ...process.env, PORT: String(port), SAP_CLIENT: '100', CONTRACT_MODE: 'user-provisioning' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await waitForReady(child);
});

after(() => child?.kill('SIGTERM'));

test('provisioning health advertises only the governed write aliases', async () => {
  const response = await fetch(`${origin}/v1/health`, { headers: { authorization: `Bearer ${token}` } });
  const body = await response.json();
  assert.equal(body.capabilities.readOnly, false);
  assert.equal(body.capabilities.writeEnabled, true);
  assert.deepEqual([...body.capabilities.operations].sort(), Object.keys(verbs).sort());
});

test('creates, locks, unlocks and resets a prefixed synthetic user without returning a password', async () => {
  const created = await write('createSu01CommunicationUser', {
    username: 'N8N_DEMO_01', firstName: 'Ana', lastName: 'Demo', validDays: 3,
  });
  assert.equal(created.status, 200);
  assert.equal(created.body.data.created, true);
  assert.equal(created.body.meta.write, true);
  assert.equal(created.body.meta.syntheticData, true);

  const locked = await write('lockSu01User', { username: 'N8N_DEMO_01' });
  assert.equal(locked.body.data.lockStatus, 'LockedByAdministrator');
  const unlocked = await write('unlockSu01User', { username: 'N8N_DEMO_01' });
  assert.equal(unlocked.body.data.lockStatus, 'Unlocked');
  const reset = await write('resetSu01UserPassword', { username: 'N8N_DEMO_01' });
  assert.equal(reset.body.data.passwordState, 'initial');
  assert.equal(reset.body.data.mustChangeAtNextLogon, true);
  for (const result of [created, locked, unlocked, reset]) {
    assert.equal(result.body.data.passwordReturned, false);
    assert.doesNotMatch(result.text, /"(password|initialPassword|bapipwd)"\s*:/i);
  }
});

test('requires the verb of the requested operation and the governed prefix', async () => {
  const wrongVerb = await write('resetSu01UserPassword', { username: 'N8N_DEMO_00' }, 'CREATE N8N_DEMO_00');
  assert.equal(wrongVerb.status, 403);
  const outsidePrefix = await write('lockSu01User', { username: 'DDIC' });
  assert.equal(outsidePrefix.status, 400);
  assert.equal(outsidePrefix.body.error, 'USERNAME_PREFIX_INVALID');
  const missing = await write('unlockSu01User', { username: 'N8N_DEMO_99' });
  assert.equal(missing.status, 400);
  assert.equal(missing.body.error, 'USER_NOT_FOUND');
  const extra = await write('resetSu01UserPassword', { username: 'N8N_DEMO_00', password: 'Secret1!' });
  assert.equal(extra.body.error, 'PARAMETER_NOT_ALLOWED');
});

test('the provisioning fixture refuses read operations', async () => {
  const response = await fetch(`${origin}/v1/operations/listSu01Users/execute`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ operation: 'listSu01Users', parameters: {}, context: { readOnly: true } }),
  });
  assert.equal(response.status, 403);
});
