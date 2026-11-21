import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { parseArgs } from 'node:util';
import { bindBatchObservation, comparePublicationScope, readReleaseSnapshot } from './lib/missiongo-release-snapshot.mjs';
import { hash, inspectReleaseTools } from './lib/hermes-release-tools.mjs';
import { releaseTargets } from './lib/hermes-release-coverage.mjs';

const repository = 'https://github.com/guoshengliang-coder/hermes-remote';
const slug = 'guoshengliang-coder/hermes-remote';
const productId = 'afde17c0-2829-41e9-be45-0fb03816b99c';
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8', timeout: 25_000 }).trim();

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function inspectMain() {
  const origin = run('git', ['remote', 'get-url', 'origin']);
  if (![repository, `${repository}.git`, `git@github.com:${slug}.git`].includes(origin)) throw new Error('Origin is not the bound Hermes GO repository');
  const local = run('git', ['rev-parse', 'refs/remotes/origin/main']);
  const remote = run('git', ['ls-remote', '--exit-code', 'origin', 'refs/heads/main']).split(/\s/)[0];
  if (remote !== local) throw new Error('Tracked main differs from the live GitHub branch');
  return remote;
}

function inspectPullRequest(entry, expectedHead, base, reference) {
  const pr = JSON.parse(run('gh', ['pr', 'view', String(entry.pullRequest), '--repo', slug,
    '--json', 'state,baseRefName,baseRefOid,headRefOid,headRepository,mergeCommit,mergeable,statusCheckRollup']));
  if (pr.baseRefName !== 'main' || pr.headRefOid !== expectedHead || pr.headRepository?.nameWithOwner !== slug) {
    throw new Error('Live pull request identity differs from the frozen delivery');
  }
  if (reference) {
    if (pr.state !== 'MERGED' || pr.mergeCommit?.oid !== reference.commit) throw new Error('Historical merge does not match GitHub');
    run('git', ['merge-base', '--is-ancestor', pr.mergeCommit.oid, base]);
  } else if (pr.state !== 'OPEN' || pr.baseRefOid !== base) {
    throw new Error('Pull request is no longer open against the observed main');
  }
  return { state: pr.state, mergeable: pr.mergeable, head: pr.headRefOid,
    checks: pr.statusCheckRollup.map(check => ({ name: check.name, status: check.status, conclusion: check.conclusion })) };
}

try {
  const { values } = parseArgs({ options: { batch: { type: 'string' }, candidates: { type: 'string' } } });
  if (!values.batch || !values.candidates) throw new Error('Supply --candidates and --batch');
  const raw = readJson(values.batch);
  const batch = raw.batch ?? raw;
  const observation = bindBatchObservation(batch, { productId, repository });
  const snapshot = readReleaseSnapshot(readJson(values.candidates), { productId });
  const checks = [];
  function observe(id, operation) {
    try {
      const value = operation();
      checks.push({ id, result: 'observed', value });
      return value;
    } catch (error) {
      checks.push({ id, result: 'unverified', reason: error.message });
      return undefined;
    }
  }
  const base = observe('main', inspectMain);
  const scopeCheck = batch.publish && observe('scope', () => comparePublicationScope(batch, snapshot));
  const planned = observation.heads.map(({ deliveryId, head }) => {
    const entry = batch.entries.find(value => value.id === deliveryId);
    const reference = batch.references?.[deliveryId];
    return { deliveryId, head, pullRequest: entry.pullRequest,
      requiredArtifacts: observe(`artifacts:${deliveryId}`, () => entry.requiredArtifacts?.length ? releaseTargets(entry.requiredArtifacts) : []),
      git: base && observe(`pr:${deliveryId}`, () => inspectPullRequest(entry, head, base, reference)),
    };
  });
  const tools = base && observe('tools', () => inspectReleaseTools(base, (...args) =>
    execFileSync('git', args, { timeout: 25_000 })));
  const protection = observe('github-protection', () => {
    const repo = JSON.parse(run('gh', ['api', `repos/${slug}`]));
    const rules = JSON.parse(run('gh', ['api', `repos/${slug}/rules/branches/main`]));
    return repo.allow_auto_merge === true && rules.some(rule => rule.type === 'required_status_checks' &&
      rule.parameters?.strict_required_status_checks_policy && rule.parameters?.required_status_checks?.length > 0);
  });
  const capability = tools?.verified === true && protection === true &&
    planned.every(entry => entry.git && Array.isArray(entry.requiredArtifacts));
  const prerequisite = (kind, good, dependencies, reason) => ({
    id: kind, kind, category: good ? 'fact' : 'blocker', stage: 'pre_merge', required: true,
    state: good ? 'satisfied' : 'unknown', reason, owner: 'Hermes GO integration executor',
    resumeWhen: 'Run the repository-native readiness command again with fresh Git and candidate evidence', dependencies,
  });
  const conditions = [
    prerequisite('main', Boolean(base) && planned.every(entry => entry.git), ['base', 'heads'], 'Live main and the exact batch PR heads match'),
    prerequisite('installation_integrity', tools?.verified === true, ['tools'], 'Repository-native release tools must match the reviewed main Git objects'),
    prerequisite('required_capability', capability, ['tools', 'capabilities'], 'Verify GitHub gates and checkout-native v2 coverage with official Android and Web publishers'),
    { ...prerequisite('authorization', tools?.verified === true, ['scope', 'tools', 'capabilities'], 'Repository-native tools require no machine-wide install'),
      ...(tools?.verified ? { state: 'not_required', authorizationRef: `Verified repository-native entry and file inventory at ${base}` } : {}) },
    prerequisite('scope', scopeCheck?.matches === true, ['scope', 'candidates'], 'Frozen publication candidates and exclusions match the complete fresh candidate snapshot'),
  ];
  const evidenceContext = base && { base, candidates: snapshot.candidatesDigest,
    tools: hash(tools?.inventory ?? null), capabilities: hash({ capability, protection, supported: tools?.capabilities ?? [] }) };
  const needsFollowUp = conditions.some(condition => !['satisfied', 'not_required'].includes(condition.state));
  console.log(JSON.stringify({ schemaVersion: 1, kind: 'integration_readiness', needsFollowUp,
    repository, productId, checkedAt: new Date().toISOString(), originMain: base, observation, evidenceContext,
    batchRevision: batch.integration?.revision, snapshot, planned, checks, scopeCheck, conditions,
  }, null, 2));
  if (needsFollowUp) process.exitCode = 1;
} catch (error) {
  console.error(`Hermes release readiness: ${error.message}`);
  process.exitCode = 1;
}
