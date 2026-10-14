import http from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createReleaseReader } from './releases.mjs';
import { errorEnvelope } from './errors.mjs';
import { renderPage, renderError } from './pages.mjs';

const SESSION_MS = 12 * 60 * 60 * 1000;
const MAX_BODY = 1024;
const digest = value => createHash('sha256').update(value).digest();
export function readConfig(env = process.env) {
  const production = env.NODE_ENV === 'production';
  const origin = env.WEBSITE_ORIGIN ?? (production ? 'https://go.mrlgs.net' : 'http://127.0.0.1:4174');
  const site = new URL(origin);
  if (site.origin !== origin || site.username || site.password || (production ? site.protocol !== 'https:' : !['http:', 'https:'].includes(site.protocol))) throw new Error('Invalid WEBSITE_ORIGIN');
  if (!/^\d{6}$/.test(env.WEBSITE_INVITE_CODE ?? '')) throw new Error('WEBSITE_INVITE_CODE must be configured as six digits');
  if (production && Buffer.byteLength(env.WEBSITE_SESSION_SECRET ?? '') < 32) throw new Error('WEBSITE_SESSION_SECRET must be at least 32 bytes');
  const supportEmail = env.WEBSITE_SUPPORT_EMAIL ?? '';
  if (supportEmail && !/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(supportEmail)) throw new Error('Invalid WEBSITE_SUPPORT_EMAIL');
  return { origin, secure: site.protocol === 'https:', inviteHash: digest(env.WEBSITE_INVITE_CODE),
    sessionSecret: env.WEBSITE_SESSION_SECRET || randomBytes(32).toString('hex'), supportEmail,
    trustLoopbackProxy: env.WEBSITE_TRUST_LOOPBACK_PROXY === 'true', webAppURL: 'https://mrlgs.net/app/' };
}

export function createWebsite({ config, releases = createReleaseReader(), now = Date.now } = {}) {
  if (!config) config = readConfig();
  const attempts = new Map();
  const pruneAttempts = () => { for (const [key, value] of attempts) if (value.until <= now()) attempts.delete(key); };
  const sign = payload => createHmac('sha256', config.sessionSecret).update(payload).digest('base64url');
  const cookieName = config.secure ? '__Host-hgo-invite' : 'hgo-invite';
  function authorized(req) {
    const cookie = (req.headers.cookie ?? '').split(';').map(value => value.trim()).find(value => value.startsWith(`${cookieName}=`))?.slice(cookieName.length + 1);
    if (!cookie || !/^\d+\.[a-zA-Z0-9_-]{43}$/.test(cookie)) return false;
    const [expiry, signature] = cookie.split('.');
    const expected = sign(expiry);
    return timingSafeEqual(Buffer.from(signature), Buffer.from(expected)) && Number(expiry) > now() && Number(expiry) <= now() + SESSION_MS;
  }
  function rateLimit(req) {
    const remote = req.socket.remoteAddress ?? 'unknown';
    const loopback = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remote);
    const ip = config.trustLoopbackProxy && loopback && typeof req.headers['x-real-ip'] === 'string' ? req.headers['x-real-ip'] : remote;
    const time = now();
    pruneAttempts();
    let entry = attempts.get(ip);
    if (!entry) {
      if (attempts.size >= 10_000) return 600;
      entry = { count: 0, until: time + 600_000 }; attempts.set(ip, entry);
    }
    entry.count++;
    return entry.count > 8 ? Math.max(1, Math.ceil((entry.until - time) / 1000)) : 0;
  }
  const assets = new Map([['/site.css', ['text/css; charset=utf-8', 'site.css']], ['/site.js', ['text/javascript; charset=utf-8', 'site.js']], ['/logo.png', ['image/png', 'logo.png']]]);
  function send(res, status, body, type = 'text/html; charset=utf-8', extra = {}) {
    res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', ...extra }); res.end(body);
  }
  function fail(req, res, code, status, headers = {}) {
    const error = errorEnvelope(code);
    if (req.headers.accept?.includes('application/json')) send(res, status, JSON.stringify({ error }), 'application/json; charset=utf-8', headers);
    else send(res, status, renderError(config, error), undefined, headers);
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'");
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    if (config.secure) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    try {
      const url = new URL(req.url, config.origin);
      if (url.origin !== config.origin) return fail(req, res, 'HR-SITE-005', 400);
      if (req.method === 'POST' && url.pathname === '/api/invite') {
        if (req.headers.origin !== config.origin || !['application/json', 'application/x-www-form-urlencoded'].includes((req.headers['content-type'] ?? '').split(';')[0])) return fail(req, res, 'HR-SITE-005', 400);
        const retry = rateLimit(req);
        if (retry) return fail(req, res, 'HR-SITE-002', 429, { 'Retry-After': String(retry) });
        let body = ''; let length = 0;
        for await (const chunk of req) {
          length += chunk.length;
          if (length > MAX_BODY) return fail(req, res, 'HR-SITE-005', 413);
          body += chunk.toString('utf8');
        }
        let code;
        try { code = req.headers['content-type'].startsWith('application/json') ? JSON.parse(body)?.code : new URLSearchParams(body).get('code'); }
        catch { return fail(req, res, 'HR-SITE-005', 400); }
        if (typeof code !== 'string' || !/^\d{6}$/.test(code) || !timingSafeEqual(digest(code), config.inviteHash)) return fail(req, res, 'HR-SITE-001', 403);
        const expiry = String(now() + SESSION_MS);
        const cookie = `${cookieName}=${expiry}.${sign(expiry)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_MS / 1000}${config.secure ? '; Secure' : ''}`;
        if (req.headers.accept?.includes('application/json')) return send(res, 200, JSON.stringify({ verified: true }), 'application/json; charset=utf-8', { 'Set-Cookie': cookie });
        return send(res, 303, '', undefined, { 'Set-Cookie': cookie, Location: '/download/#clients' });
      }
      if (!['GET', 'HEAD'].includes(req.method)) return fail(req, res, 'HR-SITE-005', 405, { Allow: 'GET, HEAD' });
      if (assets.has(url.pathname)) {
        const [type, filename] = assets.get(url.pathname);
        const bytes = await readFile(new URL(`../public/${filename}`, import.meta.url));
        return send(res, 200, req.method === 'HEAD' ? '' : bytes, type, { 'Cache-Control': filename === 'logo.png' ? 'public, max-age=3600' : 'no-cache' });
      }
      if (url.pathname === '/healthz') return send(res, 200, req.method === 'HEAD' ? '' : '{"ok":true}', 'application/json');
      if (url.pathname.startsWith('/downloads/')) {
        const platform = url.pathname.slice('/downloads/'.length);
        if (!['android', 'desktop'].includes(platform)) return fail(req, res, 'HR-SITE-005', 404);
        if (!authorized(req)) return fail(req, res, 'HR-SITE-003', 403);
        const release = await releases.load(platform, true);
        if (release.status !== 'available') return fail(req, res, 'HR-SITE-004', 503);
        return send(res, 303, '', undefined, { Location: release.url });
      }
      if (url.pathname === '/api/releases') return send(res, 200, req.method === 'HEAD' ? '' : JSON.stringify(await releases.publicSummary()), 'application/json');
      if (url.pathname === '/robots.txt') return send(res, 200, `User-agent: *\nDisallow: /api/\nDisallow: /downloads/\nSitemap: ${config.origin}/sitemap.xml\n`, 'text/plain');
      if (url.pathname === '/sitemap.xml') return send(res, 200, `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${['/', '/download/', '/guide/', '/help/'].map(path => `<url><loc>${config.origin}${path}</loc></url>`).join('')}</urlset>`, 'application/xml');
      const pages = new Map([['/', 'home'], ['/download/', 'download'], ['/guide/', 'guide'], ['/help/', 'help']]);
      if (!pages.has(url.pathname)) return fail(req, res, 'HR-SITE-005', 404);
      const page = pages.get(url.pathname);
      const metadata = page === 'download' ? await releases.publicSummary() : {};
      return send(res, 200, req.method === 'HEAD' ? '' : renderPage(page, config, { verified: authorized(req), metadata }));
    } catch { fail(req, res, 'HR-SITE-006', 500); }
  });
  server.requestTimeout = 10_000; server.headersTimeout = 10_000;
  // Remove inactive entries even when there are no new requests. No IPs are written to disk.
  const pruneTimer = setInterval(pruneAttempts, 30_000).unref();
  server.once('close', () => clearInterval(pruneTimer));
  return server;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const startupFailure = () => {
    const error = errorEnvelope('HR-SITE-008');
    process.stderr.write(`${error.code} ${error.message} ${errorEnvelope(error.code, 'en').message} See website/README.md.\n`);
    process.exitCode = 1;
  };
  try {
    const config = readConfig();
    const port = Number(process.env.PORT ?? 4174);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
    const server = createWebsite({ config });
    server.on('error', startupFailure);
    server.listen(port, '127.0.0.1', () => process.stdout.write(`Hermes GO website listening on 127.0.0.1:${port}\n`));
  } catch {
    startupFailure();
  }
}
