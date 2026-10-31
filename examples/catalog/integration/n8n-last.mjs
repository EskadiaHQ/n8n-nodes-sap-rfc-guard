// Prints a compact view of the latest executions (node, status, error, first output item).
const api = 'http://127.0.0.1:5678/api/v1';
const headers = { 'X-N8N-API-KEY': process.env.N8N_API_KEY };
const limit = Number(process.argv[2] ?? 1);
const list = await (await fetch(`${api}/executions?limit=${limit}`, { headers })).json();
for (const item of list.data ?? []) {
  const detail = await (await fetch(`${api}/executions/${item.id}?includeData=true`, { headers })).json();
  console.log(`== execution ${item.id} workflow ${item.workflowId} status ${detail.status}`);
  const runs = detail.data?.resultData?.runData ?? {};
  const error = detail.data?.resultData?.error;
  if (error) console.log('  workflow error:', error.message, error.description ?? '');
  for (const [name, executions] of Object.entries(runs)) {
    const run = executions[0];
    const out = run.data?.main?.[0] ?? [];
    const first = out[0]?.json;
    const text = run.error ? 'ERROR ' + run.error.message : JSON.stringify(first ?? {}).slice(0, Number(process.argv[3] ?? 400));
    console.log(`  [${name}] items=${out.length} ${text}`);
  }
}
