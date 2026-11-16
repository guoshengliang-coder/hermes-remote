#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyDesktopComponentReleaseV2 } from './lib/desktop-managed-release.mjs';
import { buildCombinationEvidence } from './lib/desktop-combination-evidence.mjs';

// Explicit local inputs only. The installed upstream probe enforces its own write/network guards.
// This gate certifies one exact isolated combination, never the owner's production session state.
try {
  const [flag, input, outputFlag, output] = process.argv.slice(2);
  if (flag !== '--config' || outputFlag !== '--output' || process.argv.length !== 6) throw new Error('arguments');
  const config = JSON.parse(await readFile(input, 'utf8'));
  const keys = ['appBundle', 'componentManifest', 'componentArtifacts', 'hermesRoot', 'hermesPython', 'hermesCommit'];
  if (Object.keys(config).sort().join() !== keys.sort().join() || !keys.every(k => typeof config[k] === 'string')
      || !keys.filter(k => k !== 'hermesCommit').every(k => path.isAbsolute(config[k]))) throw new Error('config');
  const repository = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
  const run = (exe, args) => execFileSync(exe, args, { cwd: repository, maxBuffer: 2 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  const plist = JSON.parse(run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', path.join(config.appBundle, 'Contents/Info.plist')]));
  const app = { version: plist.CFBundleShortVersionString, build: Number(plist.CFBundleVersion), sourceCommit: plist.HermesGoSourceCommit };
  const verification = run('swift', ['run', '--package-path', 'desktop', 'DesktopReleaseVerifier', config.appBundle, app.version, String(app.build), app.sourceCommit]);
  const bundleSHA256 = verification.toString().match(/^DESKTOP_APP_BUNDLE_SHA256=([a-f0-9]{64})$/m)?.[1];
  const manifestBytes = await readFile(config.componentManifest);
  const envelope = JSON.parse(manifestBytes);
  const trust = plist.HermesGoDesktopReleaseSigningKeys ? JSON.parse(plist.HermesGoDesktopReleaseSigningKeys)
    : { [plist.HermesGoDesktopReleaseSigningKeyID]: plist.HermesGoDesktopReleaseSigningPublicKey };
  const manifest = await verifyDesktopComponentReleaseV2({ manifestPath: config.componentManifest,
    artifactDirectory: config.componentArtifacts, expectedKeyId: envelope.keyId, publicKey: trust[envelope.keyId],
    expectedOrigin: plist.HermesGoDesktopReleaseArtifactOrigin, expectedChannel: plist.HermesGoDesktopReleaseChannel,
    expectedArchitecture: plist.HermesGoDesktopReleaseArchitecture });
  function requireUnchangedUpstream() {
    const profile = '(version 1) (allow default) (deny file-write* (require-not (literal "/dev/null"))) (deny network*) (deny process-fork)';
    const status = run('/usr/bin/sandbox-exec', ['-p', profile, '/usr/bin/git', '--no-optional-locks', '--no-replace-objects',
      '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false', '-C', config.hermesRoot, 'status', '--porcelain']);
    const head = run('/usr/bin/git', ['--no-optional-locks', '--no-replace-objects', '-C', config.hermesRoot, 'rev-parse', 'HEAD']).toString().trim();
    if (status.length !== 0 || head !== config.hermesCommit) throw new Error('upstream source changed');
  }
  requireUnchangedUpstream();
  const upstreamBytes = run(config.hermesPython, ['-I', '-B', 'scripts/dev/check-hermes-archive.py', '--hermes-root', config.hermesRoot]);
  requireUnchangedUpstream();
  const evidence = buildCombinationEvidence({ app, bundleSHA256,
    executable: await readFile(path.join(config.appBundle, 'Contents/MacOS/HermesGoDesktop')),
    manifest, manifestBytes, upstream: JSON.parse(upstreamBytes), upstreamBytes, hermesCommit: config.hermesCommit });
  await writeFile(output, `${JSON.stringify(evidence, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  console.log('DESKTOP_ISOLATED_COMBINATION_OK');
} catch {
  console.error('HR-DESKUPDATE-004 精确组合验证未通过，未生成发布证据。 / Exact combination verification failed.');
  process.exitCode = 1;
}
