import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { validateDesktopInternal, validateDesktop, DESKTOP_INTERNAL_INDEX } from './releases.mjs';

export const DESKTOP_APP_INDEX = 'https://mrlgs.net/desktop/apps/index.json';

// Existing Desktop builds accept exactly the legacy schema and non-dev filename. The URL below
// is an exact alias to the same verified ad-hoc bytes, not a new or notarized artifact.
export function createAppUpdateIndex(index) {
  validateDesktopInternal(index);
  const appIndex = Object.fromEntries(['schemaVersion', 'channel', 'architecture', 'appVersion',
    'buildNumber', 'minimumMacOS', 'downloadURL', 'sizeBytes', 'sha256', 'releaseNotes',
    'sourceCommit', 'updatedAt'].map(key => [key, index[key]]));
  return validateDesktop({ ...appIndex, downloadURL: index.downloadURL.replace('-dev.dmg', '.dmg') });
}

export async function createInternalIndex({ receipt, dmg, notes, architecture = 'arm64', minimumMacOS = '14.0' }) {
  // Only register a completed, owner-authorized publication, never a local candidate.
  if (receipt.verified !== true || receipt.notarized !== false || receipt.channel !== 'internal-ad-hoc'
      || receipt.stage !== 'internal-test-published-and-public-readback-verified') throw new Error('unverified publication');
  const data = await readFile(dmg);
  const sha256 = createHash('sha256').update(data).digest('hex');
  if (basename(dmg) !== `Hermes-Go-Desktop-${receipt.version}-dev.dmg`
      || data.length !== receipt.sizeBytes || sha256 !== receipt.sha256) throw new Error('publication byte mismatch');
  return validateDesktopInternal({ schemaVersion: 1, channel: 'internal', distribution: 'ad-hoc', notarized: false,
    architecture, appVersion: receipt.version, buildNumber: receipt.build, minimumMacOS,
    downloadURL: receipt.url, sizeBytes: data.length, sha256, releaseNotes: notes,
    sourceCommit: receipt.sourceCommit, updatedAt: new Date(receipt.verifiedAt).toISOString().replace(/\.\d+Z$/, 'Z') });
}

export async function verifyPublicDmg(index, fetcher = fetch) {
  validateDesktopInternal(index);
  await verifyPublicArtifact(index, fetcher);
}

async function verifyPublicArtifact(index, fetcher) {
  const response = await fetcher(index.downloadURL, { redirect: 'error', signal: AbortSignal.timeout(60_000) });
  if (!response.ok || !response.body) { await response.body?.cancel(); throw new Error('DMG unavailable'); }
  const hash = createHash('sha256'); let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    if (size > index.sizeBytes) throw new Error('DMG too large');
    hash.update(chunk);
  }
  if (size !== index.sizeBytes || hash.digest('hex') !== index.sha256) throw new Error('DMG byte mismatch');
}

// Executed under sudo on the release host. Both pointers share one lock and rollback record.
// Component manifests and official signing/notarization workflows are independent.
export const remoteTransaction = String.raw`
import base64, fcntl, hashlib, json, os, pathlib, sys, tempfile
action, encoded, run = sys.argv[1:]
data = base64.b64decode(encoded)
message = json.loads(data)
index = message.get('internalIndex', message)
app_index = message.get('appUpdateIndex')
root = pathlib.Path('/srv/hermes-desktop-apps')
backup = root / ('.internal-index-previous-' + run)
serialize = lambda value: (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
contents = {'internal-index.json': serialize(index)} if app_index else {'internal-index.json': data}
if app_index:
    expected = {key: index[key] for key in ('schemaVersion', 'channel', 'architecture', 'appVersion', 'buildNumber', 'minimumMacOS', 'downloadURL', 'sizeBytes', 'sha256', 'releaseNotes', 'sourceCommit', 'updatedAt')}
    expected['downloadURL'] = expected['downloadURL'].replace('-dev.dmg', '.dmg')
    assert app_index == expected and app_index['channel'] == 'internal'
    contents['index.json'] = serialize(app_index)
artifact = root / index['appVersion'] / ('Hermes-Go-Desktop-' + index['appVersion'] + '-dev.dmg')
def replace(name, content):
    target = root / name
    assert not target.is_symlink()
    if content is None:
        target.unlink(missing_ok=True)
        return
    fd, temporary = tempfile.mkstemp(prefix='.internal-index-next-', dir=root)
    try:
        with os.fdopen(fd, 'wb') as out:
            out.write(content); out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), 0o644)
        os.replace(temporary, target)
    finally:
        pathlib.Path(temporary).unlink(missing_ok=True)
def read_current(name):
    target = root / name
    assert not target.is_symlink()
    return target.read_bytes() if target.exists() else None
with (root / '.internal-index.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    if action == 'publish':
        assert artifact.is_file() and not artifact.is_symlink()
        actual = artifact.read_bytes()
        assert len(actual) == index['sizeBytes'] and hashlib.sha256(actual).hexdigest() == index['sha256']
        previous = {name: read_current(name) for name in contents}
        # Validate every pointer before changing any; never replace another channel or downgrade.
        for name, content in contents.items():
            if previous[name] is not None:
                old, new = json.loads(previous[name]), json.loads(content)
                assert old['channel'] == new['channel'] and old['architecture'] == new['architecture']
                assert tuple(map(int, new['appVersion'].split('.'))) >= tuple(map(int, old['appVersion'].split('.')))
                assert new['buildNumber'] >= old['buildNumber']
                if old['appVersion'] == new['appVersion']:
                    assert old == new
        with backup.open('xb') as out:
            os.fchmod(out.fileno(), 0o600)
            out.write(json.dumps({name: base64.b64encode(value).decode() if value is not None else None for name, value in previous.items()}).encode())
            out.flush(); os.fsync(out.fileno())
        try:
            for name, content in contents.items(): replace(name, content)
        except:
            for name, content in previous.items(): replace(name, content)
            backup.unlink()
            raise
    elif action in ('rollback', 'finish'):
        assert all(read_current(name) == content for name, content in contents.items()), 'concurrent pointer change; refusing rollback/finish'
        if action == 'rollback':
            previous = json.loads(backup.read_bytes())
            assert set(previous) == set(contents)
            for name, content in previous.items(): replace(name, base64.b64decode(content) if content is not None else None)
        backup.unlink()
    else:
        raise ValueError('unknown action')
print('INTERNAL_INDEX_' + action.upper() + '_OK')
`;

export async function publishInternalIndex(index, { executeRemote, fetcher = fetch, syncAppUpdate = false }) {
  await verifyPublicDmg(index, fetcher);
  const appUpdateIndex = syncAppUpdate ? createAppUpdateIndex(index) : null;
  if (appUpdateIndex) await verifyPublicArtifact(appUpdateIndex, fetcher);
  const data = `${JSON.stringify(appUpdateIndex ? { internalIndex: index, appUpdateIndex } : index)}\n`;
  await executeRemote('publish', data);
  try {
    for (const [url, value] of [[DESKTOP_INTERNAL_INDEX, index], ...(appUpdateIndex ? [[DESKTOP_APP_INDEX, appUpdateIndex]] : [])]) {
      const response = await fetcher(url, { redirect: 'error', signal: AbortSignal.timeout(10_000), headers: { 'Cache-Control': 'no-cache' } });
      if (!response.ok || await response.text() !== `${JSON.stringify(value)}\n`) throw new Error('pointer readback mismatch');
    }
  } catch (error) {
    await executeRemote('rollback', data);
    throw error;
  }
  await executeRemote('finish', data);
  return index;
}
