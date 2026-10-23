// Local-only real HTTP relay benchmark. Build first with npm run build.
// No production credentials, services, or user files are used.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { createReadStream } from 'node:fs';
import { mkdtemp, open, realpath, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { ArtifactDownloadSource } from '../connector/dist/artifact-download-source.js';
import { ArtifactDownloadBroker } from '../gateway/dist/artifact-download-broker.js';

const flags = Object.fromEntries(process.argv.slice(2).map(arg => arg.replace(/^--/, '').split('=')));
const connections = Number(flags.connections ?? 1);
const rangeBytes = Number(flags['range-bytes'] ?? 262144);
const sizeBytes = Number(flags['size-bytes'] ?? 8388608);
const controlDelayMs = Number(flags['control-delay-ms'] ?? 40);
const adaptive = flags.adaptive === 'true';
assert.ok([1, 2, 4].includes(connections));
assert.ok([262144, 1048576, 4194304].includes(rangeBytes));
assert.ok(Number.isSafeInteger(sizeBytes) && sizeBytes > 0 && sizeBytes <= 64 * 1048576);
assert.ok(Number.isFinite(controlDelayMs) && controlDelayMs >= 0 && controlDelayMs <= 1000);
const root = await realpath(await mkdtemp(join(tmpdir(), 'artifact-benchmark-')));
const sourcePath = join(root, 'fixture.bin'), targetPath = join(root, 'result.bin');
const handle = await open(sourcePath, 'w');
const expected = createHash('sha256');
try {
  for (let at = 0; at < sizeBytes;) {
    const block = Buffer.alloc(Math.min(65536, sizeBytes - at), (at / 65536) % 251);
    await handle.write(block); expected.update(block); at += block.length;
  }
} finally { await handle.close(); }
const socket = Object.assign(new EventEmitter(), { readyState: 1 });
const controllers = new Map();
const returnConnections = new Set(), probes = [];
let source, origin, stopped = false, requests = 0, firstRangeMs;
const broker = new ArtifactDownloadBroker((_socket, message) => {
  if (message.type === 'tunnel.http.request') {
    const controller = new AbortController(); controllers.set(message.id, controller);
    void (async () => {
      await delay(controlDelayMs, undefined, { signal: controller.signal });
      await source.respond(message, controller.signal);
    })().catch(() => {}).finally(() => controllers.delete(message.id));
  } else if (message.type === 'tunnel.http.cancel') controllers.get(message.requestId)?.abort();
});
const server = createServer((request, response) => {
  const url = new URL(request.url, origin);
  if (url.pathname === '/probe') { response.end('ok'); return; }
  if (url.pathname.startsWith('/api/file-channel/')) returnConnections.add(request.socket.remotePort);
  void broker.accept(request, response, url).then(accepted => {
    if (!accepted) broker.forward(request, response, url, { socket, deviceId: 'fixture', routingKey: 'fixture' });
  }).catch(() => response.destroy());
});
try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
  source = new ArtifactDownloadSource(root, origin.replace('http:', 'ws:') + '/v1/connect',
    () => 'application/octet-stream', sizeBytes, 4 * 1048576);
  const descriptor = await source.prepare(sourcePath);
  const destination = await open(targetPath, 'w');
  const started = performance.now();
  const probe = (async () => {
    while (!stopped) {
      const at = performance.now(); const response = await fetch(`${origin}/probe`); await response.text();
      probes.push(performance.now() - at); await delay(30);
    }
  })();
  try {
    let cursor = 0;
    let width = adaptive ? 262144 : rangeBytes;
    do {
      const batchStarted = performance.now();
      await Promise.all(Array.from({ length: connections }, async () => {
      while (cursor < sizeBytes) {
        const start = cursor, end = Math.min(sizeBytes, start + width) - 1;
        cursor = end + 1; requests++;
        const response = await fetch(`${origin}/api/files/downloads/${descriptor.artifactId}`, {
          headers: { Range: `bytes=${start}-${end}`, 'If-Match': descriptor.etag },
        });
        assert.equal(response.status, 206, await (response.status === 206 ? Promise.resolve('') : response.text()));
        assert.equal(response.headers.get('etag'), descriptor.etag);
        assert.equal(response.headers.get('content-range'), `bytes ${start}-${end}/${sizeBytes}`);
        const bytes = Buffer.from(await response.arrayBuffer());
        assert.equal(bytes.length, end - start + 1);
        await destination.write(bytes, 0, bytes.length, start);
        firstRangeMs ??= performance.now() - started;
        if (adaptive) break;
      }
      }));
      const batchMs = performance.now() - batchStarted;
      if (batchMs < 1500) width = Math.min(rangeBytes, width * 2);
      else if (batchMs > 3000) width = Math.max(262144, width / 2);
    } while (cursor < sizeBytes);
    const elapsedMs = performance.now() - started;
    await destination.sync();
    const actual = createHash('sha256');
    for await (const block of createReadStream(targetPath)) actual.update(block);
    assert.equal(actual.digest('hex'), expected.digest('hex'));
    stopped = true; await probe;
    probes.sort((a, b) => a - b);
    console.log(JSON.stringify({ sizeBytes, connections, rangeBytes, adaptive, controlDelayMs, requests,
      returnConnections: returnConnections.size, elapsedMs: Math.round(elapsedMs),
      mibPerSecond: Number((sizeBytes / 1048576 / elapsedMs * 1000).toFixed(2)),
      firstRangeMs: Math.round(firstRangeMs), probeP95Ms: Math.round(probes[Math.floor(probes.length * .95)] ?? 0),
      sha256Verified: true, environment: 'loopback, injected control delay, shared 4 MiB/s source budget' }));
  } finally { stopped = true; await probe; await destination.close(); }
} finally {
  stopped = true; for (const controller of controllers.values()) controller.abort();
  broker.close(); source?.clear(); server.closeAllConnections();
  await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true, force: true });
}
