// Runs inside the disposable n8n container: owner setup, API key, and the test credential.
// Secrets arrive as environment variables from a root-only file and are never printed.
const base = 'http://127.0.0.1:5678';
const env = process.env;
let cookie = '';
async function rest(method, path, body) {
  const response = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const setCookie = response.headers.get('set-cookie');
  if (setCookie) cookie = setCookie.split(';')[0];
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : {} };
}
const owner = { email: env.OWNER_EMAIL, firstName: 'Catalog', lastName: 'Test', password: env.OWNER_PASSWORD };
let setup = await rest('POST', '/rest/owner/setup', owner);
if (setup.status !== 200) {
  setup = await rest('POST', '/rest/login', { emailOrLdapLoginId: owner.email, password: owner.password });
}
console.log('owner', setup.status);
const types = await rest('GET', '/types/nodes.json');
const node = (Array.isArray(types.json) ? types.json : []).find((item) => item.name === 'n8n-nodes-sap-rfc-guard.sapRfcGuard');
console.log('node type loaded', Boolean(node), node ? JSON.stringify(node.properties.find((p) => p.name === 'resource').options.map((o) => o.value)) : '');
const keys = await rest('GET', '/rest/api-keys');
let apiKey = env.API_KEY_EXISTING || '';
if (!apiKey) {
  const scopes = await rest('GET', '/rest/api-keys/scopes');
  const created = await rest('POST', '/rest/api-keys', { label: 'catalog-test', expiresAt: null, scopes: scopes.json.data ?? scopes.json });
  apiKey = created.json.data?.rawApiKey ?? created.json.data?.apiKey ?? '';
  console.log('api key', created.status, apiKey ? 'created' : JSON.stringify(created.json).slice(0, 200));
}
if (apiKey) process.stdout.write('APIKEY=' + apiKey + '\n');
