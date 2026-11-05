import assert from 'node:assert/strict';
import {execFileSync, spawnSync} from 'node:child_process';
import {chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {applyGradleVersions, applyReadme} from '../bump-android-release.mjs';
import {classifyAndroidReleaseMetadata, formatObservation} from '../ci/android-release-metadata.mjs';

const GRADLE = 'android/app/build.gradle.kts';
const README = 'android/README.md';
const RELEASE = 'android/releases/0.1.195.json';
const gradle = 'import java.util.Properties\nval appVersionCode = 195\nval appVersionName = "0.1.194"\n\nandroid { namespace = "com.hermes.client" }\n';
const readme = '# Android\n\n- Version 0.1.194 previous release\n- Version 0.1.193 older release\n\nDownload Hermes-Remote-0.1.194-debug.apk\n';
const next = {versionName: '0.1.195', versionCode: 196};
const canonical = notes => `${JSON.stringify({channel: 'internal', releaseNotes: notes}, null, 2)}\n`;

function fixture(t, initial = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'android metadata comparison '));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const git = (...args) => execFileSync('git', ['-C', root, '-c', 'core.hooksPath=/dev/null', ...args], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
  const write = (file, text) => { mkdirSync(path.dirname(path.join(root, file)), {recursive: true}); writeFileSync(path.join(root, file), text); };
  const commit = () => {
    git('add', '-A');
    git('-c', 'user.name=Metadata test', '-c', 'user.email=metadata@example.invalid', 'commit', '-qm', 'fixture');
    return git('rev-parse', 'HEAD');
  };
  git('init', '-q'); git('config', 'core.filemode', 'true');
  for (const [file, text] of Object.entries({[GRADLE]: gradle, [README]: readme,
    'android/releases/0.1.194.json': canonical(['Previous release']), ...initial})) write(file, text);
  const baseSha = commit();
  const summary = 'Prepare a test package with reviewed release notes';
  write(GRADLE, applyGradleVersions(gradle, next));
  write(README, applyReadme(readme, {current: '0.1.194', next: next.versionName, summary}));
  write(RELEASE, canonical(['Release note']));
  return {root, git, write, commit, baseSha, classify(headSha = commit()) {
    return classifyAndroidReleaseMetadata({repoDir: root, baseSha, headSha});
  }};
}

test('the producer exact three-file patch is a candidate, without changing any gate', t => {
  const f = fixture(t);
  const result = f.classify();
  assert.deepEqual(result, {candidate: true, reason: 'producer_exact_release_metadata', fromVersion: '0.1.194', toVersion: '0.1.195'});
  assert.match(formatObservation(result), /Full Android checks remain enabled/);
});

test('the normal wrapped README entry is reconstructed with the producer', t => {
  const f = fixture(t);
  f.write(README, applyReadme(readme, {current: '0.1.194', next: next.versionName, summary: 'Reviewed release summary '.repeat(20)}));
  assert.equal(f.classify().candidate, true);
});

for (const [label, mutate] of [
  ['Kotlin source', f => f.write('android/app/src/main/java/Feature.kt', 'val changed = true')],
  ['dependency lock', f => f.write('package-lock.json', '{}')],
  ['CI workflow', f => f.write('.github/workflows/ci.yml', 'untrusted')],
  ['permission manifest', f => f.write('android/app/src/main/AndroidManifest.xml', '<manifest/>')],
  ['Gradle dependency', f => f.write(GRADLE, applyGradleVersions(gradle, next) + 'dependencies { implementation("unreviewed") }\n')],
  ['signing configuration', f => f.write(GRADLE, applyGradleVersions(gradle, next).replace('com.hermes.client', 'changed.namespace'))],
  ['duplicate Gradle field', f => f.write(GRADLE, applyGradleVersions(gradle, next) + 'val appVersionCode = 196\n')],
  ['patch skipped', f => { f.write(GRADLE, applyGradleVersions(gradle, {...next, versionName: '0.1.196'})); }],
  ['code skipped', f => { f.write(GRADLE, applyGradleVersions(gradle, {...next, versionCode: 197})); }],
  ['version regression', f => { f.write(GRADLE, applyGradleVersions(gradle, {...next, versionCode: 194})); }],
  ['unrelated README edit', f => f.write(README, readFileSync(path.join(f.root, README), 'utf8').replace('older release', 'rewritten history'))],
  ['new README paragraph', f => f.write(README, readFileSync(path.join(f.root, README), 'utf8') + '\nExtra instructions\n')],
  ['release channel', f => f.write(RELEASE, JSON.stringify({channel: 'beta', releaseNotes: ['note']}))],
  ['extra release field', f => f.write(RELEASE, JSON.stringify({channel: 'internal', releaseNotes: ['note'], downloadUrl: 'https://example.invalid'}))],
  ['empty release notes', f => f.write(RELEASE, canonical([]))],
  ['non-array release notes', f => f.write(RELEASE, canonical('note'))],
  ['non-string release note', f => f.write(RELEASE, canonical([123]))],
  ['whitespace release note', f => f.write(RELEASE, canonical(['   ']))],
  ['padded release note', f => f.write(RELEASE, canonical([' note ']))],
  ['control character', f => f.write(RELEASE, canonical(['line\nbreak']))],
  ['too many notes', f => f.write(RELEASE, canonical(Array(21).fill('note')))],
  ['long note', f => f.write(RELEASE, canonical(['n'.repeat(501)]))],
  ['duplicate JSON key', f => f.write(RELEASE, '{"channel":"beta","channel":"internal","releaseNotes":["note"]}\n')],
  ['noncanonical JSON', f => f.write(RELEASE, JSON.stringify({channel: 'internal', releaseNotes: ['note']}))],
  ['executable Gradle', f => chmodSync(path.join(f.root, GRADLE), 0o755)],
  ['executable README', f => chmodSync(path.join(f.root, README), 0o755)],
  ['executable release notes', f => chmodSync(path.join(f.root, RELEASE), 0o755)],
  ['symlinked Gradle', f => { unlinkSync(path.join(f.root, GRADLE)); symlinkSync('/nonexistent/private-input', path.join(f.root, GRADLE)); }],
  ['symlinked README', f => { unlinkSync(path.join(f.root, README)); symlinkSync('/nonexistent/private-input', path.join(f.root, README)); }],
  ['symlinked release notes', f => { unlinkSync(path.join(f.root, RELEASE)); symlinkSync('/nonexistent/private-input', path.join(f.root, RELEASE)); }],
  ['missing README update', f => f.write(README, readme)],
  ['missing release file', f => unlinkSync(path.join(f.root, RELEASE))],
]) {
  test(`${label} retains full validation eligibility fallback`, t => {
    const f = fixture(t);
    mutate(f);
    assert.equal(f.classify().candidate, false, label);
  });
}

test('modifying an already present release file is not a new release candidate', t => {
  const f = fixture(t, {[RELEASE]: canonical(['Allocated already'])});
  assert.equal(f.classify().candidate, false);
});

test('a renamed historical release file is rejected', t => {
  const f = fixture(t);
  unlinkSync(path.join(f.root, 'android/releases/0.1.194.json'));
  assert.equal(f.classify().candidate, false);
});

test('a base that advanced on another branch is rejected', t => {
  const f = fixture(t);
  const headSha = f.commit();
  f.git('checkout', '--detach', f.baseSha);
  f.write('unrelated.md', 'main moved');
  const advancedBase = f.commit();
  assert.equal(classifyAndroidReleaseMetadata({repoDir: f.root, baseSha: advancedBase, headSha}).candidate, false);
});

test('revision failures and unavailable trees produce a safe non-candidate', () => {
  for (const [baseSha, headSha] of [['HEAD', 'origin/main'], ['0'.repeat(40), 'f'.repeat(40)], ['', ''], ['a'.repeat(40), 'a'.repeat(40)]]) {
    assert.equal(classifyAndroidReleaseMetadata({repoDir: '/nonexistent', baseSha, headSha}).candidate, false);
  }
});

test('classification reads immutable Git blobs, not uncommitted working files', t => {
  const f = fixture(t);
  const headSha = f.commit();
  f.write(GRADLE, 'uncommitted fake secret');
  f.write(RELEASE, 'uncommitted fake secret');
  assert.equal(f.classify(headSha).candidate, true);
});

test('CLI JSON and summaries expose no notes or low-level failure bodies', t => {
  const f = fixture(t);
  f.write(RELEASE, canonical(['synthetic-private-description']));
  const headSha = f.commit();
  const script = path.resolve('scripts/ci/android-release-metadata.mjs');
  for (const args of [[f.baseSha, headSha, '--json'], [f.baseSha, headSha], ['bad-input-secret', headSha, '--json']]) {
    const result = spawnSync(process.execPath, [script, ...args], {cwd: f.root, encoding: 'utf8'});
    assert.equal(result.status, 0);
    assert.doesNotMatch(result.stdout + result.stderr, /synthetic-private-description|bad-input-secret|fatal:/);
    if (args.at(-1) === '--json') assert.equal(typeof JSON.parse(result.stdout).candidate, 'boolean');
    else assert.match(result.stdout, /Full Android checks remain enabled/);
  }
});

test('observation step cannot change the existing Android job gates or classifier outputs', () => {
  const workflow = readFileSync('.github/workflows/ci.yml', 'utf8');
  const currentClassifier = readFileSync('scripts/ci/changed-components.mjs', 'utf8');
  assert.match(workflow, /Observe Android release metadata candidates \(no gate changes\)/);
  assert.match(workflow, /android-release-metadata\.mjs "\$BASE_SHA" "\$HEAD_SHA" >> "\$GITHUB_STEP_SUMMARY"/);
  for (const name of ['android', 'android-lint']) {
    const job = workflow.split(`\n  ${name}:\n`)[1]?.split(/\n  [a-z][\w-]*:\n/)[0];
    assert.match(job, /^    if: needs\.changes\.outputs\.android == 'true'$/m);
    assert.doesNotMatch(job, /metadata|candidate/);
  }
  assert.doesNotMatch(currentClassifier, /classifyAndroidReleaseMetadata|metadata|candidate/);
});
