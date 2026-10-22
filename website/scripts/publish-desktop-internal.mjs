#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, isAbsolute, join } from 'node:path';
import { parseArgs } from 'node:util';
import { prepareInternalDmg, publishInternalDmg, remoteArtifactInstall } from '../src/internal-dmg-publication.mjs';
import { remoteTransaction } from '../src/internal-publication.mjs';
import { errorEnvelope } from '../src/errors.mjs';

const execute = promisify(execFile);
let remoteStage, sshTarget;
async function ssh(command) {
  return execute('ssh', ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', sshTarget, command], { timeout: 30_000 });
}
async function remotePython(source, args) {
  const code = `import base64;exec(base64.b64decode("${Buffer.from(source).toString('base64')}"))`;
  return ssh(`sudo -n python3 -c '${code}' ${args.join(' ')}`);
}
async function verifyBundle(dmg, index) {
  if (process.platform !== 'darwin') throw new Error('macOS verification required');
  await execute('hdiutil', ['verify', dmg]);
  const mount = await mkdtemp(join(tmpdir(), 'hermes-internal-inspection-'));
  let mounted = false;
  try {
    await execute('hdiutil', ['attach', dmg, '-readonly', '-nobrowse', '-mountpoint', mount]);
    mounted = true;
    const app = join(mount, 'Hermes Go Desktop.app');
    await execute('codesign', ['--verify', '--deep', '--strict', app]);
    const { stderr: signature } = await execute('codesign', ['-dv', app]);
    if (!signature.includes('Signature=adhoc')) throw new Error('not an ad-hoc test artifact');
    const fields = { CFBundleIdentifier: 'com.hermesgo.desktop', CFBundleShortVersionString: index.appVersion,
      CFBundleVersion: String(index.buildNumber), LSMinimumSystemVersion: index.minimumMacOS };
    for (const [key, expected] of Object.entries(fields)) {
      const { stdout } = await execute('/usr/libexec/PlistBuddy', ['-c', `Print :${key}`, join(app, 'Contents/Info.plist')]);
      if (stdout.trim() !== expected) throw new Error('bundle identity mismatch');
    }
    const { stdout: binary } = await execute('file', [join(app, 'Contents/MacOS/HermesGoDesktop')]);
    const architectures = ['arm64', 'x86_64'].filter(value => binary.includes(value));
    if ((index.architecture === 'universal' ? architectures.length !== 2 : architectures.length !== 1 || architectures[0] !== index.architecture)) throw new Error('architecture mismatch');
  } finally {
    if (mounted) await execute('hdiutil', ['detach', mount]);
    await rm(mount, { recursive: true });
  }
}

try {
  const { values } = parseArgs({ options: {
    receipt: { type: 'string' }, dmg: { type: 'string' }, 'notes-file': { type: 'string' }, 'evidence-dir': { type: 'string' },
    'ssh-target': { type: 'string' }, architecture: { type: 'string', default: 'arm64' },
    'minimum-macos': { type: 'string', default: '14.0' }, help: { type: 'boolean' },
  } });
  if (values.help) {
    console.log('node website/scripts/publish-desktop-internal.mjs --receipt <verified-package.json> --dmg <version-dev.dmg> --notes-file <notes.json> --evidence-dir <new-absolute-directory> --ssh-target user@host');
  } else {
    sshTarget = values['ssh-target'];
    if (!/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$/.test(sshTarget ?? '') || !isAbsolute(values.dmg ?? '')
        || !isAbsolute(values['evidence-dir'] ?? '')) throw new Error('invalid operator input');
    const receipt = JSON.parse(await readFile(values.receipt, 'utf8'));
    const index = await prepareInternalDmg({ receipt, dmg: values.dmg, notes: JSON.parse(await readFile(values['notes-file'], 'utf8')),
      architecture: values.architecture, minimumMacOS: values['minimum-macos'] });
    async function sourceGate() {
      await execute('git', ['fetch', 'origin', 'main']);
      const { stdout: status } = await execute('git', ['status', '--porcelain']);
      const { stdout: head } = await execute('git', ['rev-parse', 'HEAD']);
      const { stdout: main } = await execute('git', ['rev-parse', 'origin/main']);
      if (status.trim() || head !== main) throw new Error('clean origin/main required');
      await execute('git', ['merge-base', '--is-ancestor', index.sourceCommit, 'HEAD']);
      // A newly built candidate must belong to this release checkout. A completed old
      // publication can be retried with its original receipt and exact immutable bytes.
      if (receipt.stage === 'internal-test-package-verified' && index.sourceCommit !== head.trim()) throw new Error('candidate source mismatch');
    }
    await sourceGate();
    await verifyBundle(values.dmg, index);
    await mkdir(values['evidence-dir'], { mode: 0o700 });
    await writeFile(join(values['evidence-dir'], 'candidate-index.json'), `${JSON.stringify(index)}\n`, { flag: 'wx', mode: 0o600 });
    const run = randomUUID();
    remoteStage = `/tmp/hermes-desktop-internal-${run}`;
    await sourceGate();
    await ssh(`umask 077; mkdir '${remoteStage}'`);
    await execute('scp', [values.dmg, `${sshTarget}:${remoteStage}/${basename(values.dmg)}`], { timeout: 60_000 });
    const completed = await publishInternalDmg(index, {
      installArtifact: async () => remotePython(remoteArtifactInstall, [remoteStage, Buffer.from(JSON.stringify(index)).toString('base64')]),
      executeRemote: async (action, data) => remotePython(remoteTransaction, [action, Buffer.from(data).toString('base64'), run]),
    });
    await writeFile(join(values['evidence-dir'], 'publication-receipt.json'), `${JSON.stringify(completed, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
    console.log('DESKTOP_INTERNAL_RELEASE_AND_WEBSITE_SYNC_OK');
    console.log('DESKTOP_INTERNAL_APP_UPDATE_SYNC_OK');
    console.log('APP_INDEX=https://mrlgs.net/desktop/apps/index.json');
    console.log(`VERSION=${index.appVersion}`);
    console.log(`URL=${index.downloadURL}`);
  }
} catch {
  const error = errorEnvelope('HR-SITE-004');
  console.error(`${error.code} ${error.message}`);
  console.error('内测发布或官网同步未完成，请核对官网当前版本，检查包校验、源码状态与发布环境后重试。');
  process.exitCode = 1;
} finally {
  if (remoteStage) await ssh(`rm -r -- '${remoteStage}'`).catch(() => {});
}
