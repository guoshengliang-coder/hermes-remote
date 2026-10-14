import { validateIndex, MAX_INDEX_BYTES } from '../../release-server/src/schema.mjs';

export const RELEASE_ORIGIN = 'https://mrlgs.net';
const indexes = { android: `${RELEASE_ORIGIN}/releases/index.json`, desktop: `${RELEASE_ORIGIN}/desktop/apps/index.json` };
export const DESKTOP_INTERNAL_INDEX = `${RELEASE_ORIGIN}/desktop/apps/internal-index.json`;
const androidNames = { 26: '8.0', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' };
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const iso = value => typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(value) && new Date(value).toISOString().replace('.000Z', 'Z') === value;

export function validateDesktop(value) {
  if (value?.schemaVersion !== 1 || value.channel !== 'internal' || !['arm64', 'x86_64', 'universal'].includes(value.architecture)
      || !semver.test(value.appVersion) || !Number.isSafeInteger(value.buildNumber) || value.buildNumber < 1
      || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes < 1 || !/^[0-9a-f]{64}$/.test(value.sha256)
      || !/^[0-9a-f]{40}$/.test(value.sourceCommit) || !/^\d+\.\d+(?:\.\d+)?$/.test(value.minimumMacOS)
      || !iso(value.updatedAt) || !Array.isArray(value.releaseNotes) || value.releaseNotes.length > 20
      || value.releaseNotes.some(note => typeof note !== 'string' || !note.trim() || Buffer.byteLength(note) > 500 || /[\x00-\x1f\x7f]/.test(note))) throw new Error('invalid desktop index');
  const expected = `${RELEASE_ORIGIN}/desktop/apps/${value.appVersion}/Hermes-Go-Desktop-${value.appVersion}.dmg`;
  if (value.downloadURL !== expected) throw new Error('invalid desktop download');
  return value;
}

// The website-only test pointer cannot be consumed by the notarized app-update channel.
export function validateDesktopInternal(value) {
  if (value?.distribution !== 'ad-hoc' || value.notarized !== false
      || value.downloadURL !== `${RELEASE_ORIGIN}/desktop/apps/${value.appVersion}/Hermes-Go-Desktop-${value.appVersion}-dev.dmg`) throw new Error('invalid internal desktop download');
  validateDesktop({ ...value, downloadURL: `${RELEASE_ORIGIN}/desktop/apps/${value.appVersion}/Hermes-Go-Desktop-${value.appVersion}.dmg` });
  return value;
}

async function readIndex(url, fetcher, timeoutMs) {
  const response = await fetcher(url, { signal: AbortSignal.timeout(timeoutMs), redirect: 'error', headers: { Accept: 'application/json' } });
  if (response.status === 404) { await response.body?.cancel(); return null; }
  if (!response.ok || !response.body) { await response.body?.cancel(); throw new Error('release response'); }
  const reader = response.body.getReader();
  const chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_INDEX_BYTES) throw new Error('release too large');
      chunks.push(Buffer.from(value));
    }
    const index = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!index || typeof index !== 'object' || Array.isArray(index)) throw new Error('invalid release object');
    return index;
  } finally { await reader.cancel().catch(() => {}); }
}

export function createReleaseReader({ fetcher = fetch, now = Date.now, ttlMs = 60_000, timeoutMs = 3500 } = {}) {
  const cache = new Map(), pending = new Map();
  async function load(platform, fresh = false) {
    if (!Object.hasOwn(indexes, platform)) return { status: 'unavailable' };
    const cached = cache.get(platform);
    if (!fresh && cached && cached.until > now()) return cached.value;
    if (pending.has(platform)) return pending.get(platform);
    const operation = (async () => {
      let value;
      try {
        const internal = platform === 'desktop' ? await readIndex(DESKTOP_INTERNAL_INDEX, fetcher, timeoutMs) : null;
        const index = internal ?? await readIndex(indexes[platform], fetcher, timeoutMs);
        if (!index) value = { status: 'unpublished' };
        else if (platform === 'android') {
          const release = validateIndex(index).versions[0];
          value = release ? { status: 'available', version: release.versionName, sizeBytes: release.sizeBytes, date: release.publishedAt,
            requirement: androidNames[release.minSdk] ? `Android ${androidNames[release.minSdk]}+` : `Android（API ${release.minSdk}+）`, notes: release.releaseNotes, sha256: release.sha256, url: release.downloadUrl } : { status: 'unpublished' };
        } else {
          const release = internal ? validateDesktopInternal(index) : validateDesktop(index);
          value = { status: 'available', version: release.appVersion, sizeBytes: release.sizeBytes, date: release.updatedAt,
            requirement: `macOS ${release.minimumMacOS}+ · ${{ arm64: 'Apple Silicon（M 系列）', x86_64: 'Intel Mac', universal: 'Apple Silicon / Intel Mac' }[release.architecture]}`, notes: release.releaseNotes, sha256: release.sha256, url: release.downloadURL,
            ...(internal ? { distribution: 'ad-hoc', notarized: false } : {}) };
        }
      } catch { value = { status: 'unavailable' }; }
      cache.set(platform, { value, until: now() + ttlMs });
      return value;
    })();
    pending.set(platform, operation);
    try { return await operation; } finally { pending.delete(platform); }
  }
  return { load, async publicSummary() {
    const [android, desktop] = await Promise.all([load('android'), load('desktop')]);
    return Object.fromEntries(Object.entries({ android, desktop }).map(([key, { url: _privateURL, ...metadata }]) => [key, metadata]));
  } };
}
