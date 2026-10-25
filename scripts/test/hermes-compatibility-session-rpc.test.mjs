import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {once} from 'node:events';
import ts from 'typescript';
import {WebSocketServer} from 'ws';
import {probeSessionRPC,SESSION_RPC_CHECKS,sessionRpcCalls,validateSessionHandle} from '../hermes-compatibility-contract-probe.mjs';

const stored='go-compat-lab-history';
const results={
  'client.capabilities':{server_requests:['approval','clarify']},
  'session.create':{session_id:'new-live',stored_session_id:'new-stored'},
  'session.resume':{session_id:'restored-live',message_count:1,messages:[],running:false,open_requests:[]},
  'config.get':{value:'medium',display:'show'},
  'session.active_list':{sessions:[{id:'restored-live',session_key:stored,status:'idle',last_active:1792926000}]},
};
async function server(t,handler,{ready=true}={}) {
  const wss=new WebSocketServer({host:'127.0.0.1',port:0});await once(wss,'listening');
  t.after(async()=>{for(const socket of wss.clients)socket.terminate();await new Promise(r=>wss.close(r));});
  wss.on('connection',socket=>{
    socket.on('message',raw=>handler(socket,JSON.parse(raw.toString())));
    if(ready)socket.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{type:'gateway.ready',payload:{}}}));
  });
  return `ws://127.0.0.1:${wss.address().port}`;
}
const answer=(socket,request,result=results[request.method])=>socket.send(JSON.stringify({jsonrpc:'2.0',id:request.id,result}));

test('session RPC probe sends the real Web client parameter shapes',async()=>{
  const source=await readFile(new URL('../../web/src/hermes/params.ts',import.meta.url),'utf8');
  const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}}).outputText;
  const client=await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
  assert.deepEqual(sessionRpcCalls.capabilities(),client.clientCapabilities());
  assert.deepEqual(sessionRpcCalls.create(),client.sessionCreate({profile:'default'}));
  assert.deepEqual(sessionRpcCalls.resume(),client.sessionResume(stored,{profile:'default'}));
  assert.deepEqual(sessionRpcCalls.reasoning('live'),client.configGetReasoning('live'));
});
test('one ready connection negotiates before creating, resuming and correlating activity',async t=>{
  const requests=[];
  const url=await server(t,(socket,request)=>{requests.push(request);answer(socket,request);});
  const checks=await probeSessionRPC(url);
  assert.ok(SESSION_RPC_CHECKS.every(name=>checks[name]));
  assert.deepEqual(requests.map(r=>r.method),Object.keys(results));
  assert.equal(requests[3].params.session_id,'restored-live');
  assert.equal(new Set(requests.map(r=>r.id)).size,5);
});
test('no ready frame means no RPCs and a bounded non-passing result',async t=>{
  let sent=0;const url=await server(t,()=>sent++,{ready:false});
  const checks=await probeSessionRPC(url,{timeoutMs:100});
  assert.equal(sent,0);assert.ok(Object.values(checks).every(v=>v===false));
});
test('wrong response IDs and malformed envelopes cannot satisfy a call',async t=>{
  for(const malformed of [false,true]) {
    const url=await server(t,(socket,request)=>socket.send(JSON.stringify(malformed?
      {jsonrpc:'1.0',id:request.id,result:results[request.method]}:
      {jsonrpc:'2.0',id:'wrong-id',result:results[request.method]})));
    const checks=await probeSessionRPC(url,{timeoutMs:100});
    assert.equal(checks.rpcCapabilities,false);assert.equal(checks.rpcResume,false);
  }
});
test('RPC errors stay local, do not leak their body, and cannot create passing dependent checks',async t=>{
  const url=await server(t,(socket,request)=>{
    if(request.method==='session.resume')socket.send(JSON.stringify({jsonrpc:'2.0',id:request.id,error:{code:4000,message:'synthetic-secret'}}));
    else answer(socket,request);
  });
  const checks=await probeSessionRPC(url);
  assert.equal(checks.rpcCreate,true);assert.equal(checks.rpcResume,false);assert.equal(checks.rpcReasoning,false);
  assert.equal(checks.rpcActiveIdentity,false);assert.equal(JSON.stringify(checks).includes('synthetic-secret'),false);
});
test('wrong stored/live mapping and transcript leakage fail their checks',async t=>{
  for(const method of ['session.resume','session.active_list']) {
    const url=await server(t,(socket,request)=>answer(socket,request,request.method===method?
      (method==='session.resume'?{...results[method],messages:[{content:'unwanted transcript'}]}:
        {sessions:[{id:'another-live',session_key:stored,status:'idle',last_active:1792926000}]}):results[request.method]));
    const checks=await probeSessionRPC(url);
    assert.equal(checks[method==='session.resume'?'rpcResume':'rpcActiveIdentity'],false);
  }
  for(const value of [{},{session_id:''},{session_id:123},{session_id:'x',stored_session_id:123},
    {session_id:'x',running:1},{session_id:'x',message_count:'1'},{session_id:'x',open_requests:{}}])
    assert.throws(()=>validateSessionHandle(value,true));
});


test('socket closure fails the pending call and later checks without waiting for the deadline',async t=>{
  const url=await server(t,(socket,request)=>{
    if(request.method==='session.create')socket.close();else answer(socket,request);
  });
  const checks=await probeSessionRPC(url);
  assert.equal(checks.rpcCapabilities,true);assert.equal(checks.rpcCreate,false);assert.equal(checks.rpcResume,false);
});
test('invalid capabilities and reasoning values are not silently certified',async t=>{
  const url=await server(t,(socket,request)=>answer(socket,request,
    request.method==='client.capabilities'?{server_requests:'approval'}:
    request.method==='config.get'?{value:123,display:'show'}:results[request.method]));
  const checks=await probeSessionRPC(url);
  assert.equal(checks.rpcCapabilities,false);assert.equal(checks.rpcReasoning,false);assert.equal(checks.rpcResume,true);
});
