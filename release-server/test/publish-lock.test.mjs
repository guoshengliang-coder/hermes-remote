import assert from 'node:assert/strict';
import {mkdtemp, readFile, rename, rm, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {acquireLock} from '../../deploy/publish-release.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const dataRoot = () => mkdtemp(path.join(tmpdir(), 'publish-lock-'));
const lockPath = root => path.join(root, '.publish.lock');
const owner = async root => JSON.parse(await readFile(path.join(lockPath(root), 'owner.json'), 'utf8'));
const exists = async target => stat(target).then(() => true, () => false);
const fast = {waitMs: 3_000, pollMs: 5};

test('an old owner can never delete a successor lock after kernel-fenced cleanup', async () => {
  const root = await dataRoot();
  const stalled = await acquireLock(root, fast);
  const abandoned = `${lockPath(root)}.abandoned`;
  // Production performs this cleanup only while Linux flock excludes every publisher.
  await rename(lockPath(root), abandoned);
  const fresh = await acquireLock(root, fast);
  assert.notEqual(fresh.token, stalled.token);
  assert.equal((await owner(root)).token, fresh.token);
  assert.equal(await stalled.release(), false);
  assert.equal((await owner(root)).token, fresh.token, 'the recovered publisher deleted the live lock');
  assert.equal(await fresh.release(), true);
  await rm(abandoned, {recursive: true, force: true});
  assert.equal(await exists(lockPath(root)), false);
});

test('an existing owner is never treated as stale and waiters time out instead', async () => {
  const root = await dataRoot();
  const held = await acquireLock(root, fast);
  await sleep(50);
  await assert.rejects(acquireLock(root, {...fast, waitMs: 120}), /Timed out waiting for publish lock/);
  assert.equal((await owner(root)).token, held.token, 'a live lock was stolen from its owner');
  assert.equal(await held.release(), true);
});

test('a publisher that lost its lock fails the ownership assertion', async () => {
  const root = await dataRoot();
  const stalled = await acquireLock(root, fast);
  await stalled.assertHeld();
  const abandoned = `${lockPath(root)}.abandoned`;
  await rename(lockPath(root), abandoned);
  const fresh = await acquireLock(root, fast);
  await assert.rejects(stalled.assertHeld(), /lost the publish lock/);
  await fresh.assertHeld();
  await fresh.release();
  await rm(abandoned, {recursive: true, force: true});
});

test('concurrent publishers never hold the lock at the same time', async () => {
  const root = await dataRoot();
  let active = 0;
  let peak = 0;
  let entered = 0;
  await Promise.all(Array.from({length: 8}, async () => {
    const lock = await acquireLock(root, fast);
    active += 1;
    entered += 1;
    peak = Math.max(peak, active);
    await sleep(5);
    active -= 1;
    assert.equal(await lock.release(), true);
  }));
  assert.equal(entered, 8);
  assert.equal(peak, 1);
  assert.equal(await exists(lockPath(root)), false);
});


