import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { validateDesktopInternal, DESKTOP_INTERNAL_INDEX } from './releases.mjs';

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

// Executed under sudo on the release host. The fixed root and separate pointer preserve all
// official app/component channels. A concurrent newer publication cannot be rolled back here.
export const remoteTransaction = String.raw`
import base64, fcntl, hashlib, json, os, pathlib, sys, tempfile
action, encoded, run = sys.argv[1:]
data = base64.b64decode(encoded)
index = json.loads(data)
root = pathlib.Path('/srv/hermes-desktop-apps')
target = root / 'internal-index.json'
backup = root / ('.internal-index-previous-' + run)
artifact = root / index['appVersion'] / ('Hermes-Go-Desktop-' + index['appVersion'] + '-dev.dmg')
def replace(content):
    if content is None:
        target.unlink(missing_ok=True)
        return
    fd, name = tempfile.mkstemp(prefix='.internal-index-next-', dir=root)
    try:
        with os.fdopen(fd, 'wb') as out:
            out.write(content); out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), 0o644)
        os.replace(name, target)
    finally:
        pathlib.Path(name).unlink(missing_ok=True)
with (root / '.internal-index.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    if action == 'publish':
        assert artifact.is_file() and not artifact.is_symlink()
        actual = artifact.read_bytes()
        assert len(actual) == index['sizeBytes'] and hashlib.sha256(actual).hexdigest() == index['sha256']
        previous = target.read_bytes() if target.exists() else None
        if previous is not None:
            old = json.loads(previous)
            assert tuple(map(int, index['appVersion'].split('.'))) >= tuple(map(int, old['appVersion'].split('.')))
            if old['appVersion'] == index['appVersion']:
                assert old == index
        with backup.open('xb') as out:
            os.fchmod(out.fileno(), 0o600)
            out.write(json.dumps({'previous': base64.b64encode(previous).decode() if previous is not None else None}).encode())
        replace(data)
    elif action == 'rollback':
        assert target.read_bytes() == data, 'concurrent pointer change; refusing rollback'
        old = json.loads(backup.read_bytes())['previous']
        replace(base64.b64decode(old) if old is not None else None)
        backup.unlink()
    elif action == 'finish':
        assert target.read_bytes() == data
        backup.unlink()
    else:
        raise ValueError('unknown action')
print('INTERNAL_INDEX_' + action.upper() + '_OK')
`;

export async function publishInternalIndex(index, { executeRemote, fetcher = fetch }) {
  await verifyPublicDmg(index, fetcher);
  const data = `${JSON.stringify(index)}\n`;
  await executeRemote('publish', data);
  try {
    const response = await fetcher(DESKTOP_INTERNAL_INDEX, { redirect: 'error', signal: AbortSignal.timeout(10_000), headers: { 'Cache-Control': 'no-cache' } });
    if (!response.ok || await response.text() !== data) throw new Error('pointer readback mismatch');
  } catch (error) {
    await executeRemote('rollback', data);
    throw error;
  }
  await executeRemote('finish', data);
  return index;
}
