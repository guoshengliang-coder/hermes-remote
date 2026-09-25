import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtemp, readFile, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SCRIPT = path.join(ROOT, 'scripts', 'desktop-app-update-index.mjs');

async function fixture({notes = ['支持自动更新检查']} = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'desktop-app-index-'));
  const version = '0.2.29';
  const dmg = path.join(dir, `Hermes-Go-Desktop-${version}.dmg`);
  const bytes = Buffer.from('fake dmg payload');
  await writeFile(dmg, bytes);
  const notesFile = path.join(dir, 'notes.json');
  await writeFile(notesFile, JSON.stringify(notes));
  const output = path.join(dir, 'out');
  return {dir, version, dmg, notesFile, output, sha: createHash('sha256').update(bytes).digest('hex')};
}

function run(args) {
  return execFileSync('node', [SCRIPT, ...args], {encoding: 'utf8'});
}

test('writes an index that matches the DMG bytes', async () => {
  const {version, dmg, notesFile, output, sha} = await fixture();
  const stdout = run([
    '--version', version,
    '--channel', 'internal',
    '--architecture', 'arm64',
    '--dmg', dmg,
    '--origin', 'https://mrlgs.net',
    '--notes-file', notesFile,
    '--source-commit', 'a'.repeat(40),
    '--output', output,
    '--build-number', '32',
  ]);
  assert.match(stdout, /DESKTOP_APP_UPDATE_INDEX_OK/);

  const index = JSON.parse(await readFile(path.join(output, 'index.json'), 'utf8'));
  assert.equal(index.appVersion, version);
  assert.equal(index.buildNumber, 32);
  assert.equal(index.sha256, sha);
  assert.equal(index.sizeBytes, Buffer.byteLength('fake dmg payload'));
  assert.equal(
    index.downloadURL,
    `https://mrlgs.net/desktop/apps/${version}/Hermes-Go-Desktop-${version}.dmg`,
  );
  assert.deepEqual(index.releaseNotes, ['支持自动更新检查']);
});

test('refuses a DMG whose name does not match the version', async () => {
  const {version, dir, notesFile, output} = await fixture();
  const wrong = path.join(dir, 'Hermes-Go-Desktop-0.2.28.dmg');
  await writeFile(wrong, 'x');
  assert.throws(() =>
    execFileSync('node', [SCRIPT,
      '--version', version,
      '--channel', 'internal',
      '--architecture', 'arm64',
      '--dmg', wrong,
      '--origin', 'https://mrlgs.net',
      '--notes-file', notesFile,
      '--source-commit', 'a'.repeat(40),
      '--output', output,
      '--build-number', '32',
    ], {stdio: 'pipe'}),
  );
});

test('rejects oversized or multi-line notes', async () => {
  for (const notes of [Array.from({length: 21}, () => 'x'), ['line\nbreak']]) {
    const {version, dmg, notesFile, output} = await fixture({notes});
    assert.throws(() =>
      execFileSync('node', [SCRIPT,
        '--version', version,
        '--channel', 'internal',
        '--architecture', 'arm64',
        '--dmg', dmg,
        '--origin', 'https://mrlgs.net',
        '--notes-file', notesFile,
        '--source-commit', 'a'.repeat(40),
        '--output', output,
        '--build-number', '32',
      ], {stdio: 'pipe'}),
    );
  }
});

test('refuses to overwrite an existing index', async () => {
  const {version, dmg, notesFile, output} = await fixture();
  const args = [
    '--version', version,
    '--channel', 'internal',
    '--architecture', 'arm64',
    '--dmg', dmg,
    '--origin', 'https://mrlgs.net',
    '--notes-file', notesFile,
    '--source-commit', 'a'.repeat(40),
    '--output', output,
    '--build-number', '32',
  ];
  run(args);
  assert.throws(() => execFileSync('node', [SCRIPT, ...args], {stdio: 'pipe'}));
});
