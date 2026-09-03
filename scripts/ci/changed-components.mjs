import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const ALL = Object.freeze({node: true, android: true, desktop: true, assets: true});

export function classifyChangedPaths(paths) {
  const result = {node: false, android: false, desktop: false, assets: false};

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
