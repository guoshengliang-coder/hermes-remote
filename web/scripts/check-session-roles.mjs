// Real Chromium/production-component QA with an isolated fake transport; no account or service access.
// node web/scripts/check-session-roles.mjs <chrome executable> <output directory>
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer, mergeConfig } from 'vite';
import base from '../vite.config.ts';
const [chrome, outputDirectory] = process.argv.slice(2);
if (!chrome || !outputDirectory) throw Error('Pass Chrome executable and output directory');
const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = await mkdtemp(path.join(root, '.session-roles-'));
const profile = await mkdtemp(path.join(tmpdir(), 'hg260-chrome-'));
const output = path.resolve(outputDirectory);
let browser, server, socket;
try {
  await mkdir(output, {recursive:true});
  const mock = path.join(fixture,'session.ts');
  await writeFile(mock, `export class ChatSession {
    storedSessionId = null;
    constructor(o) { this.o = o; window.latestChatOptions = o; }
    start() { this.o.dispatch({type:'history',rows:[]}); this.o.dispatch({type:'connection',state:'ready'}); }
    dispose() {} async reasoning() { return null; } async processes() { return []; }
  }`);
  await writeFile(path.join(fixture,'index.html'), '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><div id="root"></div><script type="module" src="./fixture.tsx"></script>');
  await writeFile(path.join(fixture,'fixture.tsx'), `
import {render} from 'preact'; import {useState} from 'preact/hooks';
import {AppContext} from '../src/app/store'; import {initialInbox} from '../src/app/inbox';
import {ChatPage} from '../src/ui/ChatPage'; import {RoleSheet} from '../src/ui/RoleSheet';
import {toggleRole} from '../src/app/roleFilter'; import '../src/styles.css';
const app = {t:(zh,en)=>zh,language:'zh',account:{id:'qa'},device:{deviceId:'qa'},client:{},features:new Set(['model-select']),
profileCatalog:{default:'助手',work:'工作角色'},sessions:[{id:'qa-project',profile:'work',cwd:'/qa/project'}],inbox:initialInbox,
markSeen(){},reportLiveQuestion(){},isPinned(){return false},setChatSearchSeed(){},flash(){}};
function Fixture(){ const [selection,set]=useState(null); const filter=new URLSearchParams(location.search).has('filter');
return <AppContext.Provider value={app}><ChatPage sessionId={null}/>{filter&&<RoleSheet names={['default','work']} label={name=>app.profileCatalog[name]}
title="筛选角色" closeLabel="关闭" multiple selected={name=>selection===null||selection.includes(name)} allLabel="全部角色" onAll={()=>set(null)}
onToggle={name=>set(previous=>toggleRole(previous,['default','work'],name))} onClose={()=>{}}/>}</AppContext.Provider>}
render(<Fixture/>, document.getElementById('root'));`);
  server = await createServer(mergeConfig(base, {root, configFile:false, plugins:[{name:'qa-transport',enforce:'pre',resolveId(id){if(id==='../chat/session') return mock;}}],server:{host:'127.0.0.1',port:0}}));
  await server.listen();
  browser = spawn(chrome, ['--headless','--no-first-run','--no-default-browser-check','--remote-debugging-port=0',`--user-data-dir=${profile}`,'about:blank'], {stdio:'ignore'});
  let port;
  for(let i=0;i<100;i++){try{port=(await readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];break;}catch{await new Promise(r=>setTimeout(r,100));}}
  assert(port,'Chrome did not start');
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'})).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject});
  let sequence=0; const pending=new Map();
  socket.onmessage=event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}};
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
  await call('Page.enable'); await call('Runtime.enable');
  const observations=[];
  for(const width of [320,390,900]) for(const theme of ['light','dark']) for(const font of [16,21]) {
    await call('Emulation.setDeviceMetricsOverride',{width,height:844,deviceScaleFactor:1,mobile:true});
    await call('Page.navigate',{url:`http://127.0.0.1:${server.httpServer.address().port}/app/${path.basename(fixture)}/index.html`});
    for(let i=0;i<100;i++){if(await evaluate('!!document.querySelector(".role-switch")'))break;await new Promise(r=>setTimeout(r,50));}
    await evaluate(`document.documentElement.dataset.theme=${JSON.stringify(theme)}; document.documentElement.style.fontSize='${font}px'; document.fonts.ready`);
    await evaluate(`const input=document.querySelector('textarea'); input.value='切换保留草稿';input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.role-switch').click()`);
    await new Promise(r=>setTimeout(r,30));
    await evaluate(`document.querySelectorAll('.role-option')[1].click()`);
    await new Promise(r=>setTimeout(r,30));
    assert.equal(await evaluate(`document.querySelector('textarea').value`),'切换保留草稿');
    await evaluate(`document.querySelector('[aria-label="所属项目，点按移动"]').click()`);
    await new Promise(r=>setTimeout(r,30));
    await evaluate(`Array.from(document.querySelectorAll('.sheet-action')).find(button=>button.textContent.includes('project')).click()`);
    await new Promise(r=>setTimeout(r,30));
    await evaluate(`document.querySelector('textarea').focus()`);
    await new Promise(r=>setTimeout(r,250));
    const actual=await evaluate(`({role:window.latestChatOptions.profile,cwd:window.latestChatOptions.cwd,draft:document.querySelector('textarea').value,
      width:innerWidth,scroll:document.documentElement.scrollWidth,send:document.querySelector('.send-button')?.getBoundingClientRect().toJSON()})`);
    assert.equal(actual.role,'work'); assert.equal(actual.cwd,'/qa/project'); assert.equal(actual.draft,'切换保留草稿');
    assert(actual.scroll<=actual.width+1,JSON.stringify(actual));
    const name=`chat-${width}-${theme}-${font}`;observations.push({name,...actual});
    if(width===390 && font===21){const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(output,name+'.png'),Buffer.from(shot.data,'base64'));}
  }
  await call('Page.navigate',{url:`http://127.0.0.1:${server.httpServer.address().port}/app/${path.basename(fixture)}/index.html?filter=1`});
  for(let i=0;i<100;i++){if(await evaluate('document.querySelectorAll("[role=checkbox]").length===2'))break;await new Promise(r=>setTimeout(r,50));}
  await evaluate(`document.querySelectorAll('[role=checkbox]')[0].click();document.querySelectorAll('[role=checkbox]')[1].click()`);
  await new Promise(r=>setTimeout(r,30));
  assert.equal(await evaluate(`document.querySelectorAll('[aria-checked=true]').length`),0);
  await evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='全部角色').click()`);
  await new Promise(r=>setTimeout(r,30));
  assert.equal(await evaluate(`document.querySelectorAll('[aria-checked=true]').length`),2);
  await new Promise(r=>setTimeout(r,250));
  const shot=await call('Page.captureScreenshot',{format:'png'});await writeFile(path.join(output,'role-filter.png'),Buffer.from(shot.data,'base64'));
  await writeFile(path.join(output,'observations.json'),JSON.stringify(observations,null,2));
  console.log('SESSION_ROLES_BROWSER_OK '+observations.length+' layouts; role filter none/all passed');
} finally {
  socket?.close(); browser?.kill('SIGTERM'); await server?.close();
  await rm(fixture,{recursive:true,force:true});
  // Chrome may still be flushing its own disposable profile; keep it outside the repository.
}
