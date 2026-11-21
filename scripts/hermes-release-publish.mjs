import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { checkReleaseCoverage } from './lib/hermes-release-coverage.mjs';
import { readReleaseSnapshot } from './lib/missiongo-release-snapshot.mjs';
import { inspectReleaseTools } from './lib/hermes-release-tools.mjs';

const slug = 'guoshengliang-coder/hermes-remote';
const repository = `https://github.com/${slug}`;
const productId = 'afde17c0-2829-41e9-be45-0fb03816b99c';
const git = (...args) => execFileSync('git', args, { timeout: 25_000 });
const gitText = (...args) => git(...args).toString('utf8').trim();

function cleanCurrentMain(expected) {
  const origin = gitText('remote', 'get-url', 'origin');
  if (![repository, `${repository}.git`, `git@github.com:${slug}.git`].includes(origin)) throw new Error('Unexpected repository origin');
  const remote = gitText('ls-remote', '--exit-code', 'origin', 'refs/heads/main').split(/\s/)[0];
  if (expected !== remote || expected !== gitText('rev-parse', 'HEAD') || gitText('status', '--porcelain')) {
    throw new Error('Use a clean release checkout at the current exact main commit');
  }
}

function resolveIntegration(candidate) {
  const ref = candidate.completion;
  const url = ref?.kind === 'github' ? ref.url : !ref ? candidate.pullRequestUrl : undefined;
  const prefix = `${repository}/pull/`;
  if (typeof url !== 'string' || !url.startsWith(prefix) || !/^[1-9][0-9]*$/.test(url.slice(prefix.length))) return null;
  const pr = JSON.parse(execFileSync('gh', ['pr', 'view', url.slice(prefix.length), '--repo', slug,
    '--json', 'url,state,baseRefName,mergeCommit,headRefOid'], { timeout: 25_000, encoding: 'utf8' }));
  if (pr.state !== 'MERGED' || pr.url !== url || pr.baseRefName !== 'main' ||
      (ref?.head && ref.head !== pr.headRefOid) || (ref?.commit && ref.commit !== pr.mergeCommit?.oid)) return null;
  return { repository, branch: pr.baseRefName, commit: pr.mergeCommit?.oid };
}

function invoke(command, args, env = process.env) {
  const result = spawnSync(command, args, { stdio: 'inherit', env });
  if (result.status !== 0) throw new Error(`Official publisher ended without success (${result.error?.message ?? result.signal ?? result.status})`);
}

function mainCheck(head) {
  const runs = JSON.parse(execFileSync('gh', ['run', 'list', '--repo', slug, '--branch', 'main', '--commit', head,
    '--event', 'push', '--json', 'workflowName,status,conclusion', '--limit', '100'], { encoding: 'utf8', timeout: 25_000 }));
  if (!runs.some(run => run.workflowName === 'CI' && run.conclusion === 'success') ||
      runs.some(run => run.status !== 'completed' || !['success', 'skipped', 'neutral'].includes(run.conclusion))) {
    throw new Error('Required main CI evidence is incomplete or unsuccessful');
  }
}

try {
  const { values } = parseArgs({ options: { candidates: { type: 'string' }, head: { type: 'string' },
    'candidates-digest': { type: 'string' }, 'android-version': { type: 'string' }, web: { type: 'boolean' }, publish: { type: 'boolean' } } });
  const { candidates, head, 'candidates-digest': expectedDigest, 'android-version': version } = values;
  if (!candidates || !head || !expectedDigest || (!version && !values.web)) throw new Error('Supply fresh candidates, approved candidate digest, exact head, and release targets');
  if (version && !/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Invalid Android version');
  cleanCurrentMain(head);
  const tools = inspectReleaseTools(head, git);
  if (!tools.verified) throw new Error('Repository-native publisher differs from the reviewed main commit');
  const snapshot = readReleaseSnapshot(JSON.parse(readFileSync(candidates, 'utf8')), { productId });
  if (snapshot.candidatesDigest !== expectedDigest) throw new Error('Candidate range changed after review');
  if (!snapshot.candidates.length) throw new Error('No integrated MissionGo delivery is available for publication');
  const artifacts = { ...(version ? { android: { sourceCommit: head } } : {}), ...(values.web ? { web: { sourceCommit: head } } : {}) };
  const coverage = await checkReleaseCoverage({ snapshot, repository, targetHead: head, artifacts,
    resolveIntegration, contains: (descendant, ancestor) => {
      const result = spawnSync('git', ['merge-base', '--is-ancestor', ancestor, descendant], { timeout: 25_000 });
      if (![0, 1].includes(result.status)) throw new Error('Commit ancestry is unavailable');
      return result.status === 0;
    } });
  console.log(JSON.stringify({ head, candidatesDigest: snapshot.candidatesDigest, coverage }));
  if (!coverage.ready) throw new Error('At least one required artifact lacks verified integration coverage');
  mainCheck(head);
  if (values.publish) {
    const common = gitText('rev-parse', '--path-format=absolute', '--git-common-dir');
    if (gitText('rev-parse', '--absolute-git-dir') === common) throw new Error('Publication requires an isolated worktree');
    const lock = join(common, 'hermes-missiongo-publish.lock');
    mkdirSync(lock, { mode: 0o700 });
    try {
      cleanCurrentMain(head);
      if (readReleaseSnapshot(JSON.parse(readFileSync(candidates, 'utf8')), { productId }).candidatesDigest !== expectedDigest) {
        throw new Error('Candidate range changed while acquiring the publication lock');
      }
      if (version) invoke(process.execPath, ['scripts/android-release-train.mjs', 'publish', version]);
      cleanCurrentMain(head);
      if (values.web) invoke('bash', ['scripts/publish-web-app.sh'], { ...process.env, WEB_PUBLISH_VERIFY_PUBLIC: '1' });
    } finally {
      rmdirSync(lock);
    }
  }
} catch (error) {
  console.error(`Hermes publication gate: ${error.message}`);
  process.exitCode = 1;
}
