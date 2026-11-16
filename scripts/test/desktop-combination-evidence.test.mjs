import assert from 'node:assert/strict';
import test from 'node:test';
import { assertCombinationEvidence, assertAppIndexMatchesBundle, buildCombinationEvidence, digest, requiredHermesChecks } from '../lib/desktop-combination-evidence.mjs';

function fixture() {
  const app = { version: '0.2.53', build: 56, sourceCommit: 'a'.repeat(40) };
  const manifest = { schemaVersion: 2, releaseVersion: '0.4.18', components: [
    { kind: 'node_runtime', version: '22.23.2', contentSHA256: 'b'.repeat(64), dependencies: [] },
    { kind: 'connector', version: '0.1.21', contentSHA256: 'c'.repeat(64), dependencies: [{ kind: 'node_runtime', contentSHA256: 'b'.repeat(64) }] },
  ] };
  return { app, bundleSHA256: '3'.repeat(64), executable: Buffer.from('binary'), manifest, manifestBytes: Buffer.from('signed envelope'),
    upstream: { runtimeIdentity: { interpreterSHA256: '1'.repeat(64), importedCodeSHA256: '2'.repeat(64), importedModuleCount: 100 }, passed: true, sourceCommit: 'd'.repeat(40), checks: requiredHermesChecks.map(name => ({ name, passed: true })) },
    upstreamBytes: Buffer.from('probe'), hermesCommit: 'd'.repeat(40) };
}
test('exact combination retains its remaining physical gates', () => {
  const f = fixture(); const evidence = buildCombinationEvidence(f);
  assert.equal(assertCombinationEvidence(evidence, { ...f.app, bundleSHA256: f.bundleSHA256, executableSHA256: digest(f.executable), manifestSHA256: digest(f.manifestBytes) }), true);
  assert.equal(evidence.scope, 'isolated_handlers_only'); assert.equal(evidence.remainingVerification.length, 3);
});
test('changed app, source or component manifest cannot reuse evidence', () => {
  const f = fixture(), evidence = buildCombinationEvidence(f);
  const expected = { ...f.app, bundleSHA256: f.bundleSHA256, executableSHA256: digest(f.executable), manifestSHA256: digest(f.manifestBytes) };
  for (const [key, value] of [['bundleSHA256', '4'.repeat(64)], ['build', 57], ['sourceCommit', 'e'.repeat(40)], ['executableSHA256', 'f'.repeat(64)], ['manifestSHA256', 'f'.repeat(64)]]) {
    assert.throws(() => assertCombinationEvidence(evidence, { ...expected, [key]: value }));
  }
});
test('missing, failed, future-source and inexact dependency evidence is refused', () => {
  for (const mutate of [f => f.upstream.checks.pop(), f => { f.upstream.checks[0].passed = false; },
    f => { f.hermesCommit = 'e'.repeat(40); }, f => { f.manifest.components[1].dependencies[0].contentSHA256 = 'e'.repeat(64); }]) {
    const f = fixture(); mutate(f); assert.throws(() => buildCombinationEvidence(f));
  }
});

test('helper and configuration changes cannot reuse main-executable-only evidence', () => {
  const f = fixture(), evidence = buildCombinationEvidence(f);
  const expected = { ...f.app, executableSHA256: digest(f.executable), manifestSHA256: digest(f.manifestBytes) };
  assert.throws(() => assertCombinationEvidence(evidence, expected));
  assert.throws(() => buildCombinationEvidence({ ...f, bundleSHA256: undefined }));
});

test('publication index must match bundled channel, architecture and minimum OS', () => {
  const index = { appVersion: '0.2.53', buildNumber: 56, minimumMacOS: '14.0', channel: 'internal', architecture: 'arm64' };
  const plist = { CFBundleShortVersionString: '0.2.53', CFBundleVersion: '56', LSMinimumSystemVersion: '14.0',
    HermesGoDesktopAppUpdateChannel: 'internal', HermesGoDesktopAppUpdateArchitecture: 'arm64' };
  assert.equal(assertAppIndexMatchesBundle(index, plist), true);
  for (const [key, value] of [['appVersion', '0.2.52'], ['buildNumber', 55], ['minimumMacOS', '13.0'],
    ['channel', 'stable'], ['architecture', 'x86_64']]) {
    assert.throws(() => assertAppIndexMatchesBundle({ ...index, [key]: value }, plist));
  }
});
