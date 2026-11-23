import assert from "node:assert/strict";
import { createServer } from "node:http";
import { copyFile, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "vite";

const [modulePath, name = "chromium", executablePath] = process.argv.slice(2);
if (!modulePath || !["chromium", "webkit"].includes(name)) throw Error("Pass Playwright and a browser.");
const playwright = await import(pathToFileURL(path.resolve(modulePath)).href);
const root = fileURLToPath(new URL("../", import.meta.url));
const fixture = await mkdtemp(path.join(root, ".inline-preview-"));
const dist = path.join(fixture, "dist");
let browser, server;
try {
  const html = path.join(fixture, "index.html");
  await copyFile(new URL("./fixtures/inline-preview.html", import.meta.url), html);
  await writeFile(path.join(fixture, "fixture.tsx"), [
    "import { render } from 'preact';",
    "import { AppContext } from '../src/app/store';",
    "import { InlinePreview } from '../src/ui/InlinePreview';",
    "import '../src/styles.css';",
    'const svg = \'<svg width="850" height="160"><rect width="850" height="160" fill="var(--accent)"/><text x="790" y="80">Last</text></svg>\';',
    "const client = {deviceApi: async (_, __, file) => new Response(file.includes('script.html') ? '<script>void 0</script>' + svg : svg)};",
    "const app = { client, device: {deviceId: 'fixture'}, language: 'en', t: (_, en) => en };",
    'render(<AppContext.Provider value={app as any}><main class="turn turn-assistant"><InlinePreview target="/wide.html" height={200}/><InlinePreview target="/script.html" height={200}/></main></AppContext.Provider>, document.getElementById("root")!);',
  ].join("\n"));
  await build({ root, configFile: path.join(root, "vite.config.ts"), build: {
    outDir: dist, rollupOptions: { input: html },
  } });
  const csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; media-src blob:; connect-src 'self'; font-src 'self'; worker-src 'self'; frame-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
  server = createServer(async (request, response) => {
    const route = request.url?.split("?")[0] ?? "";
    const asset = route.match(/^\/app\/assets\/([\w.-]+)$/)?.[1];
    if (route !== "/app/" && !asset) { response.writeHead(404).end(); return; }
    const file = asset ? path.join(dist, "assets", asset) : path.join(dist, path.basename(fixture), "index.html");
    const types = { ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2" };
    try {
      const bytes = await readFile(file);
      response.writeHead(200, { "content-type": types[path.extname(file)] ?? "text/html", "content-security-policy": csp }).end(bytes);
    } catch {
      response.writeHead(404).end();
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  browser = await playwright[name].launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 360, height: 720 }, isMobile: true, hasTouch: true });
  await page.goto("http://127.0.0.1:" + server.address().port + "/app/");
  const iframe = page.locator("iframe");
  await iframe.waitFor();
  await page.getByText("Download this chart to view it.").waitFor();
  assert.equal(await iframe.getAttribute("sandbox"), "");
  const frame = page.frameLocator("iframe");
  const bounds = await frame.locator("svg").evaluate(svg => {
    window.scrollTo(document.documentElement.scrollWidth, 0);
    return { width: window.innerWidth, left: window.scrollX, fill: getComputedStyle(svg.querySelector("rect")).fill };
  });
  assert(bounds.width <= 360 && bounds.left > 400, JSON.stringify(bounds));
  assert.equal(bounds.fill, "rgb(139, 128, 232)");
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 360);
  await page.evaluate(() => { document.documentElement.dataset.theme = "dark"; });
  await frame.locator('rect[fill="#ada3ff"]').waitFor();
  console.log(name + " verified iframe overflow, shared colors, strict CSP and download fallback");
} finally {
  await browser?.close();
  server?.close();
  await rm(fixture, { recursive: true, force: true });
}
