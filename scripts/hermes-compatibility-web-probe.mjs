#!/usr/bin/env node
// Actual packaged Web app; only synthetic cookies and a loopback HTTPS proxy.
import assert from 'node:assert/strict';
import {createServer} from 'node:https';
import {request as httpRequest} from 'node:http';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {join} from 'node:path';
import {PROMPT_TEXT,PROMPT_ANSWER} from './hermes-compatibility-contract-probe.mjs';
import {CLARIFY_PROMPT,CLARIFY_QUESTION,CLARIFY_ANSWER,CLARIFY_REPLY} from './hermes-compatibility-clarify-probe.mjs';
export const WEB_SCOPE='packaged-web-text-clarify-reload-v1';
export const WEB_CHECKS=['browserLaunched','webAuthenticated','sessionRefreshed','deviceReady','csrfEnforced','textSubmitted',
  'textRendered','textHistoryReload','clarifySubmitted','clarifyQuestionRendered','clarifyAnswered',
  'clarifyRendered','clarifyHistoryReload','webSocketAuthenticated','browserClosed','proxyClosed'];

export const permittedSocketPath=path=>typeof path==='string'&&/^\/v2\/devices\/[A-Za-z0-9_-]+\/ws$/.test(path);
export function permittedProxyPath(path){
  return typeof path==='string'&&path.startsWith('/')&&!path.startsWith('//')
    &&(!path.startsWith('/v2/web/auth/')||path==='/v2/web/auth/refresh');
}

export async function probeWeb(input){
  assert.ok(Number.isInteger(input.port)&&input.port>0&&input.port<=65535);
  assert.ok(Number.isInteger(input.gatewayPort)&&input.gatewayPort>0&&input.gatewayPort<=65535);
  assert.equal(input.origin,`https://127.0.0.1:${input.port}`);
  assert.match(input.session?.token??'',/^hga_[A-Za-z0-9_-]{43}$/);
  assert.match(input.session?.refreshToken??'',/^hgr_[A-Za-z0-9_-]{43}$/);
  const checks=Object.fromEntries(WEB_CHECKS.map(key=>[key,false]));
  const sockets=new Set(),screenshots={};let browser,page,version,stage='prepare',deviceId;
  const proxy=createServer({key:await readFile(input.key),cert:await readFile(input.cert)},(request,response)=>{
    // Authentication is seeded, never sent through a real email provider by this fixture.
    if(!permittedProxyPath(request.url)){
      response.writeHead(403);response.end();return;
    }
    const upstream=httpRequest({host:'127.0.0.1',port:input.gatewayPort,path:request.url,method:request.method,headers:request.headers},reply=>{
      response.writeHead(reply.statusCode,reply.headers);reply.pipe(response);
    });
    upstream.on('error',()=>{if(!response.headersSent)response.writeHead(502);response.end();});
    upstream.setTimeout(15_000,()=>upstream.destroy());request.pipe(upstream);
  });
  proxy.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  proxy.on('upgrade',(request,socket,head)=>{
    if(!permittedSocketPath(request.url)){socket.destroy();return;}
    const upstream=httpRequest({host:'127.0.0.1',port:input.gatewayPort,path:request.url,method:'GET',headers:request.headers});
    upstream.on('upgrade',(reply,peer,rest)=>{
      socket.write('HTTP/1.1 101 Switching Protocols\r\n'+Object.entries(reply.headers).map(([k,v])=>`${k}: ${v}\r\n`).join('')+'\r\n');
      if(rest.length)socket.write(rest);if(head.length)peer.write(head);
      socket.pipe(peer);peer.pipe(socket);peer.on('error',()=>socket.destroy());socket.on('error',()=>peer.destroy());
      socket.on('close',()=>peer.destroy());peer.on('close',()=>socket.destroy());
    });
    upstream.on('response',reply=>{reply.resume();socket.destroy();});
    upstream.on('error',()=>socket.destroy());upstream.end();
  });
  const capture=async name=>{
    const bytes=await page.screenshot({path:join(input.workspace,`${name}.png`),fullPage:true,animations:'disabled'});
    screenshots[name]=createHash('sha256').update(bytes).digest('hex');
  };
  try{
    await new Promise((resolve,reject)=>{proxy.once('error',reject);proxy.listen(input.port,'127.0.0.1',resolve);});
    const {chromium}=await import(pathToFileURL(input.module).href);
    stage='browser';browser=await chromium.launch({executablePath:input.wrapper,headless:true,timeout:30_000});
    version=browser.version();assert.equal(version,input.expectedBrowserVersion);assert.match(version,/^[0-9.]+$/);checks.browserLaunched=true;
    const context=await browser.newContext({ignoreHTTPSErrors:true,locale:'en-US',viewport:{width:1200,height:850},serviceWorkers:'block'});
    await context.route('**/*',route=>new URL(route.request().url()).origin===input.origin?route.continue():route.abort());
    await context.addCookies([{name:'__Host-hermes_go_access',value:input.session.token,url:input.origin+'/',httpOnly:true,secure:true,sameSite:'Strict'},
      {name:'__Host-hermes_go_refresh',value:input.session.refreshToken,url:input.origin+'/',httpOnly:true,secure:true,sameSite:'Strict'},
      {name:'__Host-hermes_go_installation',value:input.session.installationId,url:input.origin+'/',httpOnly:true,secure:true,sameSite:'Strict'}]);
    page=await context.newPage();page.setDefaultTimeout(20_000);
    page.on('response',async response=>{
      try{
        if(response.url()===input.origin+'/v2/web/session'&&response.status()===200)
          checks.webAuthenticated=(await response.json()).session?.authenticated===true;
        if(response.url()===input.origin+'/v2/web/auth/refresh'&&response.status()===200)
          checks.sessionRefreshed=(await response.json()).session?.authenticated===true;
        if(response.url()===input.origin+'/v2/web/devices'&&response.status()===200)
          deviceId=(await response.json()).items?.[0]?.deviceId;
      }catch{/* no response content is reported */}
    });
    page.on('websocket',socket=>{
      const address=new URL(socket.url());
      if(address.origin!==input.origin.replace('https:','wss:')||!permittedSocketPath(address.pathname))return;
      socket.on('framereceived',({payload})=>{
        try{const frame=JSON.parse(String(payload));if(frame.method==='event'&&frame.params?.type==='gateway.ready')checks.webSocketAuthenticated=true;}catch{}
      });
      socket.on('framesent',({payload})=>{
        try{
          const frame=JSON.parse(String(payload));
          if(frame.method==='prompt.submit'&&frame.params?.text===PROMPT_TEXT)checks.textSubmitted=true;
          if(frame.method==='prompt.submit'&&frame.params?.text===CLARIFY_PROMPT)checks.clarifySubmitted=true;
          if(frame.method==='clarify.lock'&&frame.params?.question_id==='q0'&&frame.params?.answer===CLARIFY_ANSWER+' (Recommended)')checks.clarifyAnswered=true;
        }catch{}
      });
    });
    stage='device';await page.goto(input.origin+'/app/',{waitUntil:'domcontentloaded'});
    await page.getByLabel('New chat',{exact:true}).click();
    const composer=page.getByPlaceholder('Type a message…');await composer.waitFor();checks.deviceReady=true;
    stage='csrf';assert.equal(typeof deviceId,'string');
    const refused=await page.evaluate(async path=>(await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status,
      `/v2/web/devices/${encodeURIComponent(deviceId)}/select-default`);
    assert.equal(refused,403);checks.csrfEnforced=true;
    stage='text';await composer.fill(PROMPT_TEXT);await page.getByRole('button',{name:'Send',exact:true}).click();
    const assistant=text=>page.locator('.turn-assistant').getByText(text,{exact:true});
    await assistant(PROMPT_ANSWER).waitFor();checks.textRendered=true;
    await page.getByRole('button',{name:'Stop',exact:true}).waitFor({state:'hidden'});
    stage='text-reload';await page.reload({waitUntil:'domcontentloaded'});await assistant(PROMPT_ANSWER).waitFor();
    assert.equal(await assistant(PROMPT_ANSWER).count(),1);checks.textHistoryReload=true;
    stage='clarify';await composer.fill(CLARIFY_PROMPT);await page.getByRole('button',{name:'Send',exact:true}).click();
    await page.getByText(CLARIFY_QUESTION,{exact:true}).waitFor();checks.clarifyQuestionRendered=true;await capture('question');
    await page.getByRole('button',{name:CLARIFY_ANSWER+' (Recommended)',exact:true}).click();
    await assistant(CLARIFY_REPLY).waitFor();checks.clarifyRendered=true;
    await page.getByRole('button',{name:'Stop',exact:true}).waitFor({state:'hidden'});
    stage='clarify-reload';await page.reload({waitUntil:'domcontentloaded'});await assistant(CLARIFY_REPLY).waitFor();
    assert.equal(await assistant(CLARIFY_REPLY).count(),1);assert.equal(await assistant(PROMPT_ANSWER).count(),1);
    checks.clarifyHistoryReload=true;await capture('completed');stage='complete';
  }catch(error){
    const diagnostic=String(error?.message??'').replace(/hg[acr]_[A-Za-z0-9_-]+/g,'[redacted]').slice(0,8192);
    await writeFile(join(input.workspace,'failure.private.txt'),diagnostic,{mode:0o600}).catch(()=>{});
    if(page){
      await writeFile(join(input.workspace,'page.private.txt'),await page.locator('body').innerText().catch(()=>''),{mode:0o600}).catch(()=>{});
      await capture('failure').catch(()=>{});
    }
  }finally{
    try{if(browser)await browser.close();checks.browserClosed=true;}catch{}
    for(const socket of sockets)socket.destroy();
    await new Promise(resolve=>proxy.close(resolve));checks.proxyClosed=true;
  }
  return {schemaVersion:1,kind:'live-web-browser-probe',scope:WEB_SCOPE,checks,stage,browserVersion:version??null,screenshots,compatibilityApproved:false};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  try{
    let input='';for await(const chunk of process.stdin){input+=chunk;if(input.length>32768)throw Error('input');}
    console.log(JSON.stringify(await probeWeb(JSON.parse(input))));
  }catch{console.error('HR-HERMESCOMPAT-004：浏览器验证未完成，请查看测试记录。 / Browser verification incomplete. Review the test record.');process.exitCode=1;}
}
