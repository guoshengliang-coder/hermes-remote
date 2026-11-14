// HG-254: render the production Composer, icons and stylesheet in real browsers, without a backend.
// Usage: node web/scripts/check-composer-action.mjs <absolute-playwright-module> <output-directory> [webkit-executable]
import assert from 'node:assert/strict';
import {mkdtemp, writeFile, rm, mkdir} from 'node:fs/promises';
import {fileURLToPath, pathToFileURL} from 'node:url';
import path from 'node:path';
import {createServer} from 'vite';
const [playwrightModule, outputDirectory, webkitExecutable] = process.argv.slice(2);
if (!playwrightModule || !outputDirectory) throw new Error('Pass an external Playwright module and output directory.');
const {chromium, webkit} = await import(pathToFileURL(playwrightModule).href);
const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = await mkdtemp(path.join(root, '.composer-review-'));
const output = path.resolve(outputDirectory);
await mkdir(output, {recursive:true});
let server;
try {
  await writeFile(path.join(fixture,'index.html'), '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="root"></div><script type="module" src="./review.tsx"></script>');
  await writeFile(path.join(fixture,'review.tsx'), `
import {render} from 'preact';
import {Composer} from '../src/ui/Composer';
import {AppContext} from '../src/app/store';
import '../src/styles.css';
const t=(zh:string)=>zh;
const app={t,language:'zh',features:new Set(),device:{deviceId:'review'},client:{settled:async()=>{}}};
const result={sends:0,stops:0,lastText:''};
let nonce=0;
let props={t,language:'zh',generating:false,disabled:false,draftKey:null,chip:{label:'claude-opus-5 · 高',onClick(){}},seed:{text:'请检查 gateway 配置',nonce:++nonce},onSend(text:string){result.sends++;result.lastText=text},onInterrupt(){result.stops++}};
function draw(){render(<AppContext.Provider value={app as any}><main class="chat-page"><Composer {...props as any}/></main></AppContext.Provider>,document.getElementById('root')!)}
window.review={result,enableVoice(){app.features.add('voice-input');draw()},update(change:any){if('text' in change){props.seed={text:change.text,nonce:++nonce};delete change.text}props={...props,...change};draw()}};
draw();
`);
  server=await createServer({root,configFile:path.join(root,'vite.config.ts'),server:{host:'127.0.0.1',port:0}});
  await server.listen();
  const port=server.httpServer.address().port;
  const url=`http://127.0.0.1:${port}/app/${path.basename(fixture)}/index.html`;
  const observations=[];
  for(const [engine,type] of [['chromium',chromium],['webkit',webkit]]) {
    const browser=await type.launch({headless:true, ...(engine === 'webkit' && webkitExecutable ? {executablePath:webkitExecutable} : {})});
    try {
      for(const dark of [false,true]) for(const touch of [false,true]) {
        const context=await browser.newContext({viewport:{width:touch?390:900,height:780},hasTouch:touch,isMobile:touch,colorScheme:dark?'dark':'light'});
        const page=await context.newPage();
        const errors=[];page.on('pageerror',e=>errors.push(e.message));
        await page.goto(url);await page.waitForFunction(()=>!!window.review);
        if(dark) await page.evaluate(()=>document.documentElement.dataset.theme='dark');
        await page.locator('textarea').fill('请检查 gateway 配置');
        await page.locator('textarea').focus();
        const button=page.locator('.send-button');
        await button.waitFor();
        const actual=await button.evaluate(el=>{const c=getComputedStyle(el),b=el.getBoundingClientRect(),svg=el.querySelector('svg').getBoundingClientRect();return{background:c.backgroundColor,color:c.color,border:c.borderTopColor,borderWidth:c.borderTopWidth,width:b.width,height:b.height,iconWidth:svg.width,iconHeight:svg.height,path:el.querySelector('path')?.getAttribute('d'),stroke:el.querySelector('path')?.getAttribute('stroke-width')}});
        assert.equal(actual.background,dark?'rgb(30, 35, 43)':'rgb(24, 28, 36)');
        assert.equal(actual.color,'rgb(255, 255, 255)');
        assert.equal(actual.border,dark?'rgb(58, 64, 73)':'rgba(0, 0, 0, 0)');
        assert.equal(actual.borderWidth,'1px');
        assert.equal(actual.width,48);assert.equal(actual.height,48);assert.equal(actual.iconWidth,24);assert.equal(actual.iconHeight,24);
        assert.equal(actual.stroke,'1.7');assert.equal(actual.path,'M12 19V5M5 12l7-7 7 7');
        await page.locator('.composer-wrap').screenshot({path:path.join(output,`${engine}-${dark?'dark':'light'}-${touch?'touch':'mouse'}-send.png`)});
        if(touch) await button.tap();else await button.click();
        assert.equal(await page.evaluate(()=>window.review.result.sends),1);
        await page.locator('textarea').focus();await page.locator('textarea').fill('');
        assert.equal(await button.isDisabled(),true);
        assert.equal(await button.evaluate(el=>getComputedStyle(el).backgroundColor),'rgba(0, 0, 0, 0)');
        assert.equal(await button.evaluate(el=>getComputedStyle(el).borderTopColor),'rgba(0, 0, 0, 0)');
        assert.equal(await button.evaluate(el=>getComputedStyle(el).opacity),'0.55');
        await page.locator('.composer-wrap').screenshot({path:path.join(output,`${engine}-${dark?'dark':'light'}-${touch?'touch':'mouse'}-empty.png`)});
        await page.evaluate(()=>window.review.update({generating:true}));
        await page.locator('.send-button.stop').waitFor();
        assert.equal(await button.getAttribute('aria-label'),'停止');
        assert.equal(await button.locator('rect').getAttribute('fill'),'currentColor');
        assert.equal(await button.evaluate(el=>getComputedStyle(el).backgroundColor),actual.background);
        await page.locator('.composer-wrap').screenshot({path:path.join(output,`${engine}-${dark?'dark':'light'}-${touch?'touch':'mouse'}-stop.png`)});
        if(touch) await button.tap();else await button.click();
        assert.equal(await page.evaluate(()=>window.review.result.stops),1);
        assert.equal(await page.evaluate(()=>window.review.result.sends),1);
        await page.evaluate(()=>window.review.update({generating:false}));
        await page.locator('textarea').fill('keyboard');await page.locator('textarea').press('Enter');
        assert.equal(await page.evaluate(()=>window.review.result.sends),touch?1:2);
        await page.locator('textarea').fill('shift');await page.locator('textarea').press('Shift+Enter');
        assert.equal(await page.evaluate(()=>window.review.result.sends),touch?1:2);
        await page.locator('textarea').dispatchEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true});
        assert.equal(await page.evaluate(()=>window.review.result.sends),touch?1:2);
        // Native keyboard focus retains the existing visible focus outline.
        await page.locator('textarea').focus();
        // WebKit's macOS full-keyboard-access preference can skip buttons in Tab order.
        await page.keyboard.press('Tab');
        await button.focus();
        assert.equal(await button.evaluate(el=>el===document.activeElement),true);
        assert.equal(await button.evaluate(el=>getComputedStyle(el).outlineStyle),'solid');
        // Zoom text without shrinking the primary action; the long model stays in its own slot.
        await page.evaluate(()=>{document.documentElement.style.fontSize='20.8px';window.review.update({chip:{label:'long-model-'.repeat(40),onClick(){}}})});
        assert.ok(Math.abs(await button.evaluate(el=>el.getBoundingClientRect().width)-62.4)<.1);
        assert.ok(await button.evaluate(el=>el.getBoundingClientRect().right<=innerWidth));
        // Enter the real voice layout with a synthetic permission result; never open a microphone.
        const voiceDraft=await page.locator('textarea').inputValue();
        await page.evaluate(()=>{
          Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{getUserMedia:async()=>{window.review.gumCalls=(window.review.gumCalls||0)+1;return{getTracks:()=>[{stop(){}}]}}}});
          window.review.enableVoice();
        });
        const mic=page.locator('.composer-voice');
        if(touch) await mic.tap();else await mic.click();
        await page.locator('.voice-mode').waitFor({timeout:3000}).catch(async error=>{console.log(JSON.stringify({engine,touch,dark,gumCalls:await page.evaluate(()=>window.review.gumCalls),composer:await page.locator('.composer').getAttribute('class'),errors,body:(await page.locator('body').innerText()).slice(0,1500)}));throw error;});
        await page.evaluate(()=>window.review.update({generating:true}));
        const voiceStop=page.locator('.voice-mode .send-button.stop');
        await voiceStop.waitFor();
        const voiceStyle=await voiceStop.evaluate(el=>{const c=getComputedStyle(el);return{fill:c.backgroundColor,ink:c.color,border:c.borderTopColor,width:c.borderTopWidth}});
        assert.deepEqual(voiceStyle,{fill:actual.background,ink:actual.color,border:actual.border,width:'1px'});
        assert.equal(await page.locator('.voice-hold').isDisabled(),true);
        if(touch) await voiceStop.tap();else await voiceStop.click();
        assert.equal(await page.evaluate(()=>window.review.result.stops),2);
        assert.equal(await page.evaluate(()=>window.review.result.sends),touch?1:2);
        await page.evaluate(()=>window.review.update({generating:false}));
        if(touch) await mic.tap();else await mic.click();
        await page.locator('textarea').waitFor();
        assert.equal(await page.locator('textarea').inputValue(),voiceDraft);
        // Hold only fixture image decoding to observe the production preparing state.
        await page.evaluate(()=>{window.createImageBitmap=()=>new Promise(()=>{});});
        await page.locator('input[accept="image/*"]:not([capture])').setInputFiles(fileURLToPath(new URL('../../android/app/src/main/ic_launcher-playstore.png', import.meta.url)));
        await button.locator('.spinner').waitFor();
        assert.equal(await button.isDisabled(),true);
        assert.equal(await button.evaluate(el=>getComputedStyle(el).backgroundColor),'rgba(0, 0, 0, 0)');
        await page.locator('.composer-wrap').screenshot({path:path.join(output,`${engine}-${dark?'dark':'light'}-${touch?'touch':'mouse'}-preparing.png`)});
        assert.deepEqual(errors,[]);
        console.log(JSON.stringify({engine,theme:dark?'dark':'light',input:touch?'touch':'mouse',passed:true}));
        observations.push({engine,version:browser.version(),theme:dark?'dark':'light',input:touch?'touch':'mouse',...actual,actions:'send/stop/voice-stop/disabled/preparing/hairline/keyboard/IME/focus/large-text passed'});
        await context.close();
      }
    } finally {await browser.close();}
  }
  await writeFile(path.join(output,'results.json'),JSON.stringify(observations,null,2)+'\n');
  console.log(JSON.stringify({cases:observations.length,engines:['chromium','webkit'],checks:'passed',output}));
} finally {await server?.close();await rm(fixture,{recursive:true,force:true});}
