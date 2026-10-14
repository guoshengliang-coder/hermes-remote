#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { createInternalIndex, publishInternalIndex, remoteTransaction } from '../src/internal-publication.mjs';
import { errorEnvelope } from '../src/errors.mjs';

const execute = promisify(execFile);
try {
  const { values } = parseArgs({ options: {
    receipt: { type: 'string' }, dmg: { type: 'string' }, 'notes-file': { type: 'string' }, output: { type: 'string' },
    architecture: { type: 'string', default: 'arm64' }, 'minimum-macos': { type: 'string', default: '14.0' },
    publish: { type: 'boolean', default: false }, 'ssh-target': { type: 'string' }, help: { type: 'boolean' },
  } });
  if (values.help) {
    console.log('node website/scripts/desktop-internal-index.mjs --receipt <verified-publication.json> --dmg <version-dev.dmg> --notes-file <notes.json> --output <new-index.json> [--publish --ssh-target user@host]');
  } else {
    const index = await createInternalIndex({ receipt: JSON.parse(await readFile(values.receipt, 'utf8')), dmg: values.dmg,
      notes: JSON.parse(await readFile(values['notes-file'], 'utf8')), architecture: values.architecture, minimumMacOS: values['minimum-macos'] });
    await writeFile(values.output, `${JSON.stringify(index)}\n`, { flag: 'wx', mode: 0o600 });
    if (values.publish) {
      if (!/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$/.test(values['ssh-target'] ?? '')) throw new Error('invalid SSH target');
      // Publication tools must run from a clean merged tree; the artifact may have an older
      // verified source commit because registering an already published build does not rebuild it.
      const { stdout: status } = await execute('git', ['status', '--porcelain']);
      await execute('git', ['fetch', 'origin', 'main']);
      const { stdout: head } = await execute('git', ['rev-parse', 'HEAD']);
      const { stdout: main } = await execute('git', ['rev-parse', 'origin/main']);
      if (status.trim() || head !== main) throw new Error('publication requires clean origin/main');
      await execute('git', ['merge-base', '--is-ancestor', index.sourceCommit, 'HEAD']);
      const run = randomUUID();
      await publishInternalIndex(index, { executeRemote: async (action, data) => {
        // Base64 contains no shell metacharacters. No caller-provided shell text is interpolated.
        const python = `import base64;exec(base64.b64decode("${Buffer.from(remoteTransaction).toString('base64')}"))`;
        const result = await execute('ssh', ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', values['ssh-target'],
          `sudo -n python3 -c '${python}' ${action} ${Buffer.from(data).toString('base64')} ${run}`], { timeout: 30_000 });
        console.log(result.stdout.trim());
      } });
    }
    console.log(values.publish ? 'DESKTOP_INTERNAL_INDEX_PUBLIC_VERIFIED' : 'DESKTOP_INTERNAL_INDEX_PREPARED');
  }
} catch {
  const error = errorEnvelope('HR-SITE-004');
  console.error(`${error.code} ${error.message}`);
  console.error('请核对已验证的内测发布凭据、DMG、索引路由及干净 main 工作区，再重试。');
  process.exitCode = 1;
}
