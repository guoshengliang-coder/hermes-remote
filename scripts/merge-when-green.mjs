#!/usr/bin/env node
// The merge gate from AGENTS.md as one blocking call: wait until every check reported for the PR has
// completed successfully, merge exactly the commit those checks ran on, then wait for the resulting
// `main` push checks and report them.
//
// It exists because agents were doing this by hand. Measured over Sep 12–22, sessions spent 14 hours
// in `until gh pr checks …; sleep` loops — 1,100+ polls, each one a model round-trip. This script
// polls inside one process instead, so the agent makes one call (in the background) and reads one
// result. It does not relax the gate: a failing, cancelled or missing check blocks the merge, and a
// red-light change (docs/INTEGRATION.md table 2) is refused unless the integration agent says so.
//
// Exit codes: 0 merged and main green · 1 PR checks failed, nothing merged · 2 merged but main
// checks failed · 3 timed out · 4 refused (PR state, red light, usage).
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const OK_CONCLUSIONS = new Set(['SUCCESS', 'SKIPPED', 'NEUTRAL']);

// Table 2's red light: a version truth source or gateway/release-contract.json. Most of these files
// also change for ordinary reasons (dependencies, plist keys), so a file counts only when one of its
// changed lines matches the version pattern. Keep in step with docs/INTEGRATION.md 表 1: `doc` is how
// that table names each source, and the test suite fails when one stops appearing there.
const versionField = (text) => /^\s*"version"\s*:/.test(text);
export const RED_LIGHT_SOURCES = [
  {file: 'android/app/build.gradle.kts', doc: 'appVersionName', matches: (text) => /^\s*val\s+appVersion(Name|Code)\s*=/.test(text)},
  // The value sits on the line after its key, and a version bump may change only that line.
  {file: 'desktop/Packaging/Info.plist', doc: 'CFBundleShortVersionString',
    matches: (text, previous) => /CFBundle(ShortVersionString|Version)</.test(text) || /<key>CFBundle(ShortVersionString|Version)<\/key>/.test(previous)},
  {file: 'gateway/package.json', doc: '`gateway`', matches: versionField},
  {file: 'connector/package.json', doc: '`connector`', matches: versionField},
  {file: 'protocol/package.json', doc: '`protocol`', matches: versionField},
  {file: 'web/package.json', doc: '`web/package.json`', matches: versionField},
  {file: 'gateway/release-contract.json', doc: '`gateway/release-contract.json`', matches: () => true},
];

// A check is pending until it completes; a completed check passes only on success, skipped (the
// component filter in ci.yml skips unaffected jobs) or neutral. Any failure is reported at once,
// without waiting for the remaining checks.
export function classifyChecks(rollup) {
  const pending = [];
  const failed = [];
  for (const check of rollup ?? []) {
    const name = check.workflowName ? `${check.workflowName} / ${check.name}` : (check.name ?? check.context);
    if (check.__typename === 'StatusContext') {
      if (check.state === 'PENDING' || check.state === 'EXPECTED') pending.push(name);
      else if (check.state !== 'SUCCESS') failed.push(`${name} (${check.state})`);
      continue;
    }
    if (check.status !== 'COMPLETED') pending.push(name);
    else if (!OK_CONCLUSIONS.has(check.conclusion)) failed.push(`${name} (${check.conclusion})`);
  }
  // Sorted so the progress log prints a state once, not again whenever gh reorders the same checks.
  pending.sort();
  failed.sort();
  if (failed.length) return {state: 'failure', pending, failed};
  if (!(rollup ?? []).length) return {state: 'none', pending, failed};
  return {state: pending.length ? 'pending' : 'success', pending, failed};
}

// `gh run list` for the merge commit: push-triggered workflow runs on main.
export function classifyRuns(runs) {
  return classifyChecks((runs ?? [])
    .filter((run) => run.event === 'push')
    .map((run) => ({
      name: run.name,
      status: String(run.status).toUpperCase(),
      conclusion: run.conclusion ? String(run.conclusion).toUpperCase() : null,
    })));
}

// Files from a unified diff whose changed lines hit a red-light pattern.
export function redLightFiles(diff) {
  const hits = new Set();
  let current = null;
  let previous = '';
  for (const line of diff.split('\n')) {
    const header = /^diff --git a\/(\S+) b\/(\S+)/.exec(line);
    if (header) {
      current = RED_LIGHT_SOURCES.find((source) => source.file === header[2] || source.file === header[1]);
      previous = '';
      continue;
    }
    if (!current || line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@')) continue;
    const text = line.slice(1);
    if ((line.startsWith('+') || line.startsWith('-')) && current.matches(text, previous)) hits.add(current.file);
    previous = text;
  }
  return [...hits];
}

export function refusal(pr) {
  if (pr.state !== 'OPEN') return `PR is ${pr.state}, not OPEN`;
  if (pr.isDraft) return 'PR is a draft';
  if (pr.mergeable === 'CONFLICTING') return 'PR has merge conflicts with its base';
  return null;
}

// Polls `probe` until `classify` settles. `none` (nothing reported yet) is tolerated for `grace` ms,
// since checks take a few seconds to register after a push or merge. Success must be seen on two
// consecutive polls so a workflow that registers late is not missed.
export async function waitFor({probe, classify, interval, timeout, grace, sleep, now, log}) {
  const start = now();
  let lastSummary = '';
  let confirmed = false;
  for (;;) {
    const result = classify(await probe());
    const summary = result.state === 'pending' ? `pending: ${result.pending.join(', ')}` : result.state;
    if (summary !== lastSummary) log(`  ${summary}`);
    lastSummary = summary;
    if (result.state === 'failure') return result;
    if (result.state === 'success') {
      if (confirmed) return result;
      confirmed = true;
    } else {
      confirmed = false;
    }
    const elapsed = now() - start;
    if (result.state === 'none' && elapsed >= grace) return result;
    if (elapsed >= timeout) return {...result, state: 'timeout'};
    await sleep(interval);
  }
}

function gh(args) {
  return execFileSync('gh', args, {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 256 * 1024 * 1024});
}

function parseArgs(argv) {
  const options = {pr: null, merge: true, allowRed: false, timeoutMin: 45, intervalSec: 20};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--no-merge') options.merge = false;
    else if (arg === '--allow-red') options.allowRed = true;
    else if (arg === '--timeout-min') options.timeoutMin = Number(argv[++i]);
    else if (arg === '--interval-sec') options.intervalSec = Number(argv[++i]);
    else if (/^\d+$/.test(arg) && options.pr === null) options.pr = arg;
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!options.pr) throw new Error('usage: scripts/merge-when-green.mjs <PR> [--no-merge] [--allow-red] [--timeout-min N] [--interval-sec N]');
  if (!(options.timeoutMin > 0) || !(options.intervalSec > 0)) throw new Error('--timeout-min and --interval-sec must be positive');
  return options;
}

async function main(argv) {
  const options = parseArgs(argv);
  const {pr} = options;
  const timing = {
    interval: options.intervalSec * 1000,
    timeout: options.timeoutMin * 60 * 1000,
    grace: 3 * 60 * 1000,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: () => Date.now(),
    log: (line) => console.log(line),
  };
  const view = (fields) => JSON.parse(gh(['pr', 'view', pr, '--json', fields]));

  const initial = view('state,isDraft,mergeable,headRefOid,baseRefName,title');
  const refused = refusal(initial);
  if (refused) return finish(4, `REFUSED #${pr}: ${refused}`);
  const red = redLightFiles(gh(['pr', 'diff', pr]));
  if (red.length && options.merge && !options.allowRed) {
    return finish(4, `REFUSED #${pr}: red light (docs/INTEGRATION.md table 2) — changes a version truth source: ${red.join(', ')}. Only the integration agent merges this, with --allow-red.`);
  }
  const head = initial.headRefOid;
  console.log(`#${pr} ${initial.title}\n  head ${head.slice(0, 8)} → ${initial.baseRefName}`);

  const checks = await waitFor({...timing, probe: () => view('headRefOid,statusCheckRollup'), classify: (data) => {
    if (data.headRefOid !== head) return {state: 'failure', pending: [], failed: [`head moved to ${data.headRefOid.slice(0, 8)} while waiting — rerun`]};
    return classifyChecks(data.statusCheckRollup);
  }});
  if (checks.state === 'failure') return finish(1, `CHECKS_FAILED #${pr}: ${checks.failed.join('; ')}`);
  if (checks.state === 'none') return finish(1, `CHECKS_FAILED #${pr}: no checks were reported`);
  if (checks.state === 'timeout') return finish(3, `TIMEOUT #${pr}: still pending: ${checks.pending.join(', ')}`);
  if (!options.merge) return finish(0, `CHECKS_GREEN #${pr} at ${head.slice(0, 8)} (not merged: --no-merge)`);

  // --match-head-commit: if anything was pushed after the checks we just read, GitHub refuses.
  gh(['pr', 'merge', pr, '--merge', '--match-head-commit', head]);
  const merged = view('state,mergeCommit');
  if (merged.state !== 'MERGED' || !merged.mergeCommit?.oid) return finish(4, `MERGE_FAILED #${pr}: state ${merged.state}`);
  const sha = merged.mergeCommit.oid;
  console.log(`  merged as ${sha.slice(0, 8)}; waiting for ${initial.baseRefName} checks`);

  const runs = await waitFor({...timing, probe: () => JSON.parse(gh(['run', 'list', '--commit', sha, '--json', 'name,event,status,conclusion'])), classify: classifyRuns});
  if (runs.state === 'failure') return finish(2, `MAIN_FAILED ${sha.slice(0, 8)} after #${pr}: ${runs.failed.join('; ')}`);
  if (runs.state === 'none') return finish(2, `MAIN_FAILED ${sha.slice(0, 8)} after #${pr}: no push checks appeared`);
  if (runs.state === 'timeout') return finish(3, `TIMEOUT ${sha.slice(0, 8)} after #${pr}: main still pending: ${runs.pending.join(', ')}`);
  return finish(0, `MERGED_GREEN #${pr} → ${initial.baseRefName} ${sha.slice(0, 8)}`);
}

function finish(code, message) {
  console.log(message);
  process.exitCode = code;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`merge-when-green: ${error.stderr?.toString().trim() || error.message}`);
    process.exitCode = 4;
  });
}
