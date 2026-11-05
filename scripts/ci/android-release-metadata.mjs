// Pure content classifier. Only the BASE-loaded gate can authorize skipping PR Android jobs.
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {applyGradleVersions, applyReadme, nextVersion, parseGradleVersions, validateNotes} from '../bump-android-release.mjs';

const GRADLE = 'android/app/build.gradle.kts';
const README = 'android/README.md';
const SHA = /^[0-9a-f]{40}$/;
const reject = reason => ({candidate: false, reason});

function git(repoDir, args) {
  const bytes = execFileSync('git', ['-C', repoDir, ...args], {
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 2 * 1024 * 1024, timeout: 5_000,
    env: {...process.env, GIT_NO_REPLACE_OBJECTS: '1'},
  });
  // Reject malformed bytes instead of silently replacing them; preserve any BOM for exact matching.
  return new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes);
}

function versions(text) {
  // Require one complete declaration per field. The producer's parser alone accepts duplicates.
  for (const pattern of [
    /^val[ \t]+appVersionName[ \t]*=[ \t]*"[^"\r\n]+"[ \t]*$/gm,
    /^val[ \t]+appVersionCode[ \t]*=[ \t]*[0-9]+[ \t]*$/gm,
  ]) if ([...text.matchAll(pattern)].length !== 1) throw new Error();
  const version = parseGradleVersions(text);
  if (!Number.isSafeInteger(version.versionCode) || version.versionCode < 1 || version.versionCode > 2_100_000_000) throw new Error();
  if (!version.versionName.split('.').every(n => Number.isSafeInteger(Number(n)))) throw new Error();
  return version;
}

function rawChanges(text) {
  const parts = text.split('\0');
  if (parts.pop() !== '' || parts.length % 2) throw new Error();
  const rows = [];
  for (let i = 0; i < parts.length; i += 2) {
    const header = /^:(\d{6}) (\d{6}) ([0-9a-f]{40}) ([0-9a-f]{40}) ([AM])$/.exec(parts[i]);
    if (!header) throw new Error();
    rows.push({oldMode: header[1], newMode: header[2], status: header[5], path: parts[i + 1]});
  }
  return rows;
}

function releaseSummary(readme, version) {
  const prefix = `- Version ${version} `;
  const lines = readme.split('\n');
  const starts = lines.map((line, i) => line.startsWith(prefix) ? i : -1).filter(i => i >= 0);
  if (starts.length !== 1) throw new Error();
  const start = starts[0];
  const parts = [lines[start].slice(prefix.length)];
  for (let i = start + 1; i < lines.length && lines[i].startsWith('  '); i += 1) parts.push(lines[i].trim());
  const summary = parts.join(' ');
  if (!summary.trim() || summary.length > 8_000 || /[\u0000-\u001f\u007f]/.test(summary)) throw new Error();
  return summary;
}

export function classifyAndroidReleaseMetadata({repoDir = process.cwd(), baseSha, headSha}) {
  if (!SHA.test(baseSha ?? '') || !SHA.test(headSha ?? '') || baseSha === headSha) return reject('invalid_revision');
  try {
    git(repoDir, ['merge-base', '--is-ancestor', baseSha, headSha]);
  } catch { return reject('base_not_ancestor_or_unavailable'); }

  try {
    const rows = rawChanges(git(repoDir, ['diff', '--raw', '-z', '--no-renames', '--no-abbrev', baseSha, headSha, '--']));
    if (rows.length !== 3) return reject('not_three_release_files');
    const byPath = new Map(rows.map(row => [row.path, row]));
    for (const file of [GRADLE, README]) {
      const row = byPath.get(file);
      if (!row || row.status !== 'M' || row.oldMode !== '100644' || row.newMode !== '100644') return reject('unexpected_paths_or_modes');
    }
    // Read only the two allowlisted metadata files, never arbitrary diff paths or working files.
    const blob = (sha, file) => git(repoDir, ['show', `${sha}:${file}`]);
    let current, next, baseGradle, headGradle;
    try {
      baseGradle = blob(baseSha, GRADLE); headGradle = blob(headSha, GRADLE);
      current = versions(baseGradle); next = versions(headGradle);
      const expected = nextVersion(current);
      if (next.versionName !== expected.versionName || next.versionCode !== expected.versionCode) return reject('not_next_patch_and_code');
    } catch { return reject('invalid_version_declarations'); }
    if (headGradle !== applyGradleVersions(baseGradle, next)) return reject('gradle_changed_beyond_versions');

    const releaseFile = `android/releases/${next.versionName}.json`;
    const release = byPath.get(releaseFile);
    if (!release || release.status !== 'A' || release.oldMode !== '000000' || release.newMode !== '100644') return reject('release_file_not_new_regular_blob');
    try {
      const text = blob(headSha, releaseFile);
      const description = JSON.parse(text);
      if (Object.keys(description).sort().join(',') !== 'channel,releaseNotes' || description.channel !== 'internal') return reject('invalid_release_description');
      const notes = description.releaseNotes;
      if (!Array.isArray(notes) || notes.some(note => typeof note !== 'string' || !note.trim() || note !== note.trim())) return reject('invalid_release_description');
      validateNotes(notes);
      // The producer's canonical bytes also reject duplicate JSON keys and extra hidden fields.
      if (text !== `${JSON.stringify({channel: 'internal', releaseNotes: notes}, null, 2)}\n`) return reject('noncanonical_release_description');
    } catch { return reject('invalid_release_description'); }

    try {
      const baseReadme = blob(baseSha, README), headReadme = blob(headSha, README);
      const expected = applyReadme(baseReadme, {
        current: current.versionName, next: next.versionName, summary: releaseSummary(headReadme, next.versionName),
      });
      if (headReadme !== expected) return reject('readme_changed_beyond_release_entry');
    } catch { return reject('invalid_release_readme'); }
    return {candidate: true, reason: 'producer_exact_release_metadata', fromVersion: current.versionName, toVersion: next.versionName};
  } catch { return reject('comparison_unavailable'); }
}

export function formatObservation(result) {
  // Reasons and version strings are generated internally, never copied from release-note bodies.
  return [
    '### Android 纯升版候选（仅内容检查 / content only）', '',
    `- 候选匹配 / Candidate: **${result.candidate ? '是 / yes' : '否 / no'}**`,
    `- 分类原因 / Reason: \`${result.reason}\``,
    '- 本命令只判断内容；是否省去 PR 检查由可信 BASE 门禁决定，发布仍需单独授权。',
    '- This candidate inspection does not authorize skipping tests or publishing.', '',
  ].join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [baseSha, headSha, format, ...extra] = process.argv.slice(2);
  const result = extra.length || (format && format !== '--json')
    ? reject('invalid_arguments') : classifyAndroidReleaseMetadata({baseSha, headSha});
  console.log(format === '--json' ? JSON.stringify(result) : formatObservation(result));
}
