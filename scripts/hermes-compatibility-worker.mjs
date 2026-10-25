#!/usr/bin/env node
// Trusted CI transport. Upstream code never runs in this OIDC-enabled job.
import { readFile, writeFile, lstat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { compatibilityUUID, compatibilityDigest, parseValidationIdentity, parseValidationReport,
  REQUIRED_COMPATIBILITY_CHECKS } from '../gateway/dist/account/hermes-compatibility-model.js';

export async function readJSON(path) {
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink() || info.size > 512 * 1024) throw new Error('invalid_file');
  return JSON.parse(await readFile(path, 'utf8'));
}
export function parseTask(value) {
  if (!value || Object.keys(value).sort().join(',') !== 'identity,runId,schemaVersion' || value.schemaVersion !== 1) throw new Error('invalid_task');
  return { schemaVersion: 1, runId: compatibilityUUID(value.runId), identity: parseValidationIdentity(value.identity) };
}
export function validateBundle(task, bundle) {
  if (!bundle || Object.keys(bundle).sort().join(',') !== 'evidence,report,schemaVersion' || bundle.schemaVersion !== 1) throw new Error('invalid_bundle');
  const report = parseValidationReport(bundle.report);
  if (report.runId !== task.runId || compatibilityDigest(report.identity) !== compatibilityDigest(task.identity)
      || compatibilityDigest(bundle.evidence) !== report.evidenceBundleSha256) throw new Error('evidence_mismatch');
  const digests = new Set(Object.values(bundle.evidence).map(compatibilityDigest));
  if (report.checks.some(c => c.evidenceSha256 !== null && !digests.has(c.evidenceSha256))) throw new Error('missing_evidence');
  return report;
}
export function infrastructureReport(task, now = new Date()) {
  const timestamp = now.toISOString().replace(/\.\d{3}Z$/, 'Z');
  return { schemaVersion: 1, runId: task.runId, identity: task.identity, startedAt: timestamp, finishedAt: timestamp,
    outcome: 'infrastructure_failed', checks: REQUIRED_COMPATIBILITY_CHECKS.map(name => ({ name, status: 'not_run', evidenceSha256: null })),
    evidenceBundleSha256: compatibilityDigest({ reason: 'validation_job_did_not_return_verified_evidence' }) };
}
async function jsonResponse(response) {
  if (!response.ok || !response.body) throw new Error('transport_failed');
  const reader = response.body.getReader(); const chunks = []; let size = 0;
  try { while (true) { const next = await reader.read(); if (next.done) break; size += next.value.length;
    if (size > 512 * 1024) throw new Error('response_too_large'); chunks.push(next.value); } }
  finally { await reader.cancel().catch(() => {}); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
export async function workerRequest(action, runId, body, env = process.env, fetcher = fetch, namespace = 'worker') {
  compatibilityUUID(runId);
  if (!['claim', 'report'].includes(action)) throw new Error('invalid_action');
  if (!['worker', 'publisher'].includes(namespace)) throw new Error('invalid_namespace');
  const origin = new URL(env.HERMES_COMPATIBILITY_GATEWAY_URL);
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') throw new Error('invalid_origin');
  const audience = `${origin.origin}/hermes-compatibility`;
  const request = new URL(env.ACTIONS_ID_TOKEN_REQUEST_URL);
  // Provided by the runner; do not accept an arbitrary token endpoint from a task artifact.
  if (request.protocol !== 'https:' || !request.hostname.endsWith('.actions.githubusercontent.com') || request.username || request.password || request.hash) throw new Error('invalid_oidc_endpoint');
  request.searchParams.set('audience', audience);
  const token = await jsonResponse(await fetcher(request, { redirect: 'error', signal: AbortSignal.timeout(15_000),
    headers: { authorization: `Bearer ${env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}` } }));
  if (typeof token.value !== 'string' || token.value.length > 16400) throw new Error('invalid_token');
  return jsonResponse(await fetcher(`${origin.origin}/v2/hermes-compatibility/${namespace}/${runId}/${action}`, {
    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(30_000),
    headers: { authorization: `Bearer ${token.value}`, 'content-type': 'application/json' }, body: JSON.stringify(body) }));
}
async function main() {
  const [action, taskPath, resultPath] = process.argv.slice(2);
  if (action === 'claim') {
    const task = parseTask(await workerRequest('claim', process.env.HERMES_COMPATIBILITY_RUN_ID, {}));
    if (task.identity.goCommit !== process.env.GITHUB_SHA) throw new Error('checkout_mismatch');
    await writeFile(taskPath, JSON.stringify(task) + '\n', { mode: 0o600, flag: 'wx' });
  } else if (action === 'report') {
    const task = parseTask(await readJSON(taskPath));
    if (task.identity.goCommit !== process.env.GITHUB_SHA || task.runId !== process.env.HERMES_COMPATIBILITY_RUN_ID) throw new Error('task_mismatch');
    let report;
    try { report = validateBundle(task, await readJSON(resultPath)); }
    catch { report = infrastructureReport(task); }
    await workerRequest('report', task.runId, report);
  } else throw new Error('invalid_action');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(() => { console.error('HR-HERMESCOMPAT-005：验证任务传输未完成，请检查任务记录后重试。 / Validation transport failed; review the job and retry.'); process.exitCode = 1; });
}
