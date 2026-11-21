import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';

const files = [
  'scripts/release-readiness.mjs',
  'scripts/hermes-release-publish.mjs',
  'scripts/lib/hermes-release-tools.mjs',
  'scripts/lib/hermes-release-coverage.mjs',
  'scripts/lib/missiongo-release-snapshot.mjs',
  'scripts/android-release-train.mjs',
  'scripts/publish-web-app.sh',
  'scripts/package-debug-apk.sh',
  'deploy/publish-web-app.mjs',
  '.github/workflows/android-release.yml',
];

export const hash = value => createHash('sha256').update(typeof value === 'string' || Buffer.isBuffer(value)
  ? value : JSON.stringify(value)).digest('hex');

// These tools run from the reviewed checkout. No machine-wide installation is needed.
export function inspectReleaseTools(base, git) {
  const inventory = files.map(file => {
    const stat = lstatSync(file);
    if (!stat.isFile()) throw new Error(`Release tool is not a regular file: ${file}`);
    const actual = hash(readFileSync(file));
    const expected = hash(git('show', `${base}:${file}`));
    return { file, actual, expected, matches: actual === expected };
  });
  return { inventory, verified: inventory.every(file => file.matches),
    capabilities: ['coverage-v2', 'live-github-integration', 'android-official-release-train', 'web-atomic-publisher'] };
}

export function verifyPullRequestState(pr, { expectedHead, base, reference, git }) {
  if (pr.state === 'MERGED') {
    const commit = pr.mergeCommit?.oid;
    if (!/^[0-9a-f]{40}$/.test(commit ?? '') || (reference && commit !== reference.commit)) {
      throw new Error('Merge commit does not match the observed reference');
    }
    // The selected PR can already be merged when the server needs proof refreshed. A fresh
    // observation binds that same head to an actual merge on main without rerunning Git actions.
    git('merge-base', '--is-ancestor', commit, base);
    git('merge-base', '--is-ancestor', expectedHead, commit);
    return;
  }
  if (reference || pr.state !== 'OPEN' || pr.baseRefOid !== base) {
    throw new Error('Pull request is not open against the observed main and has no verified merge');
  }
}
