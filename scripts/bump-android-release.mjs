#!/usr/bin/env node
// Allocates the next Android version: the three files a release commit has always touched by hand
// (docs/APP_UPDATE.md "Publishing" step 1), plus the collision checks that hand-editing kept
// getting wrong. 0.1.97 was allocated twice and published never; 0.1.95 was taken by another
// agent's release between one agent reading the version and writing it back.
//
// This is the version gate only. It does not commit, tag, build, or publish — those stay separate
// decisions, and the release gate (scripts/package-debug-apk.sh) still has to pass afterwards.
import {execFileSync} from 'node:child_process';
import {existsSync, readdirSync, readFileSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

// The note limits belong to the manifest schema the release server enforces; re-declaring them
// here is how the two drift apart and a release fails at upload instead of at allocation.
import {MAX_RELEASE_NOTES, MAX_RELEASE_NOTE_LENGTH} from '../release-server/src/schema.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const GRADLE_FILE = path.join(ROOT, 'android', 'app', 'build.gradle.kts');
const README_FILE = path.join(ROOT, 'android', 'README.md');
const RELEASES_DIR = path.join(ROOT, 'android', 'releases');
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

export function parseGradleVersions(text) {
  const name = /^val\s+appVersionName\s*=\s*"([^"]+)"\s*$/m.exec(text);
  const code = /^val\s+appVersionCode\s*=\s*(\d+)\s*$/m.exec(text);
  if (!name || !code) throw new Error('android/app/build.gradle.kts: appVersionName/appVersionCode not found');
  if (!SEMVER.test(name[1])) throw new Error(`appVersionName is not semver: ${name[1]}`);
  return {versionName: name[1], versionCode: Number(code[1])};
}

export function nextVersion({versionName, versionCode}) {
  const [major, minor, patch] = versionName.split('.').map(Number);
  return {versionName: `${major}.${minor}.${patch + 1}`, versionCode: versionCode + 1};
}

export function applyGradleVersions(text, next) {
  const withCode = text.replace(/^val\s+appVersionCode\s*=\s*\d+\s*$/m, `val appVersionCode = ${next.versionCode}`);
  const withName = withCode.replace(/^val\s+appVersionName\s*=\s*"[^"]+"\s*$/m, `val appVersionName = "${next.versionName}"`);
  const parsed = parseGradleVersions(withName);
  if (parsed.versionName !== next.versionName || parsed.versionCode !== next.versionCode) {
    throw new Error('failed to rewrite the version truth source');
  }
  return withName;
}

// A version string is not attacker-controlled here, but building a regexp out of one still means
// every caller has to reason about escaping. Both lookups are plain substring work, so they are
// written as plain substring work.
function lineStartIndex(text, prefix) {
  if (text.startsWith(prefix)) return 0;
  const index = text.indexOf(`\n${prefix}`);
  return index === -1 ? -1 : index + 1;
}

// The bullet list is not sorted, so the only stable anchor is the entry for the version being
// superseded: the new one goes immediately above it, exactly where a human has been putting it.
export function applyReadme(text, {current, next, summary}) {
  const anchorIndex = lineStartIndex(text, `- Version ${current} `);
  if (anchorIndex === -1) throw new Error(`android/README.md has no "- Version ${current}" entry to anchor against`);
  const bullet = `${wrapBullet(`- Version ${next} ${summary.trim()}`)}\n`;
  let out = text.slice(0, anchorIndex) + bullet + text.slice(anchorIndex);

  const currentApk = `Hermes-Remote-${current}-debug.apk`;
  if (!out.includes(currentApk)) throw new Error(`android/README.md has no ${currentApk} reference`);
  out = out.split(currentApk).join(`Hermes-Remote-${next}-debug.apk`);

  // scripts/package-debug-apk.sh re-checks both of these before it will build. Failing here costs
  // nothing; failing there costs a full test-and-assemble cycle first.
  if (!out.includes(`Version ${next}`) || !out.includes(`Hermes-Remote-${next}-debug.apk`)) {
    throw new Error('the rewritten README would not satisfy scripts/package-debug-apk.sh');
  }
  return out;
}

function wrapBullet(line, width = 100) {
  const words = line.split(/\s+/);
  const lines = [];
  let current = words.shift();
  for (const word of words) {
    if (`${current} ${word}`.length > width) {
      lines.push(current);
      current = `  ${word}`;
    } else {
      current = `${current} ${word}`;
    }
  }
  lines.push(current);
  return lines.join('\n');
}

// A .json file is taken as the notes array verbatim; anything else is split on blank lines, which
// is how these notes are actually drafted.
export function normalizeNotes(raw, {json}) {
  const notes = json
    ? JSON.parse(raw)
    : raw.split(/\n\s*\n/).map(paragraph => paragraph.split('\n').map(line => line.trim()).join(' ').trim());
  if (!Array.isArray(notes)) throw new Error('release notes must be an array of strings');
  return notes.filter(note => typeof note === 'string' && note.trim()).map(note => note.trim());
}

export function validateNotes(notes) {
  if (!notes.length) throw new Error('release notes are empty');
  if (notes.length > MAX_RELEASE_NOTES) throw new Error(`at most ${MAX_RELEASE_NOTES} notes, got ${notes.length}`);
  for (const note of notes) {
    if (note.length > MAX_RELEASE_NOTE_LENGTH) {
      throw new Error(`a note exceeds ${MAX_RELEASE_NOTE_LENGTH} characters (${note.length}): ${note.slice(0, 60)}`);
    }
    if (CONTROL_CHARACTERS.test(note)) throw new Error(`a note contains control characters: ${note.slice(0, 60)}`);
  }
  return notes;
}

export function allocatedVersions({releaseFiles, tags}) {
  const fromFiles = releaseFiles.filter(name => name.endsWith('.json')).map(name => name.slice(0, -'.json'.length));
  const fromTags = tags.map(tag => tag.replace(/^android-v/, ''));
  return new Set([...fromFiles, ...fromTags].filter(value => SEMVER.test(value)));
}

const git = (...args) => execFileSync('git', args, {cwd: ROOT, encoding: 'utf8'}).trim();

function assertReleasableCheckout() {
  // docs/APP_UPDATE.md: "A version number allocated on a feature branch is a version number that
  // can be lost." Allocating from anything but the current origin/main is how numbers collide.
  if (git('status', '--porcelain')) throw new Error('working tree is not clean');
  git('fetch', '--quiet', 'origin', 'main');
  if (git('rev-parse', 'HEAD') !== git('rev-parse', 'origin/main')) {
    throw new Error('HEAD is not origin/main — start from the current main before allocating a version');
  }
}

function remoteReleaseTags() {
  return git('ls-remote', '--tags', '--refs', 'origin', 'android-v*')
    .split('\n')
    .filter(Boolean)
    .map(line => line.split('\t')[1].replace('refs/tags/', ''));
}

function parseArgs(argv) {
  const options = {dryRun: false};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--notes-file') options.notesFile = argv[++i];
    else if (arg === '--summary') options.summary = argv[++i];
    else if (arg === '--summary-file') options.summaryFile = argv[++i];
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!options.notesFile) {
    throw new Error('--notes-file <path> is required (a JSON array, or paragraphs separated by blank lines)');
  }
  if (!options.summary && !options.summaryFile) {
    throw new Error('--summary <text> or --summary-file <path> is required (the android/README.md entry)');
  }
  return options;
}

function main(argv) {
  const options = parseArgs(argv);
  assertReleasableCheckout();

  const gradleText = readFileSync(GRADLE_FILE, 'utf8');
  const current = parseGradleVersions(gradleText);
  const next = nextVersion(current);

  const taken = allocatedVersions({releaseFiles: readdirSync(RELEASES_DIR), tags: remoteReleaseTags()});
  if (taken.has(next.versionName)) {
    throw new Error(
      `${next.versionName} is already allocated — a release file or an origin tag exists for it. ` +
      'Another agent released between this checkout and now; fetch main again and re-run.'
    );
  }

  const notes = validateNotes(
    normalizeNotes(readFileSync(options.notesFile, 'utf8'), {json: options.notesFile.endsWith('.json')})
  );
  const summary = options.summary ?? readFileSync(options.summaryFile, 'utf8');
  const releaseFile = path.join(RELEASES_DIR, `${next.versionName}.json`);
  if (existsSync(releaseFile)) throw new Error(`${releaseFile} already exists`);

  const writes = [
    [GRADLE_FILE, applyGradleVersions(gradleText, next)],
    [README_FILE, applyReadme(readFileSync(README_FILE, 'utf8'), {
      current: current.versionName,
      next: next.versionName,
      summary,
    })],
    [releaseFile, `${JSON.stringify({channel: 'internal', releaseNotes: notes}, null, 2)}\n`],
  ];

  process.stdout.write(`${current.versionName} (code ${current.versionCode}) -> ${next.versionName} (code ${next.versionCode})\n`);
  for (const [file] of writes) {
    process.stdout.write(`  ${options.dryRun ? 'would write' : 'write'} ${path.relative(ROOT, file)}\n`);
  }
  if (options.dryRun) return;
  for (const [file, contents] of writes) writeFileSync(file, contents);

  process.stdout.write(
    '\nNext, in order:\n' +
    '  ./scripts/package-debug-apk.sh          # release gate; must print APK_RELEASE_OK\n' +
    `  git switch -c release/${next.versionName}\n` +
    `  git commit -am "build(android): release ${next.versionName}"\n` +
    '  # open the PR, let the required checks finish, merge, then push the tag:\n' +
    `  git tag android-v${next.versionName} && git push origin android-v${next.versionName}\n`
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`bump-android-release: ${error.message}\n`);
    process.exitCode = 1;
  }
}
