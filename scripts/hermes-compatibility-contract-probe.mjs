#!/usr/bin/env node
// Actual Connector adapters against a disposable server; optional prompts use a fenced local model fixture.
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import WebSocket from 'ws';
import { HermesAuth } from '../connector/dist/hermes-auth.js';
import { evaluateHermesContract } from '../connector/dist/hermes-contract.js';
import { parseActiveList } from '../connector/dist/session-observer.js';

export const CORE_REST_SCOPE = 'required-read-surfaces-v1';
export const CORE_REST_CHECKS = ['restCoreStatus','restCoreSessions','restCoreProfileSessions','restCoreHistory','restCorePagination'];
const HISTORY_ID = 'go-compat-lab-history';
const HISTORY_TEXT = 'Hermes GO 升级前的合成会话';
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const seconds = value => typeof value === 'number' && Number.isFinite(value) && value > 0 && value < 100_000_000_000;
export function validateCoreStatus(value, version) {
  if (!object(value) || value.version !== version || typeof value.gateway_running !== 'boolean') throw new Error('status_shape');
}
export function validateCoreSessions(value) {
  if (!object(value) || !Array.isArray(value.sessions)) throw new Error('session_shape');
  const matches = value.sessions.filter(row => row?.id === HISTORY_ID);
  if (matches.length !== 1) throw new Error('session_identity');
  const row = matches[0];
  if (!seconds(row.last_active) || !Number.isSafeInteger(row.message_count) || row.message_count < 1
      || typeof row.archived !== 'boolean' || typeof row.is_default_profile !== 'boolean'
      || row.profile !== 'default') throw new Error('session_fields');
  for (const key of ['title','model','provider','cwd','source','git_branch','git_repo_root']) {
    if (row[key] !== undefined && row[key] !== null && typeof row[key] !== 'string') throw new Error('session_field_type');
  }
}
export function validateCoreHistory(value, {empty = false, pagination = false, offset = 0} = {}) {
  if (!object(value) || !Array.isArray(value.messages)) throw new Error('history_shape');
  if (empty) { if (value.messages.length !== 0) throw new Error('history_offset'); }
  else {
    if (value.messages.length !== 1) throw new Error('history_count');
    const row=value.messages[0];
    if (!object(row) || !Number.isSafeInteger(row.id) || row.id < 1 || row.role !== 'user'
        || row.content !== HISTORY_TEXT || !seconds(row.timestamp)) throw new Error('history_fields');
  }
  if (pagination && (!object(value.pagination) || value.pagination.limit !== 1 || value.pagination.offset !== offset
      || value.pagination.order !== 'oldest' || value.pagination.returned !== value.messages.length)) throw new Error('history_pagination');
}

export const RECONNECT_SCOPE = 'completed-text-turn-reconnect-v1';
export const RECONNECT_CHECKS = ['reconnectReady','reconnectCapabilities','reconnectResume','reconnectIdentity','reconnectHistory'];
export const RESTART_SCOPE = 'completed-text-turn-backend-restart-v1';
export const RESTART_CHECKS = ['restartPreviousStopped','restartFreshProcess','restartSamePort','restartStopped',
  'restartOldTokenRejected','restartVersion','restartSeedHistory','restartNoModelRequest',
  'restartReady','restartCapabilities','restartResume','restartIdentity','restartHistory',
  'restartConfigurationRestored','restartNetworkFence'];
export const PROMPT_SCOPE = 'loopback-model-text-turn-v1';
export const PROMPT_TEXT = 'Hermes GO compatibility synthetic turn';
export const PROMPT_ANSWER = 'Hermes GO synthetic model reply';
export const PROMPT_CHECKS = ['promptAccepted','promptStream','promptHistory','promptModelObserved','promptConfigurationRestored','promptNetworkFence'];
export const SESSION_RPC_SCOPE = 'synthetic-session-lifecycle-v1';
export const SESSION_RPC_CHECKS = ['rpcReady','rpcCapabilities','rpcCreate','rpcResume','rpcReasoning','rpcActiveIdentity'];
// Same shapes as web/src/hermes/params.ts; parity is checked against its real builders.
export const sessionRpcCalls = {
  capabilities: () => ({method:'client.capabilities',params:{server_requests:true}}),
  create: () => ({method:'session.create',params:{source:'hermes_remote',profile:'default'}}),
  resume: (storedId = HISTORY_ID) => ({method:'session.resume',params:{session_id:storedId,source:'hermes_remote',omit_messages:true,profile:'default'}}),
  reasoning: sessionId => ({method:'config.get',params:{key:'reasoning',session_id:sessionId}}),
};
export function validateSessionHandle(value, resume = false) {
  if (!object(value) || typeof value.session_id !== 'string' || !value.session_id.trim()) throw new Error('rpc_session');
  if (value.stored_session_id != null && (typeof value.stored_session_id !== 'string' || !value.stored_session_id.trim()))
    throw new Error('rpc_stored_session');
  if (resume) {
    if (value.running !== undefined && typeof value.running !== 'boolean') throw new Error('rpc_running');
    if (value.message_count !== undefined && (!Number.isSafeInteger(value.message_count) || value.message_count < 1)) throw new Error('rpc_count');
    if (value.messages !== undefined && (!Array.isArray(value.messages) || value.messages.length)) throw new Error('rpc_omit_messages');
    if (value.open_requests !== undefined && (!Array.isArray(value.open_requests) || value.open_requests.length))
      throw new Error('rpc_unexpected_request'); // seeded history has no outstanding requests
  }
  return value.session_id;
}
/** One connection, ready before capabilities, matching JSON-RPC IDs, bounded total lifetime. */
export function probeSessionRPC(url, {timeoutMs = 45_000, prompt = false, readHistory, onTurnVerified} = {}) {
  return new Promise(resolve => {
    const checks=Object.fromEntries([...SESSION_RPC_CHECKS,...(prompt?PROMPT_CHECKS.slice(0,3):[])].map(name=>[name,false]));
    const socket=new WebSocket(url,{handshakeTimeout:10_000,maxPayload:1024*1024});
    let done=false,started=false,sequence=0,pending,created;
    const turnEvents=[];let endTurn,turnBytes=0;
    const turnComplete=new Promise(resolve=>{endTurn=resolve;});
    const finish=()=>{
      if(done)return;done=true;clearTimeout(timer);
      if(pending){pending.reject(new Error('rpc_closed'));pending=undefined;}
      endTurn(false);
      // Report only after this transport is closed, so a following probe uses a new connection.
      const complete=()=>resolve({...checks});
      if(socket.readyState===WebSocket.CLOSED)complete();
      else {socket.once('close',complete);socket.terminate();}
    };
    const timer=setTimeout(finish,timeoutMs);
    const call=({method,params})=>new Promise((accept,reject)=>{
      if(done){reject(new Error('rpc_closed'));return;}
      const id=`compat-session-${++sequence}`;pending={id,accept,reject};
      socket.send(JSON.stringify({jsonrpc:'2.0',id,method,params}),error=>{if(error)finish();});
    });
    const check=async(name,fn)=>{try{await fn();if(!done)checks[name]=true;}catch{/* boolean evidence only */}};
    async function run() {
      let resumed;
      await check('rpcCapabilities',async()=>{
        const v=await call(sessionRpcCalls.capabilities());
        if(!object(v)||!Array.isArray(v.server_requests)||!v.server_requests.every(x=>typeof x==='string'))throw new Error('rpc_capabilities');
      });
      await check('rpcCreate',async()=>{created=await call(sessionRpcCalls.create());validateSessionHandle(created);});
      await check('rpcResume',async()=>{resumed=validateSessionHandle(await call(sessionRpcCalls.resume()),true);});
      await check('rpcReasoning',async()=>{
        if(!resumed)throw new Error('rpc_no_session');
        const v=await call(sessionRpcCalls.reasoning(resumed));
        if(!object(v)||typeof v.value!=='string'||!v.value.trim()||!['show','hide'].includes(v.display))throw new Error('rpc_reasoning');
      });
      await check('rpcActiveIdentity',async()=>{
        if(!resumed)throw new Error('rpc_no_session');
        const rows=parseActiveList(await call({method:'session.active_list',params:{}}));
        if(!rows.some(row=>row.id===resumed&&row.sessionKey===HISTORY_ID))throw new Error('rpc_active_identity');
      });
      if(prompt) {
        await check('promptAccepted',async()=>{
          if(!checks.rpcCreate)throw new Error('no_created_session');
          const v=await call({method:'prompt.submit',params:{session_id:created.session_id,text:PROMPT_TEXT}});
          if(v?.status!=='streaming')throw new Error('prompt_rejected');
        });
        if(checks.promptAccepted)await check('promptStream',async()=>{
          if(!await turnComplete)throw new Error('turn_incomplete');
          const delta=turnEvents.filter(e=>e.type==='message.delta').map(e=>e.payload?.text??'').join('');
          const complete=turnEvents.find(e=>e.type==='message.complete');
          if(delta!==PROMPT_ANSWER||complete?.payload?.text!==PROMPT_ANSWER
            ||['error','interrupted'].includes(complete.payload.status))throw new Error('turn_content');
        });
        await check('promptHistory',async()=>{
          if(!checks.promptStream||typeof readHistory!=='function')throw new Error('turn_unverified');
          const stored=created.stored_session_id??turnEvents.find(e=>e.payload?.stored_session_id)?.payload.stored_session_id;
          if(typeof stored!=='string'||!stored)throw new Error('turn_stored_id');
          const value=await readHistory(stored);
          const history=validatePromptHistory(value);
          if(onTurnVerified)onTurnVerified({storedId:stored,history});
        });
      }
      finish();
    }
    socket.on('error',finish);socket.on('close',finish);
    socket.on('unexpected-response',(_request,response)=>{response.resume();finish();});
    socket.on('message',raw=>{
      try {
        const frame=JSON.parse(raw.toString());
        if(!object(frame)||frame.jsonrpc!=='2.0')throw new Error('rpc_envelope');
        if(frame.method==='event'&&frame.params?.type==='gateway.ready'&&!started){
          started=true;checks.rpcReady=true;void run().catch(finish);return;
        }
        if(prompt&&created&&frame.method==='event'&&frame.params?.session_id===created.session_id
            &&['message.delta','message.complete'].includes(frame.params.type)) {
          const {text,status,stored_session_id}=frame.params.payload??{};
          if(typeof text!=='string'||(stored_session_id!==undefined&&(typeof stored_session_id!=='string'||stored_session_id.length>256)))
            throw new Error('turn_event_shape');
          turnBytes+=Buffer.byteLength(text);
          if(turnEvents.length>=256||turnBytes>64*1024)throw new Error('turn_events');
          turnEvents.push({type:frame.params.type,payload:{text,status:typeof status==='string'?status.slice(0,64):undefined,stored_session_id}});
          if(frame.params.type==='message.complete')endTurn(true);
        }
        if(!pending||frame.id!==pending.id)return;
        const request=pending;pending=undefined;
        if(Object.hasOwn(frame,'error')||!Object.hasOwn(frame,'result'))request.reject(new Error('rpc_response'));
        else request.accept(frame.result);
      }catch{finish();}
    });
  });
}

export function validatePromptHistory(value) {
  if(!Array.isArray(value?.messages))throw new Error('turn_history');
  const history=[];
  for(const [role,content] of [['user',PROMPT_TEXT],['assistant',PROMPT_ANSWER]]) {
    const matches=value.messages.filter(m=>m?.role===role&&m.content===content);
    if(matches.length!==1||!seconds(matches[0].timestamp)||!Number.isSafeInteger(matches[0].id)||matches[0].id<1)
      throw new Error('turn_history');
    const {id,timestamp}=matches[0];history.push({id,role,content,timestamp});
  }
  if(history[0].id===history[1].id)throw new Error('turn_history_identity');
  return history;
}
/** New authenticated transport, same stored conversation, no new prompt or history writes. */
export function probeReconnectRPC(url,{storedId,history,readHistory,timeoutMs=15_000}) {
  return new Promise(resolve=>{
    const checks=Object.fromEntries(RECONNECT_CHECKS.map(n=>[n,false]));
    const socket=new WebSocket(url,{handshakeTimeout:10_000,maxPayload:1024*1024});
    let done=false,started=false,pending,sequence=0;
    const finish=()=>{
      if(done)return;done=true;clearTimeout(timer);
      if(pending){pending.reject(new Error('reconnect_closed'));pending=undefined;}
      const complete=()=>resolve({...checks});
      if(socket.readyState===WebSocket.CLOSED)complete();
      else {socket.once('close',complete);socket.terminate();}
    };
    const timer=setTimeout(finish,timeoutMs);
    const call=({method,params})=>new Promise((accept,reject)=>{
      if(done){reject(new Error('reconnect_closed'));return;}
      const id=`compat-reconnect-${++sequence}`;pending={id,accept,reject};
      socket.send(JSON.stringify({jsonrpc:'2.0',id,method,params}),error=>{if(error)finish();});
    });
    const check=async(name,fn)=>{try{await fn();if(!done)checks[name]=true;}catch{/* booleans only */}};
    async function run(){
      let live;
      await check('reconnectCapabilities',async()=>{
        const v=await call(sessionRpcCalls.capabilities());
        if(!object(v)||!Array.isArray(v.server_requests)||!v.server_requests.every(x=>typeof x==='string'))throw new Error('capabilities');
      });
      await check('reconnectResume',async()=>{
        if(typeof storedId!=='string'||!storedId)throw new Error('stored_id');
        live=validateSessionHandle(await call(sessionRpcCalls.resume(storedId)),true);
      });
      await check('reconnectIdentity',async()=>{
        if(!live)throw new Error('no_live');
        const rows=parseActiveList(await call({method:'session.active_list',params:{}}));
        if(!rows.some(r=>r.id===live&&r.sessionKey===storedId))throw new Error('identity');
      });
      await check('reconnectHistory',async()=>{
        if(!checks.reconnectIdentity||!Array.isArray(history))throw new Error('identity');
        if(JSON.stringify(validatePromptHistory(await readHistory(storedId)))!==JSON.stringify(history))throw new Error('changed_history');
      });
      finish();
    }
    socket.on('error',finish);socket.on('close',finish);
    socket.on('unexpected-response',(_request,response)=>{response.resume();finish();});
    socket.on('message',raw=>{
      try {
        const frame=JSON.parse(raw.toString());
        if(!object(frame)||frame.jsonrpc!=='2.0')throw new Error('envelope');
        if(frame.method==='event'&&frame.params?.type==='gateway.ready'&&!started){
          started=true;checks.reconnectReady=true;void run().catch(finish);return;
        }
        if(!pending||frame.id!==pending.id)return;
        const current=pending;pending=undefined;
        if(Object.hasOwn(frame,'error')||!Object.hasOwn(frame,'result'))current.reject(new Error('response'));
        else current.accept(frame.result);
      }catch{finish();}
    });
  });
}

async function body(response, maximum = 2 * 1024 * 1024) {
  if (!response.ok || !response.body) throw new Error('response');
  let count = 0; const chunks = [];
  const reader = response.body.getReader();
  try {
    while (true) {
      const item = await reader.read(); if (item.done) break;
      count += item.value.length; if (count > maximum) throw new Error('size');
      chunks.push(item.value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  return Buffer.concat(chunks).toString('utf8');
}
function websocket(url, rejectExpected) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, {handshakeTimeout: 10_000, maxPayload: 1024 * 1024});
    const timer = setTimeout(() => finish(false), 20_000);
    let done = false;
    function finish(ok) {
      if (done) return; done = true; clearTimeout(timer); socket.terminate();
      ok ? resolve(true) : reject(new Error('websocket_probe'));
    }
    socket.on('error', () => finish(false));
    socket.on('unexpected-response', (_request, response) => {
      response.resume(); finish(rejectExpected && [401,403].includes(response.statusCode));
    });
    socket.on('open', () => {
      if (rejectExpected) { finish(false); return; }
      socket.send(JSON.stringify({jsonrpc:'2.0', id:'compatibility-probe', method:'session.active_list', params:{}}));
    });
    socket.on('message', raw => {
      try {
        const frame = JSON.parse(raw.toString());
        if (frame.id !== 'compatibility-probe') return;
        if (frame.error) throw new Error('rpc_error');
        parseActiveList(frame.result); finish(true);
      } catch { finish(false); }
    });
    socket.on('close', () => { if (!done) finish(false); });
  });
}
export async function probeContract({port, token, version, prompt = false, transport = 'direct', captureRestart = false}) {
  if(typeof captureRestart!=='boolean'||(captureRestart&&(!prompt||transport!=='direct')))throw new Error('invalid_checkpoint_capture');
  if (!['direct','legacy-relay'].includes(transport) || typeof prompt !== 'boolean' || !Number.isInteger(port) || port < 1 || port > 65535 || typeof token !== 'string'
      || !/^[A-Za-z0-9_-]{32,128}$/.test(token) || !['0.21.3','0.22.5'].includes(version)) throw new Error('invalid_probe');
  const base = `http://127.0.0.1:${port}`, auth = new HermesAuth({baseUrl:base,sessionToken:token,requestTimeoutMs:15_000});
  const checks = {};
  async function check(name, fn) { try { await fn(); checks[name] = true; } catch { checks[name] = false; } }
  for (const [name, headers] of [['httpMissingToken',{}],['httpWrongToken',{'X-Hermes-Session-Token':'deliberately-wrong'}]]) {
    await check(name, async () => {
      const response = await fetch(base + '/api/profiles/active', {headers,redirect:'error',signal:AbortSignal.timeout(15_000)});
      await response.body?.cancel(); if (![401,403].includes(response.status)) throw new Error('not_rejected');
    });
  }
  await check('httpValidToken', async () => { await body(await auth.request('/api/profiles/active', {method:'GET'})); });
  await check('restCoreStatus', async () => validateCoreStatus(JSON.parse(await body(await auth.request('/api/status',{method:'GET'}))),version));
  await check('runningVersion', async () => {
    const status = JSON.parse(await body(await auth.request('/api/status', {method:'GET'})));
    if (status.version !== version) throw new Error('version');
  });
  for (const [name,path] of [['sessionList','/api/sessions?limit=100'], ['profileSessionList','/api/profiles/sessions?limit=100']]) {
    await check(name === 'sessionList' ? 'restCoreSessions' : 'restCoreProfileSessions', async () =>
      validateCoreSessions(JSON.parse(await body(await auth.request(path,{method:'GET'})))));
    await check(name, async () => {
      const value = JSON.parse(await body(await auth.request(path,{method:'GET'})));
      if (!Array.isArray(value.sessions) || !value.sessions.some(s=>s.id==='go-compat-lab-history')) throw new Error('history');
    });
  }
  await check('historyRead', async () => {
    const value = JSON.parse(await body(await auth.request('/api/sessions/go-compat-lab-history/messages',{method:'GET'})));
    if (!JSON.stringify(value).includes('Hermes GO 升级前的合成会话')) throw new Error('history');
  });
  await check('restCoreHistory', async () => validateCoreHistory(JSON.parse(await body(
    await auth.request(`/api/sessions/${HISTORY_ID}/messages`,{method:'GET'})))));
  await check('restCorePagination', async () => {
    for (const offset of [0,1]) validateCoreHistory(JSON.parse(await body(await auth.request(
      `/api/sessions/${HISTORY_ID}/messages?limit=1&offset=${offset}&order=oldest`,{method:'GET'}))),
      {empty:offset===1,pagination:true,offset});
  });
  await check('connectorSchema', async () => {
    const report = transport==='direct'
      ? evaluateHermesContract({openapi:{kind:'ok',body:await body(await auth.openApiDocument(),8*1024*1024)},hermesVersion:version,checkedAt:new Date()})
      : JSON.parse(await body(await auth.request('/api/hermes-remote/contract',{method:'GET'})));
    if (report.status !== 'compatible') throw new Error('contract');
  });
  await check('websocketMissingToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws', true));
  await check('websocketWrongToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws?token=deliberately-wrong', true));
  await check('connectorActiveListRPC', async () => websocket(await auth.websocketUrl('/api/ws'), false));
  let completedTurn;
  const readHistory=async id=>JSON.parse(await body(await auth.request(`/api/sessions/${encodeURIComponent(id)}/messages`,{method:'GET'})));
  Object.assign(checks,await probeSessionRPC(await auth.websocketUrl('/api/ws'),{prompt,timeoutMs:prompt?80_000:45_000,
    readHistory,onTurnVerified:value=>{completedTurn=value;}}));
  if(prompt)Object.assign(checks,completedTurn
    ? await probeReconnectRPC(await auth.websocketUrl('/api/ws'),{...completedTurn,readHistory})
    : Object.fromEntries(RECONNECT_CHECKS.map(n=>[n,false])));
  // Only the private parent process receives this checkpoint; it must strip it before reporting.
  return {schemaVersion:1,kind:'live-connector-contract-probe',version,transport,restScope:CORE_REST_SCOPE,rpcScope:SESSION_RPC_SCOPE,...(prompt?{promptScope:PROMPT_SCOPE,reconnectScope:RECONNECT_SCOPE}:{}),checks,compatibilityApproved:false,
    ...(captureRestart?{restartCheckpoint:completedTurn??null}:{})};
}

/** Read an already completed synthetic turn after the owning backend has actually restarted. */
export async function probeBackendRestart({port,token,previousToken,version,checkpoint}) {
  if(!Number.isInteger(port)||port<1||port>65535||!['0.21.3','0.22.5'].includes(version)
    ||![token,previousToken].every(v=>typeof v==='string'&&/^[A-Za-z0-9_-]{32,128}$/.test(v))||token===previousToken
    ||!object(checkpoint)||typeof checkpoint.storedId!=='string'||!checkpoint.storedId||checkpoint.storedId.length>256
    ||!Array.isArray(checkpoint.history)||checkpoint.history.length!==2
    ||JSON.stringify(validatePromptHistory({messages:checkpoint.history}))!==JSON.stringify(checkpoint.history))throw new Error('invalid_restart');
  const base=`http://127.0.0.1:${port}`,auth=new HermesAuth({baseUrl:base,sessionToken:token,requestTimeoutMs:15_000});
  const checks={};
  async function check(name,fn){try{await fn();checks[name]=true;}catch{checks[name]=false;}}
  await check('restartOldTokenRejected',async()=>{
    const response=await fetch(base+'/api/profiles/active',{headers:{'X-Hermes-Session-Token':previousToken},redirect:'error',signal:AbortSignal.timeout(15_000)});
    await response.body?.cancel();if(![401,403].includes(response.status))throw new Error('old_token_accepted');
  });
  await check('restartVersion',async()=>validateCoreStatus(JSON.parse(await body(await auth.request('/api/status',{method:'GET'}))),version));
  await check('restartSeedHistory',async()=>validateCoreHistory(JSON.parse(await body(await auth.request(`/api/sessions/${HISTORY_ID}/messages`,{method:'GET'})))));
  const readHistory=async id=>JSON.parse(await body(await auth.request(`/api/sessions/${encodeURIComponent(id)}/messages`,{method:'GET'})));
  const resumed=await probeReconnectRPC(await auth.websocketUrl('/api/ws'),{...checkpoint,readHistory});
  for(const [name,passed] of Object.entries(resumed))checks[name.replace('reconnect','restart')]=passed;
  return {schemaVersion:1,kind:'live-backend-restart-probe',scope:RESTART_SCOPE,version,attempted:true,checks,compatibilityApproved:false};
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    let input=''; for await (const chunk of process.stdin) { input+=chunk; if(input.length>4096)throw new Error('input'); }
    const value=JSON.parse(input);
    if(value.operation!==undefined&&value.operation!=='restart')throw new Error('operation');
    console.log(JSON.stringify(await (value.operation==='restart'?probeBackendRestart(value):probeContract(value))));
  } catch { console.error('HR-HERMESCOMPAT-004：接口验证未完成，请查看测试记录。 / Contract verification incomplete; review the test record.'); process.exitCode=1; }
}
