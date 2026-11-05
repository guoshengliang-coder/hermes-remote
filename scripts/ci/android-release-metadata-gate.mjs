// Run only from the trusted BASE snapshot exported by ci.yml, never from PR-controlled modules.
import {appendFileSync, realpathSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {classifyAndroidReleaseMetadata} from './android-release-metadata.mjs';

const SHA = /^[0-9a-f]{40}$/;
const REPOSITORY = /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]*\/[a-zA-Z0-9_-][a-zA-Z0-9_.-]*$/;
const full = reason => ({skipAndroid: false, reason});

async function readRuns(response) {
  if (!response.ok || !response.body) throw new Error();
  const reader = response.body.getReader();
  let size = 0;
  const chunks = [];
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 1024 * 1024) throw new Error();
      chunks.push(value);
    }
    return JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(Buffer.concat(chunks)));
  } finally { await reader.cancel().catch(() => {}); }
}

export async function decideAndroidReleaseMetadata({
  eventName, baseRef, baseSha, headSha, repository, token, repoDir = process.cwd(),
  classify = classifyAndroidReleaseMetadata, fetchImpl = fetch, timeoutMs = 15_000,
}) {
  if (eventName !== 'pull_request' || baseRef !== 'main') return full('not_main_pull_request');
  if (!SHA.test(baseSha ?? '') || !SHA.test(headSha ?? '') || !REPOSITORY.test(repository ?? '')) return full('invalid_context');
  try {
    const candidate = classify({repoDir, baseSha, headSha});
    if (candidate.candidate !== true) return full('not_exact_release_metadata');
    if (!token) return full('base_ci_unavailable');
    const query = new URLSearchParams({event: 'push', branch: 'main', head_sha: baseSha, per_page: '100'});
    const response = await fetchImpl(`https://api.github.com/repos/${repository}/actions/workflows/ci.yml/runs?${query}`, {
      headers: {Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'hermes-release-metadata-gate'},
      redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
    });
    const data = await readRuns(response);
    if (!Array.isArray(data.workflow_runs) || !Number.isSafeInteger(data.total_count) ||
        data.total_count < 1 || data.total_count !== data.workflow_runs.length) return full('base_ci_unconfirmed');
    // Require every returned attempt/run to be green; truncated, stale or mismatched data falls back.
    const green = data.workflow_runs.every(run => run.event === 'push' && run.head_branch === 'main' &&
      run.head_sha === baseSha && run.path === '.github/workflows/ci.yml' &&
      run.head_repository?.full_name === repository && run.status === 'completed' && run.conclusion === 'success');
    return green ? {skipAndroid: true, reason: 'exact_metadata_on_green_main'} : full('base_ci_not_green');
  } catch { return full('validation_unavailable'); }
}

export function formatGateDecision(result) {
  return [
    '### Android 纯升版 PR 检查 / Release metadata gate', '',
    `- 决策 / Decision: **${result.skipAndroid ? '仅此 PR 省去 Android 单测与 lint / skip PR Android tests and lint' : '沿用完整组件检查 / full component checks'}**`,
    `- 原因 / Reason: \`${result.reason}\``,
    '- main、本地 prepare、正式 package gate 与发布授权仍按完整规则执行。',
    '- Main, local prepare, the full package gate and publication authorization remain unchanged.', '',
  ].join('\n');
}

function isEntryPoint() {
  try { return process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href; }
  catch { return false; }
}

if (isEntryPoint()) {
  const env = process.env;
  const result = await decideAndroidReleaseMetadata({eventName: env.EVENT_NAME, baseRef: env.BASE_REF,
    baseSha: env.BASE_SHA, headSha: env.HEAD_SHA, repository: env.GITHUB_REPOSITORY, token: env.GH_TOKEN});
  try {
    appendFileSync(env.GITHUB_STEP_SUMMARY, formatGateDecision(result));
    appendFileSync(env.GITHUB_OUTPUT, `skip_android=${result.skipAndroid}\n`);
  } catch {
    // The workflow prewrites false and catches failure; never expose token, API body or filesystem paths.
    process.exitCode = 1;
  }
}
