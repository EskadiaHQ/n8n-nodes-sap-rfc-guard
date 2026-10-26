import http from 'node:http';
import { randomUUID } from 'node:crypto';

const port = Number(process.env.PORT ?? 8080);
// CONTRACT_MODE=user-provisioning starts a separate synthetic provisioning fixture (never both).
const provisioningMode = process.env.CONTRACT_MODE === 'user-provisioning';
const provisioningPrefix = process.env.PROVISIONING_PREFIX ?? 'N8N_DEMO_';
const expectedToken = process.env.CONTRACT_TOKEN ?? 'RFC_GUARD_CONTRACT_FIXTURE';
const maxBodyBytes = 64 * 1024;
const operations = Object.freeze([
  'listSu01Users',
  'getSu01UserDetail',
  'listSu01RiskAccounts',
  'summarizeSu01Accounts',
]);
const writeOperations = Object.freeze([
  'createSu01CommunicationUser',
  'resetSu01UserPassword',
  'lockSu01User',
  'unlockSu01User',
]);
const writeVerbs = Object.freeze({
  createSu01CommunicationUser: 'CREATE',
  resetSu01UserPassword: 'RESET',
  lockSu01User: 'LOCK',
  unlockSu01User: 'UNLOCK',
});
const writeParameters = Object.freeze({
  createSu01CommunicationUser: new Set(['client', 'username', 'firstName', 'lastName', 'email', 'validDays']),
  resetSu01UserPassword: new Set(['client', 'username']),
  lockSu01User: new Set(['client', 'username']),
  unlockSu01User: new Set(['client', 'username']),
});
const operationSet = new Set(provisioningMode ? writeOperations : operations);
// Synthetic, in-memory SU01 accounts managed by the provisioning fixture. N8N_DEMO_00 exists from start.
const managedUsers = new Map([[`${provisioningPrefix}00`, {
  username: `${provisioningPrefix}00`, userType: 'Communication', lockStatus: 'Unlocked',
  accountStatus: 'Active', passwordState: 'productive', validFrom: '2026-01-01', validTo: '2026-12-31',
}]]);
const expectedClient = process.env.SAP_CLIENT ?? '100';
const allowedParameters = Object.freeze({
  listSu01Users: new Set(['client', 'maxRows', 'inactiveDays', 'userType', 'accountStatus']),
  getSu01UserDetail: new Set(['client', 'username']),
  listSu01RiskAccounts: new Set(['client', 'maxRows', 'inactiveDays']),
  summarizeSu01Accounts: new Set(['client', 'maxRows', 'inactiveDays', 'dimension']),
});
const userTypes = new Set(['dialog', 'system', 'communication', 'reference', 'service']);
const accountStatuses = new Set(['active', 'locked', 'expired', 'notyetvalid', 'inactive']);

const users = Object.freeze([
  {
    username: 'TRAINING_ADMIN',
    userType: 'Dialog',
    fullName: 'Training Administrator',
    email: 'training.admin@example.com',
    createdAt: '2025-01-15',
    validFrom: '2025-01-15',
    validTo: '9999-12-31',
    lastLogonAt: '2026-08-20T08:42:00',
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
    lastLogonAt: '2026-08-18T14:10:00',
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
    lastLogonAt: '2026-08-21T00:05:00',
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
    lastLogonAt: '2025-12-14T17:25:00',
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
    lastLogonAt: '2025-08-10T09:30:00',
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
    lastLogonAt: '2026-08-20T22:18:00',
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

function integerParameter(parameters, key, fallback, minimum, maximum, errorCode) {
  if (parameters[key] === undefined) return fallback;
  const value = Number(parameters[key]);
  if (!Number.isInteger(value) || value < minimum || value > maximum) throw new Error(errorCode);
  return value;
}

function validateParameters(operation, parameters) {
  const allowed = allowedParameters[operation];
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) {
    throw new Error('PARAMETERS_INVALID');
  }
  if (Object.keys(parameters).some((key) => !allowed.has(key))) {
    throw new Error('PARAMETER_NOT_ALLOWED');
  }
  if (parameters.client !== undefined) {
    const client = String(parameters.client);
    if (!/^\d{3}$/.test(client)) throw new Error('CLIENT_INVALID');
    if (client !== expectedClient) throw new Error('CLIENT_MISMATCH');
  }
  integerParameter(parameters, 'maxRows', users.length, 1, 500, 'MAX_ROWS_INVALID');
  integerParameter(parameters, 'inactiveDays', 90, 1, 3650, 'INACTIVE_DAYS_INVALID');
  if (parameters.userType !== undefined && !userTypes.has(String(parameters.userType).toLowerCase())) {
    throw new Error('USER_TYPE_INVALID');
  }
  if (parameters.accountStatus !== undefined
    && !accountStatuses.has(String(parameters.accountStatus).toLowerCase())) {
    throw new Error('ACCOUNT_STATUS_INVALID');
  }
  if (parameters.dimension !== undefined
    && !['accountStatus', 'userType'].includes(parameters.dimension)) {
    throw new Error('DIMENSION_INVALID');
  }
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

  const maxRows = integerParameter(parameters, 'maxRows', users.length, 1, 500, 'MAX_ROWS_INVALID');
  return users.filter((user) => {
    const statusMatches = !status || user.accountStatus.toLowerCase() === status;
    const typeMatches = !userType || user.userType.toLowerCase() === userType;
    return statusMatches && typeMatches;
  }).slice(0, maxRows);
}

function getUserDetail(parameters = {}) {
  const username = typeof parameters.username === 'string'
    ? parameters.username.trim().toUpperCase()
    : '';
  if (!username) throw new Error('USERNAME_REQUIRED');
  return users.filter((user) => user.username === username);
}

function listRiskAccounts(parameters = {}) {
  const reasons = {
    Locked: 'account_locked',
    Expired: 'validity_expired',
    Inactive: 'no_recent_logon',
  };
  const maxRows = integerParameter(parameters, 'maxRows', users.length, 1, 500, 'MAX_ROWS_INVALID');
  return users
    .filter((user) => Object.hasOwn(reasons, user.accountStatus))
    .slice(0, maxRows)
    .map((user) => ({
      ...user,
      riskReason: reasons[user.accountStatus],
    }));
}

function summarizeAccounts(parameters = {}) {
  const dimension = parameters.dimension === 'userType' ? 'userType' : 'accountStatus';
  const counts = new Map();
  const maxRows = integerParameter(parameters, 'maxRows', users.length, 1, 500, 'MAX_ROWS_INVALID');
  for (const user of users.slice(0, maxRows)) {
    const value = user[dimension];
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([value, count]) => ({ dimension, value, count }));
}

function governedUsername(parameters) {
  const username = typeof parameters.username === 'string' ? parameters.username.trim().toUpperCase() : '';
  if (!username) throw new Error('USERNAME_REQUIRED');
  if (!/^[A-Z0-9_]{1,12}$/.test(username) || !username.startsWith(provisioningPrefix)) {
    throw new Error('USERNAME_PREFIX_INVALID');
  }
  return username;
}

function validateWriteParameters(operation, parameters) {
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) {
    throw new Error('PARAMETERS_INVALID');
  }
  if (Object.keys(parameters).some((key) => !writeParameters[operation].has(key))) {
    throw new Error('PARAMETER_NOT_ALLOWED');
  }
  if (parameters.client !== undefined && String(parameters.client) !== expectedClient) {
    throw new Error('CLIENT_MISMATCH');
  }
  return governedUsername(parameters);
}

function executeWriteOperation(operation, parameters) {
  const username = validateWriteParameters(operation, parameters);
  const today = new Date().toISOString().slice(0, 10);
  if (operation === 'createSu01CommunicationUser') {
    if (managedUsers.has(username)) throw new Error('USER_ALREADY_EXISTS');
    const validDays = integerParameter(parameters, 'validDays', 1, 1, 7, 'VALID_DAYS_INVALID');
    const validTo = new Date(Date.now() + (validDays - 1) * 86_400_000).toISOString().slice(0, 10);
    for (const field of ['firstName', 'lastName']) {
      const value = String(parameters[field] ?? '').trim();
      if (value.length < 1 || value.length > 40) throw new Error(`${field === 'firstName' ? 'FIRST' : 'LAST'}_NAME_INVALID`);
    }
    managedUsers.set(username, {
      username, userType: 'Communication', lockStatus: 'Unlocked', accountStatus: 'Active',
      passwordState: 'initial', validFrom: today, validTo,
    });
    return {
      username, created: true, verified: true, userType: 'Communication', validFrom: today, validTo,
      accountStatus: 'Active', rolesAssigned: 0, profilesAssigned: 0, passwordReturned: false,
    };
  }
  const user = managedUsers.get(username);
  if (!user) throw new Error('USER_NOT_FOUND');
  let action;
  if (operation === 'resetSu01UserPassword') {
    user.passwordState = 'initial';
    action = 'passwordReset';
  } else if (operation === 'lockSu01User') {
    user.lockStatus = 'LockedByAdministrator';
    user.accountStatus = 'Locked';
    action = 'locked';
  } else {
    user.lockStatus = 'Unlocked';
    user.accountStatus = 'Active';
    action = 'unlocked';
  }
  return {
    username, action, verified: true, lockStatus: user.lockStatus, accountStatus: user.accountStatus,
    ...(operation === 'resetSu01UserPassword'
      ? { passwordState: 'initial', mustChangeAtNextLogon: true }
      : {}),
    passwordReturned: false,
  };
}

function executeOperation(operation, parameters) {
  switch (operation) {
    case 'listSu01Users':
      return filterUsers(parameters);
    case 'getSu01UserDetail':
      return getUserDetail(parameters);
    case 'listSu01RiskAccounts':
      return listRiskAccounts(parameters);
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
      service: provisioningMode
        ? 'sap-rfc-guard-contract-fixture-provisioning'
        : 'sap-rfc-guard-contract-fixture',
      version: '1.3.0',
      capabilities: provisioningMode
        ? { readOnly: false, writeEnabled: true, operations: writeOperations }
        : { readOnly: true, operations },
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

    if (provisioningMode) {
      const parameters = body.parameters ?? {};
      const username = typeof parameters.username === 'string' ? parameters.username.trim().toUpperCase() : '';
      const expected = `${writeVerbs[routeOperation]} ${username}`;
      if (body.operation !== routeOperation
        || body.context?.write !== true
        || body.context?.readOnly !== false
        || req.headers['x-rfc-guard-mode'] !== 'user-provisioning'
        || !username
        || body.context?.confirmation !== expected) {
        json(res, 403, {
          error: 'WRITE_CONFIRMATION_REQUIRED',
          message: 'A matching explicit confirmation is required for this user change.',
          correlationId,
        });
        return;
      }
      const data = executeWriteOperation(routeOperation, parameters);
      json(res, 200, {
        operation: routeOperation,
        correlationId,
        data,
        meta: {
          source: 'contract-fixture',
          syntheticData: true,
          readOnly: false,
          write: true,
          operation: routeOperation,
          rowCount: 1,
          correlationId,
        },
      });
      return;
    }

    if (body.operation !== routeOperation || body.context?.readOnly !== true) {
      json(res, 403, {
        error: 'READ_ONLY_CONTRACT_REQUIRED',
        message: 'The fixture requires a matching read-only business operation contract.',
        correlationId,
      });
      return;
    }

    const parameters = body.parameters ?? {};
    validateParameters(routeOperation, parameters);
    const data = executeOperation(routeOperation, parameters);
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
    const code = error instanceof Error && /^[A-Z][A-Z0-9_]{2,64}$/.test(error.message)
      ? error.message
      : 'INVALID_JSON';
    json(res, code === 'REQUEST_TOO_LARGE' ? 413 : 400, {
      error: code,
      message: code === 'REQUEST_TOO_LARGE'
        ? 'The request exceeds 64 KiB.'
        : code === 'USERNAME_REQUIRED'
          ? 'The username parameter is required.'
          : code === 'USER_NOT_FOUND'
            ? 'The governed synthetic user does not exist.'
            : code === 'USERNAME_PREFIX_INVALID'
              ? 'The username does not match the configured provisioning prefix.'
          : code === 'INVALID_JSON'
            ? 'The request body must be valid JSON.'
            : 'The request parameters do not satisfy the governed operation contract.',
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
