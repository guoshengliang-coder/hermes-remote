import assert from 'node:assert/strict';
import test from 'node:test';
import { checkReleaseCoverage, releaseTargets } from '../lib/hermes-release-coverage.mjs';

const repository = 'https://github.com/guoshengliang-coder/hermes-remote';
const main = 'a'.repeat(40);
const merged = 'b'.repeat(40);
const previous = 'c'.repeat(40);
const first = { itemKey: 'HG-259', developmentEventId: 'one', requiredArtifacts: ['android', 'web'] };
const second = { ...first, developmentEventId: 'two', requiredArtifacts: ['website'] };
const evaluate = (candidates, artifacts, resolveIntegration = async () => ({ repository, branch: 'main', commit: merged })) =>
  checkReleaseCoverage({ snapshot: { candidates }, artifacts, repository, targetHead: main, resolveIntegration,
    contains: async (descendant, ancestor) => descendant === main || descendant === ancestor });

test('requires both artifact source commits to contain the candidate merge', async () => {
  const android = { sourceCommit: main };
  const partial = await evaluate([first], { android, web: { sourceCommit: previous } });
  assert.equal(partial.ready, false);
  assert.deepEqual(partial.results[0].checks.map(check => check.covered), [true, false]);
  assert.equal((await evaluate([first], { android, web: android })).ready, true);
});

test('preserves every delivery and fails closed for unknown targets or unverified merges', async () => {
  const result = await evaluate([first, second], {}, async () => null);
  assert.equal(result.ready, false);
  assert.deepEqual(result.results.map(entry => entry.identity), ['HG-259:one', 'HG-259:two']);
  assert.match(result.results[0].reason, /integration/);
  assert.match(result.results[1].reason, /Unsupported/);
  assert.throws(() => releaseTargets(['androidApp', 'android']), /duplicate/);
});
