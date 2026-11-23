// HG-261/262: production ChatPage/SessionRow + production CSS, isolated fake transport.
// node web/scripts/check-profile-layout.mjs <absolute Playwright module> <output directory> [webkit executable]
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createServer } from 'vite';
const [playwrightModule, outputDirectory, webkitExecutable] = process.argv.slice(2);
if (!playwrightModule || !outputDirectory) throw Error('Pass Playwright module and output directory');
const { chromium, webkit } = await import(pathToFileURL(path.resolve(playwrightModule)).href);
const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = await mkdtemp(path.join(root, '.profile-layout-'));
const output = path.resolve(outputDirectory);
let server;
try {
  await mkdir(output, { recursive: true });
  const mock = path.join(fixture, 'session.ts');
  await writeFile(mock, `export class ChatSession {
    constructor(o) { this.o=o;this.storedSessionId=o.storedSessionId;window.qaDispatch=o.dispatch; }
    start(){this.o.dispatch({type:'history',rows:[{id:1,role:'user',content:'本周有什么重点？',timestamp:1},{id:2,role:'assistant',content:'先确认需求，再验证关键场景。',timestamp:2}]});this.o.dispatch({type:'connection',state:'ready'});}
    dispose(){} async reasoning(){return null;} async processes(){return [];}
  }`);
  await writeFile(path.join(fixture, 'index.html'), '<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width,initial-scale=1"><div id="root"></div><script type="module" src="./fixture.tsx"></script></html>');
  await writeFile(path.join(fixture, 'fixture.tsx'), `
import {render} from 'preact';import {AppContext} from '../src/app/store';import {initialInbox} from '../src/app/inbox';
import {ChatPage} from '../src/ui/ChatPage';import {SessionRow} from '../src/ui/SessionRow';import '../src/styles.css';
const q=new URLSearchParams(location.search),project=q.has('project'),long=q.has('long'),en=q.has('en');
const role=long?(en?'Research assistant with a very long role name':'一个需要省略但不能抢占标题和项目空间的很长角色名称'):'芯芯';
const session={id:'qa',profile:'default',title:'本周工作安排',model:'gpt-6.2-sol',...(project?{cwd:'/qa/hermes-remote',git_branch:'main'}:{})};
const app={t:(zh,english)=>en?english:zh,language:en?'en':'zh',account:{id:'qa'},device:{deviceId:'qa'},client:{},features:new Set(),profileCatalog:{default:role},sessions:[session],inbox:initialInbox,markSeen(){},reportLiveQuestion(){},isPinned(){return false},setChatSearchSeed(){},flash(){}};
render(<AppContext.Provider value={app}>{q.has('list')?<div class="page"><SessionRow session={session} now={0} pinned draft onOpen={()=>{}}/><SessionRow session={{id:'role-only',profile:'default',title:'只有角色'}} now={0} onOpen={()=>{}}/><SessionRow session={{id:'branch',profile:'default',title:'项目内分支',git_branch:'main',model:'glm-5.3'}} now={0} inProject onOpen={()=>{}}/></div>:<ChatPage sessionId="qa"/>}</AppContext.Provider>,document.getElementById('root'));`);
  server = await createServer({ root, configFile:path.join(root,'vite.config.ts'), plugins:[{name:'qa-profile-transport',enforce:'pre',resolveId(id){if(id==='../chat/session')return mock;}}],server:{host:'127.0.0.1',port:0} });
  await server.listen();
  const url=`http://127.0.0.1:${server.httpServer.address().port}/app/${path.basename(fixture)}/index.html`;
  const observations=[];
  for (const [engine,type] of [['chromium',chromium],['webkit',webkit]]) {
    const browser=await type.launch({headless:true,...(engine==='webkit' && webkitExecutable ? {executablePath:webkitExecutable} : {})});
    try {
      const page=await browser.newPage({viewport:{width:390,height:844}});
      const errors=[];page.on('pageerror',e=>errors.push(e.message));
      for (const width of [320,390,900]) for (const theme of ['light','dark']) for (const font of [16,21]) for (const project of [false,true]) for (const language of ['zh','en']) {
        await page.setViewportSize({width,height:844});
        await page.goto(url+'?long=1'+(project?'&project=1':'')+(language==='en'?'&en=1':''));
        await page.locator('.chat-title .role-owner-name').waitFor();
        await page.evaluate(({theme,font})=>{document.documentElement.dataset.theme=theme;document.documentElement.style.fontSize=font+'px';},{theme,font});
        await page.evaluate(()=>document.fonts.ready);
        const geometry=async()=>page.evaluate(()=>{
          const box=s=>document.querySelector(s).getBoundingClientRect().toJSON();
          return {title:box('.chat-title h1'),role:box('.role-owner-name'),action:box('[aria-label="新会话"],[aria-label="New chat"]'),scroll:document.documentElement.scrollWidth,width:innerWidth,
            project:document.querySelector('.chat-workspace')?.getBoundingClientRect().toJSON(),
            status:(()=>{const el=document.querySelector('.chat-status');if(!el)return null;const range=document.createRange();range.selectNodeContents(el);return {box:el.getBoundingClientRect().toJSON(),text:range.getBoundingClientRect().toJSON(),overflow:getComputedStyle(el).overflowX};})()};
        });
        const actual=await geometry();
        const label=`${engine}-${width}-${theme}-${font}-${project?'project':'plain'}-${language}`;
        assert(actual.role.top>=actual.title.bottom-1,label+' role above subtitle');
        assert(actual.role.right<=actual.action.left+1,label+' role overlaps actions');
        assert(actual.scroll<=actual.width+1,label+' viewport overflow');
        if(project)assert(actual.project.width>15,label+' project was squeezed away');
        await page.evaluate(()=>window.qaDispatch({type:'connection',state:'reconnecting'}));
        await page.locator('.chat-status').waitFor();
        const reconnect=await geometry();assert(reconnect.role.top>=reconnect.title.bottom-1,label+' reconnect moved role');
        assert((await page.locator('.chat-status').textContent()).includes(language==='en'?'Reconnecting':'恢复'));
        if(width===320 && font===21 && language==='en' && !project)await page.screenshot({path:path.join(output,engine+'-reconnect-narrow.png')});
        assert(reconnect.status.box.right<=reconnect.action.left+1,label+' reconnect status box overlaps actions');
        assert(reconnect.status.text.right<=reconnect.status.box.right+1 || reconnect.status.overflow==='hidden',label+' reconnect text escapes its slot');
        await page.evaluate(()=>window.qaDispatch({type:'connection',state:'ready'}));
        if(width===390 && font===16)await page.screenshot({path:path.join(output,label+'.png')});
        observations.push({label,actual,reconnect});
      }
      await page.setViewportSize({width:390,height:844});
      await page.goto(url+'?list=1&project=1');await page.locator('.row-role').first().waitFor();
      const dots=await page.locator('.row-subline').evaluateAll(lines=>lines.map(line=>[...line.children].filter(n=>n.textContent==='·').length));
      assert.deepEqual(dots,[2,0,2]);
      await page.screenshot({path:path.join(output,engine+'-list-light.png')});
      await page.evaluate(()=>document.documentElement.dataset.theme='dark');
      await page.screenshot({path:path.join(output,engine+'-list-dark.png')});
      assert.deepEqual(errors,[],engine+' page errors');
    } finally {await browser.close();}
  }
  await writeFile(path.join(output,'observations.json'),JSON.stringify(observations,null,2));
  console.log(`PROFILE_LAYOUT_BROWSER_OK ${observations.length} layouts; role position, reconnects, projects, overflow and separators passed`);
} finally {await server?.close();await rm(fixture,{recursive:true,force:true});}
