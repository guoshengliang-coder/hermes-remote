import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const ALL = Object.freeze({node: true, android: true, desktop: true, assets: true, web: true});

// The Gateway files the Web app calls directly: a change there must also run the Web job, the way
// INTEGRATION.md's contract surfaces require the other sides to be addressed.
const WEB_CONTRACT_FILES = [
  'gateway/src/gateway-http-router.ts',
  'gateway/src/account/web-device-access.ts',
  'gateway/src/account/web-session-security.ts',
  'gateway/src/web-app-host.ts',
  'docs/hermes-rpc-params.json',
];

export function classifyChangedPaths(paths) {
  const result = {node: false, android: false, desktop: false, assets: false, web: false};

  for (const file of paths) {
    if (file === '.github/workflows/ci.yml') return {...ALL};

    if (
      file === 'package.json' ||
      file === 'package-lock.json' ||
      file.startsWith('.github/workflows/') ||
      file.startsWith('gateway/') ||
      file.startsWith('connector/') ||
      file.startsWith('protocol/') ||
      file.startsWith('release-server/') ||
      file.startsWith('ops/') ||
      file.startsWith('deploy/') ||
      file.startsWith('scripts/')
    ) result.node = true;

    if (file.startsWith('android/')) result.android = true;

    // web/ is a standalone package with its own lockfile: it never selects the Node workspace job,
    // and workspace changes select the Web job only through the files it calls.
    if (
      file.startsWith('web/') ||
      WEB_CONTRACT_FILES.includes(file) ||
      /^gateway\/src\/account\/[^/]+-http-controller\.ts$/.test(file)
    ) result.web = true;

    if (
      (file.startsWith('desktop/') && file !== 'desktop/Packaging/AppIcon.png') ||
      file === 'package.json'
    ) result.desktop = true;

    if (
      file === 'android/app/src/main/ic_launcher-playstore.png' ||
      file === 'desktop/Packaging/AppIcon.png'
    ) result.assets = true;
  }

  return result;
}

function changedPaths(base, head) {
  if (!base || !head || /^0+$/.test(base)) return null;
  try {
    const output = execFileSync('git', ['diff', '--name-only', '-z', base, head], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'inherit'],
    });
    return output.split('\0').filter(Boolean);
  } catch {
    return null;
  }
}

function main() {
  const paths = changedPaths(process.argv[2], process.argv[3]);
  const result = paths === null ? ALL : classifyChangedPaths(paths);
  for (const [component, changed] of Object.entries(result)) {
    process.stdout.write(`${component}=${changed}\n`);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main();
