import http from 'node:http';
import { randomUUID } from 'node:crypto';

const port = Number(process.env.PORT ?? 8080);
const expectedToken = process.env.CONTRACT_TOKEN ?? 'RFC_GUARD_CONTRACT_FIXTURE';
const maxBodyBytes = 64 * 1024;
const operations = Object.freeze([
  'listSu01Users',
  'getSu01UserDetail',
  'listSu01RiskAccounts',
  'summarizeSu01Accounts',
]);
const operationSet = new Set(operations);

const users = Object.freeze([
  {
    username: 'TRAINING_ADMIN',
    userType: 'Dialog',
    fullName: 'Training Administrator',
    email: 'training.admin@example.com',
    createdAt: '2025-01-15',
    validFrom: '2025-01-15',
    validTo: '9999-12-31',
    lastLogonAt: '2026-08-20T08:42:00Z',
    lockStatus: 'Unlocked',
    accountStatus: 'Active',
  },
  {
    username: 'PROCUREMENT_USER',
    userType: 'Dialog',
    fullName: 'Procurement User',
    email: 'procurement.user@example.com',
    createdAt: '2025-04-03',
    validFrom: '2025-04-03',
    validTo: '9999-12-31',
    lastLogonAt: '2026-08-18T14:10:00Z',
    lockStatus: 'LockedByAdministrator',
    accountStatus: 'Locked',
  },
  {
    username: 'BATCH_INTEGRATION',
    userType: 'System',
    fullName: 'Batch Integration',
    email: 'batch.integration@example.com',
    createdAt: '2024-11-11',
    validFrom: '2024-11-11',
    validTo: '9999-12-31',
    lastLogonAt: '2026-08-21T00:05:00Z',
    lockStatus: 'Unlocked',
    accountStatus: 'Active',
  },
  {
    username: 'FORMER_EMPLOYEE',
    userType: 'Dialog',
    fullName: 'Former Employee',
    email: 'former.employee@example.com',
    createdAt: '2023-06-09',
    validFrom: '2023-06-09',
    validTo: '2025-12-31',
    lastLogonAt: '2025-12-14T17:25:00Z',
    lockStatus: 'Unlocked',
    accountStatus: 'Expired',
  },
  {
    username: 'DORMANT_DIALOG',
    userType: 'Dialog',
    fullName: 'Dormant Dialog User',
    email: 'dormant.dialog@example.com',
    createdAt: '2024-02-20',
    validFrom: '2024-02-20',
    validTo: '9999-12-31',
    lastLogonAt: '2025-08-10T09:30:00Z',
    lockStatus: 'Unlocked',
    accountStatus: 'Inactive',
  },
  {
    username: 'COMMUNICATION_API',
    userType: 'Communication',
    fullName: 'Communication API',
    email: 'communication.api@example.com',
    createdAt: '2025-09-12',
    validFrom: '2025-09-12',
    validTo: '9999-12-31',
    lastLogonAt: '2026-08-20T22:18:00Z',
    lockStatus: 'Unlocked',
    accountStatus: 'Active',
  },
]);

function json(res, statusCode, body) {
  const payload = JSON.stringify(body);
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
    'cache-control': 'no-store',
  });
  res.end(payload);
}

function isAuthorized(req) {
  return req.headers.authorization === `Bearer ${expectedToken}`;
}

async function readJson(req) {
  let size = 0;
  const chunks = [];

  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBodyBytes) {
      throw new Error('REQUEST_TOO_LARGE');
    }
    chunks.push(chunk);
  }

  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function filterUsers(parameters = {}) {
  const status = typeof parameters.accountStatus === 'string'
    ? parameters.accountStatus.trim().toLowerCase()
    : '';
  const userType = typeof parameters.userType === 'string'
    ? parameters.userType.trim().toLowerCase()
    : '';

  return users.filter((user) => {
    const statusMatches = !status || user.accountStatus.toLowerCase() === status;
    const typeMatches = !userType || user.userType.toLowerCase() === userType;
    return statusMatches && typeMatches;
  });
}

function getUserDetail(parameters = {}) {
  const username = typeof parameters.username === 'string'
    ? parameters.username.trim().toUpperCase()
    : '';
  if (!username) throw new Error('USERNAME_REQUIRED');
  return users.filter((user) => user.username === username);
}

function listRiskAccounts() {
  const reasons = {
    Locked: 'account_locked',
    Expired: 'validity_expired',
    Inactive: 'no_recent_logon',
  };
  return users
    .filter((user) => Object.hasOwn(reasons, user.accountStatus))
    .map((user) => ({
      ...user,
      riskReason: reasons[user.accountStatus],
    }));
}

function summarizeAccounts(parameters = {}) {
  const dimension = parameters.dimension === 'userType' ? 'userType' : 'accountStatus';
  const counts = new Map();
  for (const user of users) {
    const value = user[dimension];
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([value, count]) => ({ dimension, value, count }));
}

function executeOperation(operation, parameters) {
  switch (operation) {
    case 'listSu01Users':
      return filterUsers(parameters);
    case 'getSu01UserDetail':
      return getUserDetail(parameters);
    case 'listSu01RiskAccounts':
      return listRiskAccounts();
    case 'summarizeSu01Accounts':
      return summarizeAccounts(parameters);
    default:
      return [];
  }
}

const server = http.createServer(async (req, res) => {
  const correlationId = String(req.headers['x-correlation-id'] ?? randomUUID());

  if (!isAuthorized(req)) {
    json(res, 401, {
      error: 'UNAUTHORIZED',
      message: 'A valid contract fixture token is required.',
      correlationId,
    });
    return;
  }

  if (req.method === 'GET' && req.url === '/v1/health') {
    json(res, 200, {
      status: 'ok',
      service: 'sap-rfc-guard-contract-fixture',
      version: '1.1.0',
      capabilities: {
        readOnly: true,
        operations,
      },
      correlationId,
    });
    return;
  }

  const operationMatch = req.url?.match(/^\/v1\/operations\/([A-Za-z][A-Za-z0-9]*)\/execute$/);
  const routeOperation = operationMatch?.[1] ?? '';

  if (req.method !== 'POST' || !operationSet.has(routeOperation)) {
    json(res, 403, {
      error: 'OPERATION_NOT_ALLOWED',
      message: 'The requested operation is not available in this contract fixture.',
      correlationId,
    });
    return;
  }

  try {
    const body = await readJson(req);

    if (body.operation !== routeOperation || body.context?.readOnly !== true) {
      json(res, 403, {
        error: 'READ_ONLY_CONTRACT_REQUIRED',
        message: 'The fixture requires a matching read-only business operation contract.',
        correlationId,
      });
      return;
    }

    const data = executeOperation(routeOperation, body.parameters ?? {});
    json(res, 200, {
      operation: routeOperation,
      correlationId,
      data,
      meta: {
        source: 'contract-fixture',
        syntheticData: true,
        readOnly: true,
        operation: routeOperation,
        rowCount: data.length,
        correlationId,
      },
    });
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === 'REQUEST_TOO_LARGE';
    const usernameRequired = error instanceof Error && error.message === 'USERNAME_REQUIRED';
    json(res, tooLarge ? 413 : 400, {
      error: tooLarge ? 'REQUEST_TOO_LARGE' : usernameRequired ? 'USERNAME_REQUIRED' : 'INVALID_JSON',
      message: tooLarge
        ? 'The request exceeds 64 KiB.'
        : usernameRequired
          ? 'The username parameter is required.'
          : 'The request body must be valid JSON.',
      correlationId,
    });
  }
});

server.listen(port, '0.0.0.0', () => {
  process.stdout.write(`SAP RFC Guard contract fixture listening on ${port}\n`);
});

function shutdown() {
  server.close(() => process.exit(0));
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
