// Real Chromium gesture regression for the packaged chart, using an external Playwright install.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
const [playwrightModule, chromeBinary] = process.argv.slice(2);
if (!playwrightModule || !chromeBinary) throw new Error('Usage: node web/scripts/check-chart-touch.mjs <absolute-playwright-module> <chromium-binary>');
const {chromium} = await import(pathToFileURL(playwrightModule).href);
const rows = [['Channel', 'Revenue (元)'], ...Array.from({length: 205}, (_, i) => [`C${i}`, `${i + 1}`])];
const html = `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}iframe{border:0;width:100%;height:900px}</style><iframe sandbox="allow-scripts" src="/chart.html"></iframe><script>
const frame=document.querySelector('iframe');
window.addEventListener('message',e=>{if(e.source===frame.contentWindow&&e.data.type==='ready')frame.contentWindow.postMessage(${JSON.stringify({protocol:1,type:'init',nonce:'touch-regression',rows,language:'en',theme:'light'})},'*')});
</script>`;
const assets = new URL('../../android/app/src/main/assets/table-chart/', import.meta.url);
const allowed = new Map([['/chart.html','text/html'],['/chart.css','text/css'],['/chart.js','text/javascript'],['/engine.js','text/javascript']]);
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    if (path === '/') {res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html);return;}
    if (!allowed.has(path)) {res.writeHead(404);res.end();return;}
    res.setHeader('Content-Type',allowed.get(path)+'; charset=utf-8');
    res.end(await readFile(new URL(path.slice(1), assets)));
  } catch {res.writeHead(500);res.end();}
});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
let browser;
try {
  browser = await chromium.launch({executablePath:chromeBinary,headless:true});
  const page = await browser.newPage({viewport:{width:412,height:915},isMobile:true,hasTouch:true});
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const frame = page.frameLocator('iframe');
  await frame.locator('.plot-pages').waitFor();
  const plot = frame.locator('.plot-scroll');
  const box = await plot.boundingBox();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.synthesizeScrollGesture',{x:Math.round(box.x+box.width-50),y:Math.round(box.y+100),xDistance:-220,yDistance:0,gestureSourceType:'touch',speed:600});
  const result = await plot.evaluate(node => ({offset:node.scrollLeft,visible:node.clientWidth,total:node.scrollWidth}));
  assert.ok(result.total > result.visible, 'Fixture must need horizontal scrolling');
  assert.ok(result.offset > 100, `Touch must reveal later points: ${JSON.stringify(result)}`);
  console.log(JSON.stringify({check:'packaged chart horizontal touch pan',browser:await browser.version(),...result}));
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
