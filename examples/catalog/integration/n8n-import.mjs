// Inside the disposable n8n: create test credentials, import workflows, run webhook tests.
// Env: N8N_API_KEY, RFC_GUARD_API_TOKEN. Nothing secret is printed.
import { readFileSync, readdirSync } from 'node:fs';
const api = 'http://127.0.0.1:5678/api/v1';
const headers = { 'X-N8N-API-KEY': process.env.N8N_API_KEY, 'Content-Type': 'application/json' };
async function call(method, path, body) {
  const response = await fetch(api + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await response.text();
  let json; try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: response.status, json };
}
const base = {
  apiToken: process.env.RFC_GUARD_API_TOKEN, headerMode: 'bearer', sidecarMode: 'readOnly',
  allowedOperations: 'getPurchaseOrderDetail',
  dataFieldPoliciesJson: JSON.stringify({ getPurchaseOrderDetail: ['purchaseOrder', 'companyCode', 'vendor', 'currency', 'itemNumber', 'material', 'plant', 'quantity', 'orderUnit', 'netPrice', 'deliveryStatus'] }),
  allowFunctionCatalog: true, rejectUnauthorized: true, allowInsecureHttp: false, allowAiTool: false,
  maxRows: 100, maxRequestBytes: 65536, maxResponseBytes: 2097152, connectionTimeout: 15000, requestTimeout: 30000,
  allowIncomingInvoiceCreation: false, allowGoodsReceiptCreation: false, allowUserCreation: false,
};
const wanted = {
  CRED_LECTURA: { name: 'C415 · Catálogo RFC lectura (prueba aislada)', data: { ...base, baseUrl: 'https://c415-rfc-catalog-test:8080',
    catalogFunctions: 'BAPI_PO_GETDETAIL1, BAPI_VENDOR_GETDETAIL, BAPI_MATERIAL_AVAILABILITY, STFC_CONNECTION, STFC_EXCEPTION, BAPI_SALESORDER_GETSTATUS' } },
  CRED_T422: { name: 'C415 · Catálogo RFC T422 (prueba aislada)', data: { ...base, baseUrl: 'https://c415-rfc-catalog-t422:8080',
    catalogFunctions: 'BAPI_PO_GETDETAIL1, BAPI_VENDOR_GETDETAIL, BAPI_COMPANYCODE_GETDETAIL' } },
};
const ids = {};
for (const [key, spec] of Object.entries(wanted)) {
  const created = await call('POST', '/credentials', { name: spec.name, type: 'sapRfcGuardApi', data: spec.data });
  ids[key] = created.json.id;
  console.log('credential', key, created.status, created.json.id ?? JSON.stringify(created.json).slice(0, 300));
}
const existing = (await call('GET', '/workflows?limit=100')).json.data ?? [];
for (const file of readdirSync('/tmp/wf').filter((name) => name.endsWith('.json'))) {
  let text = readFileSync('/tmp/wf/' + file, 'utf8');
  for (const [key, id] of Object.entries(ids)) text = text.split(`"id": "${key}"`).join(`"id": "${id}"`);
  const workflow = JSON.parse(text);
  for (const old of existing.filter((item) => item.name === workflow.name)) {
    await call('POST', `/workflows/${old.id}/deactivate`);
    await call('DELETE', `/workflows/${old.id}`);
  }
  const created = await call('POST', '/workflows', workflow);
  console.log('workflow', file, created.status, created.json.id ?? JSON.stringify(created.json).slice(0, 300));
  if (created.json.id && workflow.nodes.some((item) => item.type === 'n8n-nodes-base.webhook')) {
    const active = await call('POST', `/workflows/${created.json.id}/activate`);
    console.log('  activate', active.status, active.json.active ?? JSON.stringify(active.json).slice(0, 200));
  }
}
