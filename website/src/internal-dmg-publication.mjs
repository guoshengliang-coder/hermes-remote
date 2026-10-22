import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { validateDesktopInternal } from './releases.mjs';
import { publishInternalIndex } from './internal-publication.mjs';

export async function prepareInternalDmg({ receipt, dmg, notes, architecture = 'arm64', minimumMacOS = '14.0' }) {
  if (receipt.verified !== true || receipt.notarized !== false || receipt.channel !== 'internal-ad-hoc'
      || !['internal-test-package-verified', 'internal-test-published-and-public-readback-verified'].includes(receipt.stage)) throw new Error('unverified package');
  if (receipt.stage === 'internal-test-package-verified' && (receipt.checks?.assets !== 'passed'
      || !Number.isSafeInteger(receipt.checks.desktopTests) || receipt.checks.desktopTests < 1
      || receipt.checks.failures !== 0 || receipt.checks.appBuild !== 'passed'
      || receipt.checks.imageIntegrity !== 'passed')) throw new Error('package gate incomplete');
  const bytes = await readFile(dmg);
  if (basename(dmg) !== `Hermes-Go-Desktop-${receipt.version}-dev.dmg` || bytes.length !== receipt.sizeBytes
      || createHash('sha256').update(bytes).digest('hex') !== receipt.sha256) throw new Error('package byte mismatch');
  const downloadURL = `https://mrlgs.net/desktop/apps/${receipt.version}/Hermes-Go-Desktop-${receipt.version}-dev.dmg`;
  if (receipt.url !== undefined && receipt.url !== downloadURL) throw new Error('receipt URL mismatch');
  return validateDesktopInternal({ schemaVersion: 1, channel: 'internal', distribution: 'ad-hoc', notarized: false,
    architecture, appVersion: receipt.version, buildNumber: receipt.build, minimumMacOS,
    downloadURL,
    sizeBytes: receipt.sizeBytes, sha256: receipt.sha256, releaseNotes: notes, sourceCommit: receipt.sourceCommit,
    updatedAt: new Date(receipt.verifiedAt).toISOString().replace(/\.\d+Z$/, 'Z') });
}

// Publication success includes both website and existing Desktop update discovery.
export async function publishInternalDmg(index, { installArtifact, executeRemote, fetcher = fetch }) {
  validateDesktopInternal(index);
  await installArtifact(index);
  // Includes full public DMG readback, atomic index switch and public index readback/rollback.
  await publishInternalIndex(index, { executeRemote, fetcher, syncAppUpdate: true });
  return { verified: true, stage: 'internal-test-published-and-public-readback-verified',
    channel: 'internal-ad-hoc', notarized: false, version: index.appVersion, build: index.buildNumber,
    sourceCommit: index.sourceCommit, url: index.downloadURL, sizeBytes: index.sizeBytes,
    sha256: index.sha256, verifiedAt: index.updatedAt, websiteSynchronized: true,
    appUpdateSynchronized: true, appUpdateIndexURL: 'https://mrlgs.net/desktop/apps/index.json',
    appUpdateVerifiedAt: new Date().toISOString() };
}

export const remoteArtifactInstall = String.raw`
import base64, fcntl, hashlib, json, os, pathlib, re, subprocess, sys, tempfile
stage = pathlib.Path(sys.argv[1])
index = json.loads(base64.b64decode(sys.argv[2]))
root = pathlib.Path('/srv/hermes-desktop-apps')
routes = pathlib.Path('/etc/hermes-go/desktop-release-routes.conf')
version = index['appVersion']
assert re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version)
filename = 'Hermes-Go-Desktop-' + version + '-dev.dmg'
source = stage / filename
payload = source.read_bytes()
assert len(payload) == index['sizeBytes'] and hashlib.sha256(payload).hexdigest() == index['sha256']
destination = root / version / filename
def artifact_route(public_path):
    return '\nlocation = ' + public_path + ' {\n    alias ' + str(destination) + ';\n    default_type application/x-apple-diskimage;\n    add_header Cache-Control "public, max-age=31536000, immutable" always;\n    add_header X-Content-Type-Options nosniff always;\n    limit_except GET HEAD { deny all; }\n}\n'
# The no-dev URL is a compatibility alias for installed Desktop clients. Both routes serve the
# exact same immutable file, while the website retains explicit ad-hoc/notarized-false metadata.
public_path = '/desktop/apps/' + version + '/' + filename
legacy_path = public_path.replace('-dev.dmg', '.dmg')
index_path = '/desktop/apps/index.json'
index_block = '\nlocation = ' + index_path + ' {\n    alias ' + str(root / 'index.json') + ';\n    default_type application/json;\n    add_header Cache-Control "no-store" always;\n    add_header X-Content-Type-Options nosniff always;\n    limit_except GET HEAD { deny all; }\n}\n'
desired_routes = [(public_path, str(destination), artifact_route(public_path)),
                  (legacy_path, str(destination), artifact_route(legacy_path)),
                  (index_path, str(root / 'index.json'), index_block)]
with (root / '.internal-index.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    assert root.stat().st_uid == 0 and not root.is_symlink()
    assert not destination.parent.is_symlink()
    destination.parent.mkdir(mode=0o755, exist_ok=True)
    assert destination.parent.stat().st_uid == 0
    if destination.exists():
        assert destination.stat().st_uid == 0 and destination.stat().st_mode & 0o777 == 0o644
        assert not destination.is_symlink() and destination.read_bytes() == payload, 'immutable package conflict'
    else:
        fd, candidate = tempfile.mkstemp(prefix='.artifact-', dir=destination.parent)
        try:
            with os.fdopen(fd, 'wb') as out:
                out.write(payload); out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), 0o644)
            # Atomic no-overwrite install, even against a writer not using this lock.
            os.link(candidate, destination)
        finally:
            pathlib.Path(candidate).unlink(missing_ok=True)
    before = routes.read_text()
    candidate = before
    for path, alias, block in desired_routes:
        marker = 'location = ' + path + ' {'
        if marker in before:
            assert before.count(marker) == 1
            start = before.index(marker); end = start + len(marker); depth = 1
            while depth and end < len(before):
                depth += (before[end] == '{') - (before[end] == '}'); end += 1
            assert depth == 0
            existing = before[start:end]
            assert 'alias ' + alias + ';' in existing and 'limit_except GET HEAD { deny all; }' in existing
            if path == index_path:
                assert 'Cache-Control "no-store"' in existing
        else:
            candidate += block
    if candidate != before:
        def replace_routes(text):
            fd, name = tempfile.mkstemp(prefix='.desktop-routes-', dir=routes.parent)
            try:
                with os.fdopen(fd, 'w') as out:
                    out.write(text); out.flush(); os.fsync(out.fileno()); os.fchmod(out.fileno(), routes.stat().st_mode & 0o777)
                os.replace(name, routes)
            finally:
                pathlib.Path(name).unlink(missing_ok=True)
        replace_routes(candidate)
        try:
            subprocess.run(['nginx', '-t'], check=True, capture_output=True)
            subprocess.run(['systemctl', 'reload', 'nginx'], check=True, capture_output=True)
        except:
            assert routes.read_text() == candidate, 'concurrent route change; refusing rollback'
            replace_routes(before)
            subprocess.run(['nginx', '-t'], check=True, capture_output=True)
            subprocess.run(['systemctl', 'reload', 'nginx'], check=True, capture_output=True)
            raise
print('DESKTOP_INTERNAL_ARTIFACT_READY')
`;
