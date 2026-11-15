// HG-256: actual FileCard/Markdown components and production CSS, not happy-dom geometry.
// Usage: node web/scripts/check-chat-overflow.mjs <absolute-playwright-module> <output-directory> [webkit-executable]
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createServer } from "vite";

const [playwrightModule, outputDirectory, webkitExecutable] = process.argv.slice(2);
if (!playwrightModule || !outputDirectory) throw new Error("Pass a Playwright module and output directory.");
const { chromium, webkit } = await import(pathToFileURL(path.resolve(playwrightModule)).href);
const root = fileURLToPath(new URL("../", import.meta.url));
const fixture = await mkdtemp(path.join(root, ".chat-overflow-"));
const output = path.resolve(outputDirectory);
const observations = [];
let server;
let outputReady = false;
try {
  await mkdir(output, { recursive: true });
  outputReady = true;
  await writeFile(path.join(fixture, "index.html"), '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="root"></div><script type="module" src="./review.tsx"></script>');
  await writeFile(path.join(fixture, "review.tsx"), `
import { render } from 'preact';
import { AppContext } from '../src/app/store';
import { FileCard } from '../src/ui/Media';
import { Markdown } from '../src/ui/Markdown';
import '../src/styles.css';
const names = ['2026-W46-示例会议纪要索引与决策摘录-20261114.md', 'attachment-' + 'a'.repeat(100) + '.md', 'a.md'];
const prose = '普通聊天内容，应按可用宽度自动换行。'.repeat(60);
const code = '\u0060\u0060\u0060text\\n' + 'wide_code_'.repeat(80) + '\\n\u0060\u0060\u0060';
const table = '| ' + Array.from({length: 12}, (_, i) => 'Column ' + i).join(' | ') + ' |\\n| ' + Array(12).fill('---').join(' | ') + ' |\\n| ' + Array(12).fill('1234567890').join(' | ') + ' |';
const source = prose + '\\n\\n' + code + '\\n\\n' + table;
const language = new URLSearchParams(location.search).get('language') === 'en' ? 'en' : 'zh';
const app = { t: (zh: string, en: string) => language === 'en' ? en : zh, language, flash() {}, device: {deviceId: 'fixture'} };
render(<AppContext.Provider value={app as any}>
  <div class="page chat-page">
    <header class="topbar"><div class="topbar-row">布局回归</div></header>
    <div class="messages"><div class="messages-inner">
      <div class="turn turn-assistant"><Markdown source={source} />
        {names.map(name => <FileCard key={name} attachment={{name, path: '/fixture/' + name, kind: 'download'}} />)}
      </div>
      <div class="turn turn-user">
        {names.map(name => <FileCard key={name} attachment={{name, path: '/fixture/' + name, kind: 'download'}} />)}
      </div>
    </div></div>
    <footer class="chat-bottom">输入消息…</footer>
  </div>
</AppContext.Provider>, document.getElementById('root')!);
`);
  server = await createServer({ root, configFile: path.join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0 } });
  await server.listen();
  const url = `http://127.0.0.1:${server.httpServer.address().port}/app/${path.basename(fixture)}/index.html`;
  for (const [engine, type] of [["chromium", chromium], ["webkit", webkit]]) {
    const browser = await type.launch({ headless: true, ...(engine === "webkit" && webkitExecutable ? { executablePath: webkitExecutable } : {}) });
    try {
      const context = await browser.newContext({ viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true });
      for (const scenario of [{ language: "zh", safe: 0 }, { language: "en", safe: 24 }]) {
        const page = await context.newPage();
        const errors = [];
        page.on("pageerror", error => errors.push(error.message));
        await page.goto(`${url}?language=${scenario.language}`);
        await page.locator('.markdown[aria-busy="false"] pre').waitFor();
        await page.evaluate(() => document.fonts.ready);
        await page.evaluate(safe => {
          document.documentElement.style.setProperty("--safe-left", safe + "px");
          document.documentElement.style.setProperty("--safe-right", safe + "px");
        }, scenario.safe);
        for (const width of [320, 360, 390, 412, 900]) for (const fontSize of [16, 20, 24]) for (const theme of ["light", "dark"]) {
          await page.setViewportSize({ width, height: 800 });
          await page.evaluate(({ fontSize, theme }) => {
            document.documentElement.style.fontSize = fontSize + "px";
            document.documentElement.dataset.theme = theme;
          }, { fontSize, theme });
          const actual = await page.evaluate(() => {
            const port = document.querySelector(".messages");
            port.scrollLeft = 100;
            port.scrollTop = 100;
            const cards = Array.from(document.querySelectorAll(".file-card")).map((card, index) => {
              const box = card.getBoundingClientRect();
              const parent = card.parentElement.getBoundingClientRect();
              const name = card.querySelector(".file-name");
              let naturalWidth = null;
              if (index % 3 === 2) {
                const natural = card.cloneNode(true);
                natural.style.cssText = "position:fixed;visibility:hidden;width:max-content;max-width:none";
                document.body.appendChild(natural);
                naturalWidth = natural.getBoundingClientRect().width;
                natural.remove();
              }
              return { left: box.left, right: box.right, width: box.width, parentLeft: parent.left, parentRight: parent.right, parentWidth: parent.width, naturalWidth, nameWidth: name.clientWidth, nameScroll: name.scrollWidth, ellipsis: getComputedStyle(name).textOverflow };
            });
            const local = [".markdown pre", ".table-scroll"].map(selector => {
              const el = document.querySelector(selector);
              el.scrollLeft = 100;
              return { selector, width: el.clientWidth, scroll: el.scrollWidth, left: el.scrollLeft, overflowX: getComputedStyle(el).overflowX };
            });
            return { width: port.clientWidth, scroll: port.scrollWidth, left: port.scrollLeft, top: port.scrollTop, overflowY: getComputedStyle(port).overflowY, cards, local };
          });
          const label = `${engine} ${scenario.language} safe=${scenario.safe} ${width}px ${fontSize}px ${theme}`;
          observations.push({ engine, ...scenario, width, fontSize, theme, actual });
          if (width === 320 && fontSize === 16 && theme === "light" || width === 360 && fontSize === 20) {
            await page.evaluate(() => { const el = document.querySelector(".messages"); el.scrollTop = el.scrollHeight; });
            await page.screenshot({ path: path.join(output, `${engine}-${scenario.language}-${width}-${fontSize}-${theme}.png`) });
          }
          assert.equal(actual.scroll, actual.width, `${label}: transcript overflows horizontally`);
          assert.equal(actual.left, 0, `${label}: transcript can pan horizontally`);
          assert.ok(["auto", "scroll"].includes(actual.overflowY), `${label}: vertical scroll must allow user input`);
          assert.ok(actual.top > 0, `${label}: vertical scrolling must remain usable`);
          assert.equal(actual.cards.length, 6, `${label}: both message roles have their attachments`);
          actual.cards.forEach((card, i) => {
            assert.ok(card.left >= card.parentLeft - 1 && card.right <= card.parentRight + 1, `${label}: card ${i} leaves its parent`);
            assert.ok(card.width <= 22 * fontSize + 1, `${label}: desktop cap`);
            assert.equal(card.ellipsis, "ellipsis", `${label}: filename ellipsis`);
            if (i % 3 < 2) assert.ok(card.nameScroll > card.nameWidth, `${label}: long filename must truncate`);
            else assert.ok(Math.abs(card.width - Math.min(card.naturalWidth, card.parentWidth, 22 * fontSize)) <= 1, `${label}: short filename uses its natural width when space permits`);
          });
          for (const local of actual.local) {
            assert.ok(["auto", "scroll"].includes(local.overflowX), `${label}: ${local.selector} must allow user scrolling`);
            assert.ok(local.scroll > local.width && local.left > 0, `${label}: ${local.selector} keeps its own horizontal scroll`);
          }
          assert.deepEqual(errors, [], `${label}: browser errors`);
        }
        await page.close();
      }
      await context.close();
    } finally {
      await browser.close();
    }
  }
  console.log(`CHAT_OVERFLOW_OK: ${observations.length} Chromium/WebKit layouts; transcript vertical-only, local code/table scrolling retained.`);
} finally {
  try {
    if (outputReady) await writeFile(path.join(output, "observations.json"), JSON.stringify(observations, null, 2));
  } finally {
    try {
      await server?.close();
    } finally {
      await rm(fixture, { recursive: true, force: true });
    }
  }
}
