import assert from 'node:assert/strict';
import test from 'node:test';
import { bindBatchObservation, comparePublicationScope, readReleaseSnapshot } from '../lib/missiongo-release-snapshot.mjs';

const repository = 'https://github.com/guoshengliang-coder/hermes-remote';
const productId = 'hermes';
const now = Date.parse('2026-11-21T12:00:00Z');
const deliveryId = 'd76929ef-cf46-4ee2-95f4-dfec07ecc65e';
const revision = 'b'.repeat(64);
const head = 'a'.repeat(40);
const options = { productId, repository, now };
const page = (overrides = {}) => ({ productId, requestedBeforeSequence: null, coverageVersion: 2,
  generatedAt: new Date(now).toISOString(), candidates: [], noReleaseItems: [], noReleaseDeliveries: [], ...overrides });

const delivery = { kind: 'delivery', deliveryId, expectedRevision: revision, requiredArtifacts: ['web'] };

test('includes standalone deliveries and exclusions after an empty first page', () => {
  const first = page({ nextBeforeSequence: 20 });
  const second = page({ requestedBeforeSequence: 20, candidates: [delivery],
    noReleaseItems: [{ itemKey: 'HG-1', noReleaseReason: 'Docs only' }] });
  const result = readReleaseSnapshot({ pages: [first, second] }, options);
  assert.deepEqual(result.candidates, [delivery]);
  assert.equal(result.noReleaseItems[0].itemKey, 'HG-1');
  assert.equal(result.candidatesDigest, readReleaseSnapshot({ pages: [page({
    candidates: second.candidates, noReleaseItems: second.noReleaseItems,
  })] }, options).candidatesDigest);
  assert.throws(() => readReleaseSnapshot({ pages: [first] }, options), /Missing final/);
});

test('rejects stale, foreign, duplicate and old coverage snapshots', () => {
  for (const invalid of [page({ productId: 'other' }), page({ coverageVersion: 1 }),
    page({ generatedAt: '2026-11-21T10:00:00Z' }), page({ candidates: [delivery, delivery] })]) {
    assert.throws(() => readReleaseSnapshot({ pages: [invalid] }, options));
  }
});

test('binds the observed execution head and exact frozen scope to the original batch', () => {
  const batch = { id: '61266e9d-1285-4b88-bfb1-c0fed776152c', productId, publish: true,
    operation: 'merge_and_publish', publicationScope: { digest: revision }, deliveries: [deliveryId],
    selection: [{ id: deliveryId, revision }], entries: [{ repository, targetBranch: 'main', pullRequest: 756,
      id: deliveryId, head: 'c'.repeat(40), executionHead: head }] };
  const observed = bindBatchObservation(batch, options);
  assert.deepEqual(observed.heads, [{ deliveryId, head }]);
  assert.notEqual(observed.scope, bindBatchObservation({ ...batch,
    publicationScope: { digest: 'a'.repeat(64) } }, options).scope);
  assert.throws(() => bindBatchObservation(batch, { ...options, repository: `${repository}-other` }), /does not match/);
});

test('scope admits a frozen selected open PR and detects newly added release candidates', () => {
  const row = { identity: `selection:${deliveryId}`, deliveryId, selected: true,
    expectedRevision: revision, requiredArtifacts: ['web'] };
  const batch = { deliveries: [deliveryId], selection: [{ id: deliveryId, revision }],
    entries: [{ id: deliveryId, requiredArtifacts: ['web'] }],
    publicationScope: { candidates: [row], exclusions: [], blockers: [] } };
  const snapshot = readReleaseSnapshot({ pages: [page()] }, options);
  assert.equal(comparePublicationScope(batch, snapshot).matches, true);
  assert.equal(comparePublicationScope(batch, { ...snapshot, candidates: [delivery] }).matches, false);
  assert.equal(comparePublicationScope(batch, { ...snapshot,
    noReleaseItems: [{ itemKey: 'HG-1', noReleaseReason: 'Docs only' }] }).matches, false);
});
