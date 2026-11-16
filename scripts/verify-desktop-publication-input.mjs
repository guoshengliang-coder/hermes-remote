#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { assertCombinationEvidence, assertAppIndexMatchesBundle, digest } from './lib/desktop-combination-evidence.mjs';

try {
  const [app, indexPath, evidencePath, legacy] = process.argv.slice(2);
  if (!app || !indexPath || !evidencePath || (legacy && legacy !== '--legacy')) throw new Error('arguments');
  const index = JSON.parse(await readFile(indexPath, 'utf8'));
  const run = (command, args) => execFileSync(command, args, { maxBuffer: 2 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  const plist = JSON.parse(run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', path.join(app, 'Contents/Info.plist')]));
  assertAppIndexMatchesBundle(index, plist);
  const verification = run('swift', ['run', '--package-path', 'desktop', 'DesktopReleaseVerifier', app,
    index.appVersion, String(index.buildNumber), index.sourceCommit, ...(legacy ? ['--legacy'] : [])]);
  const bundleSHA256 = verification.toString().match(/^DESKTOP_APP_BUNDLE_SHA256=([a-f0-9]{64})$/m)?.[1];
  if (!legacy) {
    const evidence = JSON.parse(await readFile(evidencePath, 'utf8'));
    const current = JSON.parse(run('curl', ['--fail', '--silent', '--show-error', '--max-time', '30', plist.HermesGoDesktopComponentManifestURL]));
    assertCombinationEvidence(evidence, { version: index.appVersion, build: index.buildNumber, sourceCommit: index.sourceCommit, bundleSHA256,
      executableSHA256: digest(await readFile(path.join(app, 'Contents/MacOS/HermesGoDesktop'))),
      manifestSHA256: current.manifestSHA256 });
  }
  console.log('DESKTOP_PUBLICATION_INPUT_OK');
} catch {
  console.error('HR-DESKUPDATE-004 发行配置或精确组合证据不匹配，发布已停止。 / Release configuration or combination evidence mismatch.');
  process.exitCode = 1;
}
