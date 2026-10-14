import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
import vm from 'node:vm';
import { createWebsite, readConfig } from '../src/server.mjs';
import { createReleaseReader, validateDesktop, validateDesktopInternal, DESKTOP_INTERNAL_INDEX } from '../src/releases.mjs';
import { errors, errorEnvelope } from '../src/errors.mjs';

const testEnv = { WEBSITE_INVITE_CODE: '123456', WEBSITE_SESSION_SECRET: 'test-only-session-key-with-at-least-32-bytes' };
const android = {
  schemaVersion: 1, channel: 'internal', generatedAt: '2026-10-10T12:00:00Z', latestVersionCode: 171,
  versions: [{ versionName: '0.1.170', versionCode: 171, applicationId: 'com.hermes.remote', channel: 'internal',
    publishedAt: '2026-10-10T12:00:00Z', fileName: 'Hermes-Remote-0.1.170-debug.apk',
    downloadUrl: 'https://mrlgs.net/releases/Hermes-Remote-0.1.170-debug.apk', sizeBytes: 33372831, minSdk: 26,
    sha256: 'a'.repeat(64), certificateSha256: '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
    sourceCommit: 'b'.repeat(40), releaseNotes: ['改善图片查看。'] }],
};
const desktop = { schemaVersion: 1, channel: 'internal', architecture: 'arm64', appVersion: '0.2.31', buildNumber: 34,
  minimumMacOS: '14.0', downloadURL: 'https://mrlgs.net/desktop/apps/0.2.31/Hermes-Go-Desktop-0.2.31.dmg',
  sizeBytes: 100000, sha256: 'c'.repeat(64), releaseNotes: ['连接现有 Hermes。'], sourceCommit: 'b'.repeat(40), updatedAt: '2026-10-10T12:00:00Z' };
const internalDesktop = { ...desktop, distribution: 'ad-hoc', notarized: false,
  downloadURL: desktop.downloadURL.replace('.dmg', '-dev.dmg') };
function fixtureReader() { return createReleaseReader({ fetcher: async url => url.includes('/releases/') ? Response.json(android) : new Response(null, { status: 404 }) }); }
async function start(t, options = {}) {
  const config = options.config ?? readConfig(testEnv);
  const server = createWebsite({ config, releases: fixtureReader(), ...options });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const request = (path, init) => fetch(origin + path, { redirect: 'manual', ...init });
  const invite = (code = '123456', extra = {}) => request('/api/invite', { method: 'POST',
    headers: { Origin: config.origin, 'Content-Type': 'application/json', Accept: 'application/json', ...extra }, body: JSON.stringify({ code }) });
  return { request, invite, config };
}
function cookie(response) { return response.headers.get('set-cookie')?.split(';')[0]; }

test('public pages follow the agreed mobile entries; HTML does not disclose invitation or release URLs', async t => {
  const { request } = await start(t);
  for (const path of ['/', '/download/', '/guide/', '/help/']) {
    const response = await request(path); assert.equal(response.status, 200);
    const html = await response.text();
    assert.match(html, /<html lang="zh-CN">/); assert.match(html, /href="https:\/\/mrlgs.net\/app\/"/);
    assert.doesNotMatch(html, /123456|666666|test-only-session-key|Hermes-Remote-.*\.apk|mailto:|电脑浏览器|设计预览/);
    assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
    assert.equal(response.headers.get('cache-control'), 'no-store');
  }
  const home = await (await request('/')).text();
  assert.match(home, /你的 AGENT，/); assert.match(home, /随时在身边。/);
  const header = home.split('</header>')[0]; assert.doesNotMatch(header, /打开 Web App/);
  const download = await (await request('/download/')).text();
  assert.match(download, /0\.1\.170/); assert.match(download, /安装包暂未开放/); assert.doesNotMatch(download, /href="\/downloads\/desktop"/);
  const summary = await (await request('/api/releases')).json();
  assert.equal(summary.android.version, '0.1.170'); assert.equal(summary.desktop.status, 'unpublished');
  assert.equal(summary.android.url, undefined);
});

test('downloads require server verification, use a signed HttpOnly cookie and target the latest validated release', async t => {
  const { request, invite } = await start(t);
  assert.equal((await request('/downloads/android')).status, 403);
  const rejected = await invite('000000'); assert.equal(rejected.status, 403);
  assert.equal((await rejected.json()).error.code, 'HR-SITE-001'); assert.equal(cookie(rejected), undefined);
  const accepted = await invite(); assert.equal(accepted.status, 200);
  const setCookie = accepted.headers.get('set-cookie');
  assert.match(setCookie, /HttpOnly; SameSite=Strict; Max-Age=43200/); assert.doesNotMatch(setCookie, /123456/);
  assert.deepEqual(await accepted.json(), { verified: true });
  const access = cookie(accepted);
  const download = await request('/downloads/android', { headers: { Cookie: access } });
  assert.equal(download.status, 303); assert.equal(download.headers.get('location'), android.versions[0].downloadUrl);
  assert.equal((await request('/downloads/android', { headers: { Cookie: access.slice(0, -1) + '!' } })).status, 403);
  const page = await (await request('/download/', { headers: { Cookie: access } })).text();
  assert.match(page, /下载资格已解锁/); assert.match(page, /href="\/downloads\/android"/);
  assert.equal((await request('/downloads/desktop', { headers: { Cookie: access } })).status, 503);
});

test('expired and differently signed sessions cannot download', async t => {
  let time = 1_000_000;
  const { request, invite } = await start(t, { now: () => time });
  const access = cookie(await invite());
  time += 12 * 60 * 60 * 1000;
  const expired = await request('/downloads/android', { headers: { Cookie: access, Accept: 'application/json' } });
  assert.equal(expired.status, 403); assert.equal((await expired.json()).error.code, 'HR-SITE-003');
  const other = await start(t, { config: readConfig({ ...testEnv, WEBSITE_SESSION_SECRET: 'different-test-key' }) });
  assert.equal((await other.request('/downloads/android', { headers: { Cookie: access } })).status, 403);
});

test('production sessions are Secure and host-only; startup fails closed on missing secrets', async t => {
  assert.throws(() => readConfig({ NODE_ENV: 'production' }));
  assert.throws(() => readConfig({ NODE_ENV: 'production', WEBSITE_INVITE_CODE: '123456' }));
  assert.throws(() => readConfig({ ...testEnv, NODE_ENV: 'production', WEBSITE_ORIGIN: 'http://go.mrlgs.net' }));
  assert.throws(() => readConfig({ ...testEnv, WEBSITE_SUPPORT_EMAIL: 'x@example.com\r\nLocation:bad' }));
  const { invite } = await start(t, { config: readConfig({ ...testEnv, NODE_ENV: 'production' }) });
  assert.match((await invite()).headers.get('set-cookie'), /^__Host-hgo-invite=.*; Secure$/);
});

test('invite rejects cross-origin, missing Origin, invalid content, huge bodies and unsupported methods', async t => {
  const { request, config } = await start(t);
  const base = { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: '{"code":"123456"}' };
  for (const origin of [undefined, 'https://evil.example']) {
    const headers = { ...base.headers }; if (origin) headers.Origin = origin;
    const response = await request('/api/invite', { ...base, headers });
    assert.equal(response.status, 400); assert.equal((await response.json()).error.code, 'HR-SITE-005');
  }
  assert.equal((await request('/api/invite', { ...base, headers: { ...base.headers, Origin: config.origin }, body: '{invalid' })).status, 400);
  const tooLarge = await request('/api/invite', { ...base, headers: { ...base.headers, Origin: config.origin }, body: 'x'.repeat(2048) });
  assert.equal(tooLarge.status, 413);
  assert.equal((await request('/downloads/android', { method: 'POST' })).status, 405);
});

test('rate limit cannot be bypassed with spoofed forwarded headers and resets after its window', async t => {
  let time = 1_000_000;
  const { invite } = await start(t, { now: () => time });
  for (let i = 0; i < 8; i++) assert.equal((await invite('000000', { 'X-Real-IP': `198.51.100.${i}` })).status, 403);
  const limited = await invite(); assert.equal(limited.status, 429);
  assert.equal(limited.headers.get('retry-after'), '600'); assert.equal((await limited.json()).error.code, 'HR-SITE-002');
  time += 600_001; assert.equal((await invite()).status, 200);
});

test('regular form POST works without JavaScript and configured email is the only visible contact button', async t => {
  const { request, config } = await start(t, { config: readConfig({ ...testEnv, WEBSITE_SUPPORT_EMAIL: 'support@example.org' }) });
  const result = await request('/api/invite', { method: 'POST', headers: { Origin: config.origin, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'code=123456' });
  assert.equal(result.status, 303); assert.equal(result.headers.get('location'), '/download/#clients');
  assert.match(await (await request('/help/')).text(), /href="mailto:support@example.org"/);
});

test('only explicit assets are served; unknown platform/path cannot become an open redirect or file read', async t => {
  const { request } = await start(t);
  for (const path of ['/src/server.mjs', '/.env', '/logo.png/../../package.json', '/downloads/https%3A%2F%2Fevil.example', '/missing']) assert.equal((await request(path)).status, 404);
  assert.match((await request('/logo.png')).headers.get('content-type'), /image\/png/);
  assert.equal((await request('/site.css', { method: 'HEAD' })).status, 200);
  assert.equal((await request('/site.js')).headers.get('cache-control'), 'no-cache');
  assert.match(await (await request('/robots.txt')).text(), /Disallow: \/downloads\//);
  assert.match(await (await request('/sitemap.xml')).text(), /\/guide\//);
});

test('untrusted release fields are escaped and error serialization has localized fixed semantics only', async t => {
  const malicious = structuredClone(android); malicious.versions[0].releaseNotes = ['<script>credential</script>'];
  const releases = createReleaseReader({ fetcher: async url => url.includes('/releases/') ? Response.json(malicious) : new Response(null, { status: 404 }) });
  const { request } = await start(t, { releases });
  const html = await (await request('/download/')).text();
  assert.match(html, /&lt;script&gt;credential&lt;\/script&gt;/); assert.doesNotMatch(html, /<script>credential/);
  const registry = await readFile(new URL('../../docs/ERROR_HANDLING.md', import.meta.url), 'utf8');
  for (const code of Object.keys(errors)) {
    const zh = errorEnvelope(code), en = errorEnvelope(code, 'en');
    assert.equal(zh.code, code); assert.equal(en.code, code); assert.notEqual(zh.message, en.message);
    assert.equal(typeof zh.retryable, 'boolean'); assert.ok(zh.recoveryAction); assert.ok(zh.stage);
    assert.deepEqual(Object.keys(zh), ['code', 'message', 'retryable', 'recoveryAction', 'stage']);
    assert.ok(registry.includes(code)); assert.ok(registry.includes(zh.message)); assert.ok(registry.includes(en.message));
    assert.doesNotMatch(JSON.stringify(zh), /123456|Cookie|test-only-session-key/);
  }
});

test('release indexes are validated, bounded and cached; download resolution explicitly refreshes', async () => {
  let calls = 0; const index = structuredClone(android);
  const reader = createReleaseReader({ fetcher: async () => { calls++; return Response.json(index); } });
  assert.equal((await reader.load('android')).version, '0.1.170');
  await reader.load('android'); assert.equal(calls, 1);
  Object.assign(index.versions[0], { versionName: '0.1.171', fileName: 'Hermes-Remote-0.1.171-debug.apk', downloadUrl: 'https://mrlgs.net/releases/Hermes-Remote-0.1.171-debug.apk' });
  assert.equal((await reader.load('android', true)).version, '0.1.171'); assert.equal(calls, 2);
  const oversized = createReleaseReader({ fetcher: async () => new Response('x'.repeat(1024 * 1024 + 1)) });
  assert.equal((await oversized.load('android')).status, 'unavailable');
  const failed = createReleaseReader({ fetcher: async () => { throw new Error('PRIVATE upstream detail'); } });
  assert.deepEqual(await failed.load('android'), { status: 'unavailable' });
});

test('invalid Android identity and unsafe URLs never produce downloads', async () => {
  for (const change of [ { downloadUrl: 'https://evil.example/a.apk' }, { downloadUrl: android.versions[0].downloadUrl + '?secret=bad' },
    { applicationId: 'com.other.app' }, { certificateSha256: '0'.repeat(64) }, { fileName: 'app-debug.apk' }, { versionCode: 172 } ]) {
    const index = structuredClone(android); Object.assign(index.versions[0], change);
    const reader = createReleaseReader({ fetcher: async () => Response.json(index) });
    assert.deepEqual(await reader.load('android'), { status: 'unavailable' });
  }
  const empty = { ...android, versions: [], latestVersionCode: 0 };
  assert.equal((await createReleaseReader({ fetcher: async () => Response.json(empty) }).load('android')).status, 'unpublished');
});

test('Desktop pointer validates architecture, identity, hash, versioned filename and minimum OS', async () => {
  assert.deepEqual(validateDesktop(desktop), desktop);
  for (const change of [{ downloadURL: desktop.downloadURL + '?redirect=bad' }, { downloadURL: 'https://evil.example/file.dmg' },
    { appVersion: '../escape' }, { architecture: 'unknown' }, { channel: 'stable' }, { sha256: 'bad' }, { minimumMacOS: '<script>' },
    { buildNumber: 0 }, { sourceCommit: 'bad' }, { updatedAt: 'not-a-date' }]) assert.throws(() => validateDesktop({ ...desktop, ...change }));
  const reader = createReleaseReader({ fetcher: async url => url === DESKTOP_INTERNAL_INDEX ? new Response(null, { status: 404 }) : Response.json(desktop) });
  assert.equal((await reader.load('desktop')).url, desktop.downloadURL);
});

test('website test channel remains distinct from official app updates and rejects unsafe metadata', () => {
  assert.deepEqual(validateDesktopInternal(internalDesktop), internalDesktop);
  assert.throws(() => validateDesktop(internalDesktop));
  assert.throws(() => validateDesktopInternal(desktop));
  for (const change of [{ notarized: true }, { notarized: undefined }, { distribution: 'official' },
    { downloadURL: desktop.downloadURL }, { downloadURL: internalDesktop.downloadURL + '?token=secret' },
    { downloadURL: 'https://evil.example/file-dev.dmg' }, { appVersion: '../escape' },
    { sha256: 'bad' }, { sourceCommit: 'bad' }, { updatedAt: '2026-13-01T00:00:00Z' }]) {
    assert.throws(() => validateDesktopInternal({ ...internalDesktop, ...change }));
  }
});

test('published internal Desktop appears on the site, remains invitation gated and resolves the exact dev DMG', async t => {
  const urls = [];
  const releases = createReleaseReader({ fetcher: async url => {
    urls.push(url); return Response.json(url === DESKTOP_INTERNAL_INDEX ? internalDesktop : android);
  } });
  const { request, invite } = await start(t, { releases });
  const html = await (await request('/download/')).text();
  assert.match(html, /0\.2\.31/); assert.match(html, /内部测试版/); assert.match(html, /尚未通过 Apple 公证/);
  assert.match(html, /Apple Silicon/); assert.doesNotMatch(html, /href="\/downloads\/desktop"|\.dmg/);
  assert.equal((await request('/downloads/desktop')).status, 403);
  const access = cookie(await invite());
  const verified = await (await request('/download/', { headers: { Cookie: access } })).text();
  assert.match(verified, /href="\/downloads\/desktop"/); assert.match(verified, /下载 macOS 内测版/);
  const response = await request('/downloads/desktop', { headers: { Cookie: access } });
  assert.equal(response.status, 303); assert.equal(response.headers.get('location'), internalDesktop.downloadURL);
  const summary = await (await request('/api/releases')).json();
  assert.equal(summary.desktop.distribution, 'ad-hoc'); assert.equal(summary.desktop.notarized, false);
  assert.equal(summary.desktop.url, undefined);
  assert.equal(urls.includes('https://mrlgs.net/desktop/apps/index.json'), false);
});

test('Desktop refresh tracks newer test releases and fails closed without official or stale fallback', async t => {
  let index = structuredClone(internalDesktop); let officialCalls = 0;
  const releases = createReleaseReader({ fetcher: async url => {
    if (url !== DESKTOP_INTERNAL_INDEX) { officialCalls++; return Response.json(desktop); }
    return Response.json(index);
  } });
  const { request, invite } = await start(t, { releases });
  const access = cookie(await invite());
  assert.equal((await request('/downloads/desktop', { headers: { Cookie: access } })).headers.get('location'), index.downloadURL);
  Object.assign(index, { appVersion: '0.2.32', buildNumber: 35,
    downloadURL: 'https://mrlgs.net/desktop/apps/0.2.32/Hermes-Go-Desktop-0.2.32-dev.dmg' });
  assert.equal((await request('/downloads/desktop', { headers: { Cookie: access } })).headers.get('location'), index.downloadURL);
  index = { ...index, sha256: 'broken' };
  const failed = await request('/downloads/desktop', { headers: { Cookie: access, Accept: 'application/json' } });
  assert.equal(failed.status, 503); assert.equal(failed.headers.get('location'), null);
  assert.equal((await failed.json()).error.code, 'HR-SITE-004');
  assert.equal(officialCalls, 0);
});

test('malformed or unreachable test pointers never fall back to another channel', async () => {
  for (const value of [null, false, [], {}, 'invalid']) {
    const reader = createReleaseReader({ fetcher: async url => {
      assert.equal(url, DESKTOP_INTERNAL_INDEX); return Response.json(value);
    } });
    assert.deepEqual(await reader.load('desktop'), { status: 'unavailable' });
  }
  const reader = createReleaseReader({ fetcher: async url => {
    assert.equal(url, DESKTOP_INTERNAL_INDEX); throw new Error('network failure');
  } });
  assert.deepEqual(await reader.load('desktop'), { status: 'unavailable' });
});

test('slow and redirecting release responses fail closed within the fetch boundary', async () => {
  const reader = createReleaseReader({ timeoutMs: 15, fetcher: (_url, options) => {
    assert.equal(options.redirect, 'error'); assert.equal(options.headers.Authorization, undefined);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => resolve(Response.json(android)), 1000);
      options.signal.addEventListener('abort', () => { clearTimeout(timer); reject(new Error('private timeout detail')); }, { once: true });
    });
  } });
  assert.deepEqual(await reader.load('android'), { status: 'unavailable' });
  const redirect = createReleaseReader({ fetcher: async () => new Response(null, { status: 302, headers: { Location: 'https://evil.example' } }) });
  assert.deepEqual(await redirect.load('android'), { status: 'unavailable' });
});

test('failed refresh discards an old good pointer; requests never fall back to stale downloads', async t => {
  let broken = false;
  const releases = createReleaseReader({ fetcher: async () => broken ? Response.json({ bad: true }) : Response.json(android) });
  const { request, invite } = await start(t, { releases });
  const access = cookie(await invite());
  assert.equal((await request('/downloads/android', { headers: { Cookie: access } })).status, 303);
  broken = true;
  const result = await request('/downloads/android', { headers: { Cookie: access, Accept: 'application/json' } });
  assert.equal(result.status, 503); assert.equal(result.headers.get('location'), null);
  assert.equal((await result.json()).error.code, 'HR-SITE-004');
});

test('successful enhanced invite reloads SSR links even when already on the download page', async () => {
  let onSubmit, reloaded = false, reset = false, target;
  const status = { classList: { remove() {}, add() {} }, textContent: '' }, button = { disabled: false };
  const form = { elements: { code: { value: '123456' } }, querySelector: () => button,
    addEventListener: (_event, listener) => { onSubmit = listener; }, reset: () => { reset = true; } };
  const context = vm.createContext({ document: { querySelector: selector => selector === '[data-invite-form]' ? form : null, getElementById: () => status },
    fetch: async () => ({ ok: true, json: async () => ({ verified: true }) }),
    history: { replaceState: (_state, _unused, path) => { target = path; } }, location: { reload: () => { reloaded = true; } } });
  vm.runInContext(await readFile(new URL('../public/site.js', import.meta.url), 'utf8'), context);
  await onSubmit({ preventDefault() {} });
  assert.equal(target, '/download/#clients'); assert.equal(reloaded, true); assert.equal(reset, true); assert.equal(button.disabled, false);
});
