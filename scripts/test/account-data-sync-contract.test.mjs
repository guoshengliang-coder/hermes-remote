import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {
  SyncContractModel, entityKey, initialReadState, isUnread, parseMutation, revision,
} from './fixtures/account-data-sync-model.mjs';

const vectors = JSON.parse(await readFile(new URL('../../docs/account-data-sync/v1-fixtures.json', import.meta.url), 'utf8'));
const {a, b, otherOrigin} = vectors.principals;
const {pin, pinOtherMac, read, favorite} = vectors.entities;
const set = (entity, opId, value, baseRevision = '0', accessGeneration = '1') =>
  ({entity, opId, value, baseRevision, accessGeneration});

for (const trace of vectors.traces) {
  test(`portable contract trace: ${trace.name}`, () => {
    const model = new SyncContractModel();
    const observations = new Map();
    for (const step of trace.steps) {
      const actor = vectors.principals[step.actor];
      assert.ok(actor, step.actor);
      if (step.action === 'access') {
        assert.equal(model.access(actor, step.deviceId, step.active), step.expect);
      } else if (step.action === 'observe') {
        observations.set(step.saveAs, model.observe(actor, vectors.entities[step.entity], step.accessGeneration, step.through));
      } else if (step.action === 'mutate') {
        const entity = vectors.entities[step.entity];
        const mutation = entity.kind === 'read'
          ? {opId: step.opId, entity, accessGeneration: step.accessGeneration, observation: observations.get(step.observationRef)}
          : set(entity, step.opId, step.value, step.baseRevision, step.accessGeneration);
        assert.deepEqual(model.mutate(actor, mutation), step.expect, trace.name);
      } else {
        assert.equal(step.action, 'visible', 'unknown trace instruction');
        const snapshot = model.snapshot(actor);
        assert.deepEqual(snapshot.rows.map((r) => r.value), step.expectValues);
        assert.equal(snapshot.cursor.revision, step.expectRevision);
      }
    }
  });
}

test('wire revisions preserve unsigned 64-bit precision and reject numeric/coercible values', () => {
  assert.equal(revision('9007199254740993'), 9007199254740993n);
  assert.equal(revision('18446744073709551615'), (1n << 64n) - 1n);
  for (const value of [1, null, '', '00', '-1', ' 1', '1.0', '1e3', '18446744073709551616']) {
    assert.throws(() => revision(value), /invalid_revision/);
  }
});

test('exhausted revisions refuse a write before changing entities or permissions', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  const [, state] = model.account(a);
  state.revision = (1n << 64n) - 1n;
  assert.throws(() => model.mutate(a, set(pin, 'overflow', true)), /revision_exhausted/);
  assert.deepEqual(model.snapshot(a).rows, []);
  state.accessRevision = (1n << 64n) - 1n;
  assert.throws(() => model.access(a, 'mac-a', false), /revision_exhausted/);
  assert.equal(model.authorized(model.account(a)[0], pin, '1'), true);
});

test('structured keys separate equal session IDs on different Macs, profiles, and incarnations', () => {
  const entities = [pin, pinOtherMac, {...pin, profile: 'another'}, {...pin, sessionEpoch: 'epoch-2'}];
  assert.equal(new Set(entities.map(entityKey)).size, 4);
  assert.notEqual(entityKey({...favorite, provider: 'a/b', model: 'c'}),
    entityKey({...favorite, provider: 'a', model: 'b/c'}));
  assert.notEqual(entityKey({...pin, profile: 'Default'}), entityKey(pin));
  assert.throws(() => entityKey({...pin, profile: ''}), /invalid_entity/);
});

test('mutations reject account injection, toggle, unbound generation and client read watermarks', () => {
  const request = set(pin, 'pin-1', true);
  for (const bad of [
    {...request, accountId: 'other-account'}, {...request, toggle: true},
    {...request, value: 'true'}, {...request, accessGeneration: 1},
    {...request, entity: {...pin, extra: 'x'}},
    {opId: 'r', entity: read, accessGeneration: '1', readThrough: '999'},
    set(favorite, 'f', true, '0', '1'),
  ]) assert.throws(() => parseMutation(bad));
});

test('conflicting unpin cannot be silently rebased; tombstone blocks base-zero resurrection', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  model.mutate(a, set(pin, 'pin', true));
  const staleUnpin = set(pin, 'stale-unpin', false);
  const conflict = model.mutate(a, staleUnpin);
  assert.equal(conflict.status, 'conflict');
  assert.equal(conflict.current.value, true);
  assert.equal(model.mutate(a, set(pin, 'explicit-unpin', false, '1')).revision, '2');
  assert.deepEqual(model.mutate(a, staleUnpin), conflict, 'terminal result remains idempotent');
  assert.equal(model.mutate(a, set(pin, 'old-import', true)).status, 'conflict');
  assert.equal(model.snapshot(a).rows[0].value, false);
});

test('same op ID with changed payload is rejected, including changed field ordering independence', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  const request = set(pin, 'one', true);
  model.mutate(a, request);
  assert.equal(model.mutate(a, {...request, entity: Object.fromEntries(Object.entries(pin).reverse())}).status, 'accepted');
  assert.equal(model.mutate(a, {...request, value: false}).status, 'op_mismatch');
  assert.equal(model.snapshot(a).cursor.revision, '1');
});

test('op IDs and favorites are account/environment scoped rather than device credentials', () => {
  const model = new SyncContractModel();
  assert.equal(model.mutate(a, set(favorite, 'same', true, '0', '0')).status, 'accepted');
  assert.equal(model.mutate(b, set(favorite, 'same', false, '0', '0')).status, 'accepted');
  assert.deepEqual(model.snapshot(otherOrigin).rows, []);
  assert.equal(model.snapshot(a).rows[0].value, true);
  assert.equal(model.snapshot(b).rows[0].value, false);
});

test('read observation is bound to account, Mac, profile, session, epoch and access generation', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  model.access(a, 'mac-b', true);
  model.access(b, 'mac-a', true);
  const observation = model.observe(a, read, '1', '12');
  let i = 0;
  for (const [actor, entity] of [
    [b, read], [a, {...read, deviceId: 'mac-b'}], [a, {...read, profile: 'other'}],
    [a, {...read, sessionId: 'other'}], [a, {...read, sessionEpoch: 'epoch-2'}],
  ]) {
    assert.equal(model.mutate(actor, {entity, opId: `wrong-${i++}`, observation, accessGeneration: '1'}).status, 'invalid_observation');
  }
  model.access(a, 'mac-a', false);
  model.access(a, 'mac-a', true);
  assert.equal(model.mutate(a, {entity: read, opId: 'old-generation', observation, accessGeneration: '3'}).status, 'invalid_observation');
});

test('an unissued proof cannot advance account read state', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  assert.equal(model.mutate(a, {entity: read, opId: 'r', observation: 'made-up', accessGeneration: '1'}).status, 'invalid_observation');
  assert.equal(model.snapshot(a).cursor.revision, '0');
});

test('late read of 12 does not clear head 13, and late read 10 cannot move the account backwards', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  const observe12 = model.observe(a, read, '1', '12');
  const observe10 = model.observe(a, read, '1', '10');
  model.mutate(a, {entity: read, opId: 'r12', observation: observe12, accessGeneration: '1'});
  model.mutate(a, {entity: read, opId: 'r10', observation: observe10, accessGeneration: '1'});
  const through = model.snapshot(a).rows[0].value;
  assert.equal(through, '12');
  assert.equal(isUnread({baselineThrough: '8', legacyUnread: false}, '13', through), true);
  assert.equal(isUnread({baselineThrough: '8', legacyUnread: false}, '12', through), false);
});

test('first-install baseline preserves mapped unread, quarantines unknown tokens, and notices only new activity', () => {
  const otherRead = {...read, deviceId: 'mac-b'};
  const knownUnread = new Set([entityKey(read), 'unmapped-legacy-token']);
  const result = initialReadState([{entity: read, through: '12'}, {entity: otherRead, through: '7'}], knownUnread);
  assert.deepEqual(result.unmapped, ['unmapped-legacy-token']);
  assert.equal(isUnread(result.rows[0], '12'), true);
  assert.equal(isUnread(result.rows[0], '12', '12'), false);
  assert.equal(isUnread(result.rows[1], '7'), false);
  assert.equal(isUnread(result.rows[1], '8'), true);
  assert.throws(() => initialReadState([{entity: read, through: '12'}, {entity: read, through: '13'}], knownUnread), /duplicate_baseline/);
  // Even an empty historical conversation can carry an explicit migrated unread marker.
  assert.equal(isUnread({baselineThrough: '0', legacyUnread: true}, '0'), true);
  assert.equal(isUnread({baselineThrough: '0', legacyUnread: true}, '0', '0'), false);
});

test('snapshot is frozen while later mutations appear after its boundary', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  model.mutate(a, set(pin, 'pin', true));
  const snapshot = model.snapshot(a);
  model.mutate(a, set(pin, 'unpin', false, '1'));
  assert.equal(snapshot.rows[0].value, true);
  const changes = model.changes(a, snapshot.cursor, 200);
  assert.equal(changes.hasMore, false);
  assert.deepEqual(changes.rows.map((r) => r.value), [false]);
});

test('empty filtered page advances scanned cursor, followed by a visible change', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  model.mutate(a, set(pin, 'pin', true));
  model.access(a, 'mac-a', false);
  model.mutate(a, set(favorite, 'favorite', true, '0', '0'));
  const current = model.snapshot(a).cursor;
  // Fixture cursor represents a log scan begun at the current permission epoch, revision zero.
  const first = model.changes(a, {...current, revision: '0'}, 1);
  assert.deepEqual(first.rows, []);
  assert.equal(first.nextCursor.revision, '1');
  assert.equal(first.hasMore, true);
  const second = model.changes(a, first.nextCursor, 1);
  assert.equal(second.rows[0].entity.kind, 'favorite');
  assert.equal(second.hasMore, false);
});

test('revocation invalidates old pagination, even after the same Mac is granted again', () => {
  const model = new SyncContractModel();
  model.access(a, 'mac-a', true);
  const before = model.snapshot(a).cursor;
  model.access(a, 'mac-a', false);
  model.access(a, 'mac-a', true);
  assert.deepEqual(model.changes(a, before, 1), {status: 'reset_required'});
  assert.deepEqual(model.changes(b, model.snapshot(a).cursor, 1), {status: 'reset_required'});
});

for (const sample of vectors.contentCounterexamples) {
  test(`source-version counterexample: ${sample.name}`, () => {
    const summary = (state) => ({count: state.messages.length, lastActive: state.lastActive,
      maxRowId: Math.max(...state.messages.map((r) => r.id)),
      maxTimestamp: Math.max(...state.messages.map((r) => r.timestamp))});
    assert.deepEqual(summary(sample.before), summary(sample.after));
    assert.notDeepEqual(sample.before.messages, sample.after.messages);
    if (sample.name.includes('latest-page')) {
      assert.deepEqual(sample.before.messages.slice(-1), sample.after.messages.slice(-1),
        'even hashing only a bounded latest page misses this change');
    }
  });
}
