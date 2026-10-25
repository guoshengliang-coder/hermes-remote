#!/usr/bin/env node
import { readFile, writeFile, mkdtemp, chmod, realpath } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseTask, readJSON, infrastructureReport } from './hermes-compatibility-worker.mjs';
import { compatibilityDigest, REQUIRED_COMPATIBILITY_CHECKS } from '../gateway/dist/account/hermes-compatibility-model.js';

const exec = promisify(execFile);
const timestamp = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
const SHA = /^[a-f0-9]{64}$/;
export function lockEvidence(raw, identity, suiteSha256) {
  const names = ['oldRefusesLiveOwner', 'newRefusesLiveOwner', 'childSurvivesOwnerInterruption',
    'newRefusesSurvivingChild', 'oldRefusesSurvivingChild', 'newAcquiresAfterTreeExit'];
  if (raw?.schemaVersion !== 1 || raw.sourceCommit !== identity.sourceCommit || raw.targetCommit !== identity.targetCommit
      || raw.suiteSha256 !== suiteSha256 || !SHA.test(suiteSha256) || raw.compatibilityApproved !== false
      || !SHA.test(raw.sourceLockSha256) || !SHA.test(raw.targetLockSha256)
      || !names.every(name => typeof raw.checks?.[name] === 'boolean')) throw new Error('lock_identity_mismatch');
  const checks = Object.fromEntries(names.map(name => [name, raw.checks[name]]));
  const gap = Object.values(checks).some(value => !value);
  if (raw.status !== (gap ? 'compatibility_gap' : 'passed')) throw new Error('lock_status_mismatch');
  return {kind: 'official-update-lock-probe', suiteSha256, sourceLockSha256: raw.sourceLockSha256,
    targetLockSha256: raw.targetLockSha256, checks, gap};
}
/** Whitelist evidence fields. Never publish paths, subprocess logs, environment or credentials. */
export function labEvidence(raw, identity, fault = false) {
  if (raw?.schemaVersion !== 1 || raw.kind !== 'runtime-rehearsal' || raw.suiteSha256 !== identity.suiteSha256
      || raw.case?.sourceCommit !== identity.sourceCommit || raw.case?.targetCommit !== identity.targetCommit
      || raw.case?.installerSha256 !== identity.installerSha256 || raw.platform !== 'Darwin' || raw.arch !== 'arm64'
      || raw.compatibilityApproved !== false || (fault && raw.faultAfter !== 'target_python-deps')) throw new Error('lab_identity_mismatch');
  const names = ['sandbox', 'writeFence', 'evidenceFence', 'clone', 'origin', 'sourceCheckout', 'targetTag',
    'source_venv', 'source_python-deps', 'source_config', 'seedHistory', 'sourceHistory', 'snapshot', 'repository',
    'exactTarget', 'target_venv', 'target_python-deps', 'target_config', 'target_products', 'target_complete', 'candidateHistory', 'restore', 'restoredHistory'];
  const phases = Object.fromEntries(names.map(name => [name, raw.checks?.[name]?.passed === true]));
  const snapshot = raw.checks?.snapshot?.digest, restored = raw.checks?.restore?.digest;
  return { kind: fault ? 'dependency-failure-recovery' : 'runtime-upgrade-recovery', phases,
    completed: fault ? raw.status === 'failed' && raw.reason === 'injected_failure_after_target_dependencies' : raw.status === 'runtime_stage_passed',
    exactTarget: raw.checks?.exactTarget?.actual === identity.targetCommit,
    exactRestoredSource: raw.checks?.restore?.actual === identity.sourceCommit,
    snapshotRestored: typeof snapshot === 'string' && SHA.test(snapshot) && snapshot === restored,
    originalHistory: raw.checks?.sourceHistory?.historyPresent === true,
    upgradedHistory: raw.checks?.candidateHistory?.historyPresent === true && raw.checks?.candidateHistory?.runningVersion === identity.version,
    restoredHistory: raw.checks?.restoredHistory?.historyPresent === true };
}
export function buildBundle(task, evidence, startedAt, finishedAt = timestamp()) {
  const normal = evidence.normal, fault = evidence.fault;
  const fences = v => v && ['sandbox', 'writeFence', 'evidenceFence'].every(n => v.phases[n]);
  const normalOK = fences(normal) && normal.completed && normal.exactTarget;
  const recoveryOK = normalOK && fences(fault) && fault.completed && [normal, fault].every(v =>
    v.exactRestoredSource && v.snapshotRestored && v.restoredHistory && v.phases.restore && v.phases.restoredHistory);
  const conditions = {
    installation: normalOK && ['source_venv', 'source_python-deps', 'target_venv', 'target_python-deps', 'target_products', 'target_complete'].every(n => normal.phases[n]),
    upgrade: normalOK && normal.phases.repository && normal.phases.exactTarget && normal.phases.candidateHistory,
    history: normalOK && normal.originalHistory && normal.upgradedHistory && normal.restoredHistory,
    recovery: recoveryOK,
  };
  const checks = REQUIRED_COMPATIBILITY_CHECKS.map(name => {
    // A demonstrated collision is enough to reject compatibility. This narrow probe alone
    // cannot certify the complete service/installer/GUI concurrency matrix if it passes.
    if (name === 'concurrentUpdate' && evidence.concurrency?.gap === true) {
      return {name, status: 'failed', evidenceSha256: compatibilityDigest(evidence.concurrency)};
    }
    const available = Object.hasOwn(conditions, name);
    const proof = name === 'recovery' ? evidence.recovery : normal;
    return { name, status: available ? conditions[name] ? 'passed' : 'failed' : 'not_run',
      evidenceSha256: available && proof ? compatibilityDigest(proof) : null };
  });
  return { schemaVersion: 1, evidence, report: { schemaVersion: 1, runId: task.runId, identity: task.identity,
    startedAt, finishedAt, outcome: evidence.infrastructure ? 'infrastructure_failed' : checks.every(c => c.status === 'passed') ? 'passed' : 'failed',
    checks, evidenceBundleSha256: compatibilityDigest(evidence) } };
}
async function main() {
  const [input, output] = process.argv.slice(2); const task = parseTask(await readJSON(input));
  const startedAt = timestamp(), evidence = {};
  try {
    if (process.platform !== 'darwin' || process.arch !== 'arm64' || process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN) throw new Error('wrong_runner');
    const osVersion = (await exec('/usr/bin/sw_vers', ['-productVersion'])).stdout.trim();
    const normalizedOS = osVersion.split('.').length === 2 ? `${osVersion}.0` : osVersion;
    const head = (await exec('git', ['rev-parse', 'HEAD'])).stdout.trim();
    const suite = resolve('scripts/hermes-compatibility-lab.py');
    if (normalizedOS !== task.identity.environment.osVersion || head !== task.identity.goCommit
        || createHash('sha256').update(await readFile(suite)).digest('hex') !== task.identity.suiteSha256) throw new Error('environment_mismatch');
    const root = await mkdtemp(join(tmpdir(), 'hermes-compat-ci-')); await chmod(root, 0o700);
    const cache = join(root, 'upstream'), casePath = join(root, 'case.json');
    // Fetch only upstream Git objects here. All upstream execution remains inside the lab sandbox.
    const env = { PATH: process.env.PATH, HOME: root, TMPDIR: tmpdir(), GIT_CONFIG_NOSYSTEM: '1', GIT_TERMINAL_PROMPT: '0' };
    await exec('git', ['clone', '--filter=blob:none', '--no-checkout', 'https://github.com/NousResearch/hermes-agent.git', cache], { env, timeout: 180_000, maxBuffer: 1024 * 1024 });
    for (const commit of [task.identity.sourceCommit, task.identity.targetCommit]) {
      await exec('git', ['-C', cache, 'fetch', '--no-tags', 'origin', commit], { env, timeout: 180_000 });
      await exec('git', ['-C', cache, 'show', `${commit}:scripts/install.sh`], { env, timeout: 180_000, maxBuffer: 1024 * 1024 });
    }
    await writeFile(casePath, JSON.stringify({ schemaVersion: 1, sourceCommit: task.identity.sourceCommit,
      targetCommit: task.identity.targetCommit, installerSha256: task.identity.installerSha256, branch: 'main' }), { mode: 0o600 });
    for (const fault of [false, true]) {
      const args = [suite, '--runtime', '--cache', cache, '--case', casePath, ...(fault ? ['--fault-after', 'target_python-deps'] : [])];
      let stdout;
      try { ({ stdout } = await exec('python3', args, { timeout: 35 * 60_000, maxBuffer: 64 * 1024 })); }
      catch (error) { if (!fault || error.code !== 1) throw new Error('lab_failed'); stdout = error.stdout; }
      const result = stdout.split('\n').filter(Boolean).map(line => JSON.parse(line)).find(value => typeof value.report === 'string');
      if (!result) throw new Error('lab_report_missing');
      const reportPath = await realpath(result.report), temporary = await realpath(tmpdir());
      if (!reportPath.startsWith(`${temporary}/hermes-compat-lab-`) || !reportPath.endsWith('/report.json')) throw new Error('lab_path_invalid');
      evidence[fault ? 'fault' : 'normal'] = labEvidence(await readJSON(reportPath), task.identity, fault);
      if (!fault) {
        const lockSuite = resolve('scripts/hermes-compatibility-lock-lab.py');
        const labRoot = dirname(reportPath);
        const probe = await exec('python3', [lockSuite, '--source', join(labRoot, 'user/.hermes/hermes-agent'),
          '--target', join(labRoot, 'candidate-after-experiment/.hermes/hermes-agent')], {timeout: 90_000, maxBuffer: 64 * 1024});
        const lockResult = JSON.parse(probe.stdout.trim());
        const lockPath = await realpath(lockResult.report);
        if (!lockPath.startsWith(`${temporary}/hermes-compat-lock-lab-`) || !lockPath.endsWith('/report.json')) throw new Error('lock_path_invalid');
        evidence.concurrency = lockEvidence(await readJSON(lockPath), task.identity,
          createHash('sha256').update(await readFile(lockSuite)).digest('hex'));
      }
    }
    evidence.recovery = { normal: evidence.normal, fault: evidence.fault };
  } catch { evidence.infrastructure = { reason: 'isolated_validation_incomplete' }; }
  const bundle = buildBundle(task, evidence, startedAt);
  if (!evidence.normal) { bundle.report = infrastructureReport(task); bundle.report.startedAt = startedAt;
    bundle.report.evidenceBundleSha256 = compatibilityDigest(evidence); }
  await writeFile(output, JSON.stringify(bundle) + '\n', { mode: 0o600, flag: 'wx' });
  // Incomplete validation is a recorded result, not an approval. The reporter still runs.
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(() => { console.error('HR-HERMESCOMPAT-004：验证报告未完成，请查看任务记录。 / Validation report incomplete; review the job.'); process.exitCode = 1; });
}
