#!/usr/bin/env node
// An Android release as two calls with the publish gate between them (docs/APP_UPDATE.md
// "Publishing", docs/INTEGRATION.md table 3):
//
//   prepare  bump-android-release.mjs → development checks → release PR → merge-when-green.mjs
//   publish  check origin/main carries the version → push android-v<version> → wait for
//            android-release.yml, which builds, uploads and verifies the public APK and index
//
// Each step already existed; agents chained them by hand, a dozen tool calls and several polling
// loops per release. Nothing here replaces a gate: bump still refuses a stale or dirty tree and a
// taken number, CI's package gate still has to print APK_RELEASE_OK, the release PR goes through the
// merge gate as a red-light change, and `publish` is a separate command the owner authorizes.
// `publish` is also the only publishing path: pushing the tag after a manual upload of the same
// version is how android-v0.1.139 failed with "version conflict".
import {execFileSync, spawnSync} from 'node:child_process';
import {existsSync, readFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

import {parseGradleVersions} from './bump-android-release.mjs';
import {classifyRuns, waitFor} from './merge-when-green.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const GRADLE_PATH = 'android/app/build.gradle.kts';
const PREPARATION_ERROR = JSON.parse(readFileSync(new URL('./lib/android-release-errors.json', import.meta.url), 'utf8')).preparation;
export const PREPARATION_TASKS = Object.freeze([':app:testDebugUnitTest', ':app:lintDebug']);

const USAGE = `usage:
  node scripts/android-release-train.mjs prepare --notes-file <path> (--summary <text> | --summary-file <path>) [--no-merge]
  node scripts/android-release-train.mjs publish <version>

prepare runs development checks without release credentials, prepares a CI-built release,
and stops after the release PR is merged. It does not produce or approve an APK.
publish is the publish gate: run it only when the project owner has authorized publishing.
`;

export function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (command === 'prepare') {
    const options = {command, merge: true};
    for (let i = 0; i < rest.length; i += 1) {
      const arg = rest[i];
      if (arg === '--no-merge') options.merge = false;
      else if (arg === '--notes-file') options.notesFile = rest[++i];
      else if (arg === '--summary') options.summary = rest[++i];
      else if (arg === '--summary-file') options.summaryFile = rest[++i];
      else throw new Error(`unknown argument: ${arg}`);
    }
    if (!options.notesFile) throw new Error('prepare needs --notes-file <path>');
    if (!options.summary && !options.summaryFile) throw new Error('prepare needs --summary <text> or --summary-file <path>');
    return options;
  }
  if (command === 'publish') {
    if (rest.length !== 1 || !/^\d+\.\d+\.\d+$/.test(rest[0])) throw new Error('publish needs exactly one <version>, e.g. 0.1.141');
    return {command, version: rest[0]};
  }
  throw new Error(USAGE);
}

// What must hold on origin/main before its commit may be tagged for publication.
export function publishRefusal({version, gradleText, releaseFileExists, tagExists}) {
  const {versionName} = parseGradleVersions(gradleText);
  if (versionName !== version) return `origin/main is at ${versionName}, not ${version} — merge the release PR first`;
  if (!releaseFileExists) return `origin/main has no android/releases/${version}.json`;
  if (tagExists) return `android-v${version} already exists on origin — this version was already published or attempted; allocate a new one`;
  return null;
}

export function releasePrBody({version}) {
  return [
    `Allocates Android ${version} for a test package, via \`scripts/android-release-train.mjs prepare\`.`,
    '',
    'Credential-free development checks passed: Android unit tests and lint (including source compilation).',
    'No local APK was produced or approved. The android-release workflow must run the full package gate with repository secrets before publishing.',
    '',
    'Red-light change (version truth source): merged by the integration agent with `merge-when-green.mjs --allow-red`.',
    'Not published by this PR. Publishing is `scripts/android-release-train.mjs publish` after the owner authorizes it.',
    '',
    '🤖 Generated with [Claude Code](https://claude.com/claude-code)',
  ].join('\n');
}

function sh(command, args, options = {}) {
  return execFileSync(command, args, {cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024, ...options}).trim();
}

// Runs with inherited stdout/stderr so a long step shows progress; returns the exit status.
function stream(command, args, cwd = ROOT) {
  return spawnSync(command, args, {cwd, stdio: 'inherit'}).status;
}

function fail(code, message) {
  console.log(message);
  process.exitCode = code;
}

function failPreparation(code, reason) {
  const error = {...PREPARATION_ERROR, technicalCause: reason};
  console.error(`${error.code}: ${error.summaryZh}\n${error.summaryEn}`);
  console.error(JSON.stringify(error));
  process.exitCode = code;
}

function prepare(options) {
  sh('git', ['fetch', '--quiet', 'origin']);
  if (sh('git', ['status', '--porcelain'])) return failPreparation(4, 'worktree_not_clean');
  if (sh('git', ['rev-parse', 'HEAD']) !== sh('git', ['rev-parse', 'origin/main'])) return failPreparation(4, 'head_not_origin_main');

  const bumpArgs = ['scripts/bump-android-release.mjs', '--notes-file', options.notesFile];
  if (options.summary) bumpArgs.push('--summary', options.summary);
  else bumpArgs.push('--summary-file', options.summaryFile);
  if (stream('node', bumpArgs) !== 0) return failPreparation(1, 'version_allocation_failed');
  const {versionName: version} = parseGradleVersions(readFileSync(path.join(ROOT, GRADLE_PATH), 'utf8'));

  console.log(`\n== development checks for CI release ${version} ==`);
  if (stream('./gradlew', [...PREPARATION_TASKS, '--console=plain'], path.join(ROOT, 'android')) !== 0) {
    console.log(`${version} remains uncommitted; fix the checks and retry from a fresh worktree at origin/main.`);
    return failPreparation(1, 'development_checks_failed');
  }

  const branch = `release/android-${version}`;
  sh('git', ['switch', '-c', branch]);
  sh('git', ['add', GRADLE_PATH, 'android/README.md', `android/releases/${version}.json`]);
  sh('git', ['commit', '-m', `build(android): release ${version}`, '-m', 'Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>']);
  sh('git', ['push', '--quiet', '-u', 'origin', branch]);
  const url = sh('gh', ['pr', 'create', '--base', 'main', '--head', branch, '--title', `build(android): release ${version}`, '--body', releasePrBody({version})]);
  const pr = url.split('/').pop();
  console.log(`opened ${url}`);

  if (!options.merge) return fail(0, `PREPARED ${version}: PR #${pr} open, not merged (--no-merge). No APK built; official packaging runs in CI.`);
  const merged = stream('node', ['scripts/merge-when-green.mjs', pr, '--allow-red']);
  if (merged !== 0) return fail(merged, `MERGE_NOT_DONE ${version}: see merge-when-green output above (exit ${merged})`);
  return fail(0, [
    `PREPARED ${version}: release PR #${pr} merged, main green.`,
    'No APK built or approved. The CI release workflow runs packaging and public verification.',
    `Publish gate — only with the owner's authorization: node scripts/android-release-train.mjs publish ${version}`,
  ].join('\n'));
}

async function publish({version}) {
  const tag = `android-v${version}`;
  sh('git', ['fetch', '--quiet', 'origin', 'main']);
  const commit = sh('git', ['rev-parse', 'origin/main']);
  const refusal = publishRefusal({
    version,
    gradleText: sh('git', ['show', `origin/main:${GRADLE_PATH}`]),
    releaseFileExists: spawnSync('git', ['cat-file', '-e', `origin/main:android/releases/${version}.json`], {cwd: ROOT}).status === 0,
    tagExists: sh('git', ['ls-remote', '--tags', 'origin', `refs/tags/${tag}`]) !== '',
  });
  if (refusal) return fail(4, `REFUSED: ${refusal}`);

  sh('git', ['tag', tag, commit]);
  sh('git', ['push', '--quiet', 'origin', `refs/tags/${tag}`]);
  console.log(`pushed ${tag} at ${commit.slice(0, 8)}; waiting for android-release.yml`);

  const result = await waitFor({
    probe: () => JSON.parse(sh('gh', ['run', 'list', '--workflow', 'android-release.yml', '--branch', tag, '--json', 'name,event,status,conclusion'])),
    classify: classifyRuns,
    interval: 20_000,
    timeout: 30 * 60_000,
    grace: 3 * 60_000,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: () => Date.now(),
    log: (line) => console.log(line),
  });
  const runUrl = sh('gh', ['run', 'list', '--workflow', 'android-release.yml', '--branch', tag, '--limit', '1', '--json', 'url', '--jq', '.[0].url']);
  if (result.state === 'success') return fail(0, `PUBLISHED ${version}: ${runUrl}`);
  if (result.state === 'timeout') return fail(3, `TIMEOUT ${version}: release run still pending: ${runUrl}`);
  return fail(1, `PUBLISH_FAILED ${version}: ${result.failed?.join('; ') || 'no release run appeared'} ${runUrl}`);
}

async function main(argv) {
  const options = parseArgs(argv);
  if (!existsSync(path.join(ROOT, GRADLE_PATH))) throw new Error(`${GRADLE_PATH} not found`);
  if (options.command === 'prepare') return prepare(options);
  return publish(options);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    if (process.argv[2] === 'prepare') return failPreparation(4, 'preparation_command_failed');
    console.error(`android-release-train: ${error.stderr?.toString().trim() || error.message}`);
    process.exitCode = 4;
  });
}
