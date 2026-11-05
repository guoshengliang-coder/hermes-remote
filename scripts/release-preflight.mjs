#!/usr/bin/env node
// Read-only early rejection, never a substitute for the package or publisher gates.
// Workflows pass presence flags, never credential values, to this step.
import {readFileSync} from 'node:fs';
import {fileURLToPath, pathToFileURL} from 'node:url';
import path from 'node:path';
import {parseGradleVersions, validateNotes} from './bump-android-release.mjs';
import {MAX_INDEX_BYTES, MAX_VERSIONS, validateIndex} from '../release-server/src/schema.mjs';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
export const INPUT_PROFILES = Object.freeze({
  android: ['HERMES_DEBUG_KEYSTORE_BASE64', 'MISSIONGO_ENDPOINT', 'MISSIONGO_SDK_TOKEN',
    'RELEASE_SSH_PRIVATE_KEY', 'RELEASE_SSH_KNOWN_HOSTS'],
  'desktop-app': ['SIGNING_P12_BASE64', 'SIGNING_P12_PASSWORD', 'SIGNING_IDENTITY', 'SIGNING_TEAM_ID',
    'NOTARY_KEY_BASE64', 'NOTARY_KEY_ID', 'NOTARY_ISSUER_ID', 'RELEASE_SIGNING_KEY_ID', 'RELEASE_SIGNING_PUBLIC_KEY'],
  'desktop-components': ['SIGNING_PRIVATE_KEY', 'SIGNING_KEY_ID', 'SIGNING_PUBLIC_KEY'],
});
const REASONS = new Set(['invalid_profile', 'inputs_missing', 'android_metadata_invalid',
  'catalog_unavailable', 'catalog_invalid', 'catalog_too_large', 'catalog_full', 'version_taken', 'version_not_newer']);

export function preflightError(reason, missingInputs = []) {
  return {
    code: 'HR-RELEASE-008',
    summaryZh: '发布前置检查未通过，请检查发布配置、版本和公共索引后重试。',
    summaryEn: 'Release preflight failed. Check the release configuration, version and public index, then retry.',
    retryable: true,
    recoveryAction: 'fix_release_prerequisites_and_retry',
    stage: 'release_preflight',
    technicalCause: REASONS.has(reason) ? reason : 'redacted',
    missingInputs: missingInputs.filter(name => Object.values(INPUT_PROFILES).some(names => names.includes(name))),
  };
}

function refuse(reason, missingInputs) {
  const error = new Error(reason);
  error.releasePreflight = preflightError(reason, missingInputs);
  throw error;
}

export function checkInputs(profile, env) {
  const names = Object.hasOwn(INPUT_PROFILES, profile) ? INPUT_PROFILES[profile] : null;
  if (!names) refuse('invalid_profile');
  // Only the exact string "true" passes; absent flags and accidental raw credentials fail closed.
  const missing = names.filter(name => env[`HAS_${name}`] !== 'true');
  if (missing.length) refuse('inputs_missing', missing);
}

export function checkAndroidCatalog(version, index) {
  try { validateIndex(index); } catch { refuse('catalog_invalid'); }
  if (index.versions.some(entry => entry.versionCode === version.versionCode || entry.versionName === version.versionName)) {
    refuse('version_taken');
  }
  if (version.versionCode <= index.latestVersionCode) refuse('version_not_newer');
  if (index.versions.length >= MAX_VERSIONS) refuse('catalog_full');
}

export async function fetchAndroidCatalog(fetchImpl = fetch) {
  let response;
  try {
    response = await fetchImpl('https://mrlgs.net/releases/index.json', {
      signal: AbortSignal.timeout(15_000), redirect: 'error', credentials: 'omit', cache: 'no-store',
    });
    if (!response.ok || !response.body) refuse('catalog_unavailable');
    const reader = response.body.getReader();
    const chunks = [];
    let bytes = 0;
    try {
      for (;;) {
        const {done, value} = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        if (bytes > MAX_INDEX_BYTES) refuse('catalog_too_large');
        chunks.push(Buffer.from(value));
      }
    } finally {
      // Cancellation is best-effort and must not replace the safe preflight error.
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { refuse('catalog_invalid'); }
  } catch (error) {
    if (error.releasePreflight) throw error;
    refuse('catalog_unavailable');
  }
}

export async function runPreflight(profile, {env = process.env, root = ROOT, fetchImpl = fetch} = {}) {
  checkInputs(profile, env);
  if (profile !== 'android') return;
  let version;
  try {
    version = parseGradleVersions(readFileSync(path.join(root, 'android/app/build.gradle.kts'), 'utf8'));
    if (!Number.isSafeInteger(version.versionCode) || version.versionCode < 1) throw new Error();
    const description = JSON.parse(readFileSync(path.join(root, `android/releases/${version.versionName}.json`), 'utf8'));
    if (Object.keys(description).sort().join(',') !== 'channel,releaseNotes' || description.channel !== 'internal') throw new Error();
    if (!Array.isArray(description.releaseNotes) || description.releaseNotes.some(note => typeof note !== 'string' || !note.trim())) throw new Error();
    validateNotes(description.releaseNotes);
  } catch { refuse('android_metadata_invalid'); }
  checkAndroidCatalog(version, await fetchAndroidCatalog(fetchImpl));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  (async () => {
    if (args.length !== 1) refuse('invalid_profile');
    await runPreflight(args[0]);
    console.log(`RELEASE_PREFLIGHT_OK profile=${args[0]}`);
  })().catch(error => {
    const failure = error.releasePreflight ?? preflightError('redacted');
    console.error(`${failure.code}: ${failure.summaryZh}\n${failure.summaryEn}`);
    console.error(JSON.stringify(failure));
    process.exitCode = 1;
  });
}
