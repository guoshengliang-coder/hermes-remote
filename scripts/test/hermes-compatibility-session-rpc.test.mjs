import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {once} from 'node:events';
import ts from 'typescript';
import {WebSocketServer} from 'ws';
import {probeSessionRPC,SESSION_RPC_CHECKS,sessionRpcCalls,validateSessionHandle,probeReconnectRPC,RECONNECT_CHECKS,validatePromptHistory} from '../hermes-compatibility-contract-probe.mjs';

const stored='go-compat-lab-history';
const results={
  'client.capabilities':{server_requests:['approval','clarify']},
  'session.create':{session_id:'new-live',stored_session_id:'new-stored'},
  'session.resume':{session_id:'restored-live',message_count:1,messages:[],running:false,open_requests:[]},
  'config.get':{value:'medium',display:'show'},
  'session.active_list':{sessions:[{id:'restored-live',session_key:stored,status:'idle',last_active:1792926000}]},
};
async function server(t,handler,{ready=true,onConnection}={}) {
  const wss=new WebSocketServer({host:'127.0.0.1',port:0});await once(wss,'listening');
  t.after(async()=>{for(const socket of wss.clients)socket.terminate();await new Promise(r=>wss.close(r));});
  wss.on('connection',socket=>{
    onConnection?.(socket);
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

test('synthetic prompt accepts early streamed events and verifies persisted roles',async t=>{
  const url=await server(t,(socket,request)=>{
    if(request.method==='prompt.submit') {
      assert.deepEqual(request.params,{session_id:'new-live',text:'Hermes GO compatibility synthetic turn'});
      for(const [type,text] of [['message.delta','Hermes GO '],['message.delta','synthetic model reply'],['message.complete','Hermes GO synthetic model reply']])
        socket.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{type,session_id:'new-live',payload:{text,stored_session_id:'new-stored'}}}));
      answer(socket,request,{status:'streaming'});
    }else answer(socket,request);
  });
  const checks=await probeSessionRPC(url,{prompt:true,readHistory:async id=>{
    assert.equal(id,'new-stored');
    return {messages:[{id:1,role:'user',content:'Hermes GO compatibility synthetic turn',timestamp:1792926000},
      {id:2,role:'assistant',content:'Hermes GO synthetic model reply',timestamp:1792926001}]};
  }});
  assert.equal(checks.promptAccepted,true);assert.equal(checks.promptStream,true);assert.equal(checks.promptHistory,true);
});
test('unrelated session events and missing persisted assistant cannot pass a prompt',async t=>{
  for(const unrelated of [true,false]) {
    const url=await server(t,(socket,request)=>{
      if(request.method==='prompt.submit') {
        answer(socket,request,{status:'streaming'});
        for(const type of ['message.delta','message.complete'])socket.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{
          type,session_id:unrelated?'different':'new-live',payload:{text:'Hermes GO synthetic model reply'}}}));
      }else answer(socket,request);
    });
    const checks=await probeSessionRPC(url,{prompt:true,timeoutMs:100,readHistory:async()=>({messages:[]})});
    assert.equal(checks.promptAccepted,true);assert.equal(checks.promptStream,!unrelated);assert.equal(checks.promptHistory,false);
  }
});


test('prompt event accumulation is bounded and cannot claim completed history',async t=>{
  const url=await server(t,(socket,request)=>{
    if(request.method==='prompt.submit') {
      answer(socket,request,{status:'streaming'});
      socket.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{type:'message.delta',session_id:'new-live',payload:{text:'x'.repeat(65537)}}}));
    }else answer(socket,request);
  });
  const checks=await probeSessionRPC(url,{prompt:true,readHistory:async()=>{throw new Error('must not read');}});
  assert.equal(checks.promptStream,false);assert.equal(checks.promptHistory,false);
});


const savedTurn=()=>({messages:[
  {id:71,role:'user',content:'Hermes GO compatibility synthetic turn',timestamp:1792926000.5},
  {id:72,role:'assistant',content:'Hermes GO synthetic model reply',timestamp:1792926001.5},
]});
test('completed turn is resumed over a distinct connection using its stored ID',async t=>{
  const connections=[];const calls=[];let verified;
  const url=await server(t,(socket,request)=>{
    const index=connections.indexOf(socket);calls.push([index,request.method]);
    if(index===1) {
      if(request.method==='session.resume')assert.deepEqual(request.params,{session_id:'new-stored',source:'hermes_remote',omit_messages:true,profile:'default'});
      answer(socket,request,request.method==='session.active_list'?{sessions:[{id:'restored-live',session_key:'new-stored',status:'idle'}]}:results[request.method]);
    }else if(request.method==='prompt.submit') {
      answer(socket,request,{status:'streaming'});
      for(const type of ['message.delta','message.complete'])socket.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{
        type,session_id:'new-live',payload:{text:'Hermes GO synthetic model reply'}}}));
    }else answer(socket,request);
  },{onConnection:socket=>connections.push(socket)});
  const first=await probeSessionRPC(url,{prompt:true,readHistory:async()=>savedTurn(),onTurnVerified:value=>{verified=value;}});
  assert.equal(first.promptHistory,true);
  const second=await probeReconnectRPC(url,{...verified,readHistory:async id=>{assert.equal(id,'new-stored');return savedTurn();}});
  assert.ok(RECONNECT_CHECKS.every(name=>second[name]));assert.equal(connections.length,2);
  assert.deepEqual(calls.filter(([index])=>index===1).map(([,method])=>method),['client.capabilities','session.resume','session.active_list']);
  assert.equal(connections[0].readyState,3);
});
test('reconnect rejects lost, duplicated or changed stored rows',async t=>{
  const url=await server(t,(socket,request)=>answer(socket,request,request.method==='session.active_list'?
    {sessions:[{id:'restored-live',session_key:'new-stored',status:'idle'}]}:results[request.method]));
  for(const mutate of [v=>v.messages.pop(),v=>v.messages.push({...v.messages[0]}),v=>v.messages[0].id+=10,
    v=>v.messages[1].timestamp++,v=>{v.messages[1].content='changed';}]) {
    const changed=savedTurn();mutate(changed);
    const checks=await probeReconnectRPC(url,{storedId:'new-stored',history:validatePromptHistory(savedTurn()),readHistory:async()=>changed});
    assert.equal(checks.reconnectResume,true);assert.equal(checks.reconnectHistory,false);
  }
});
test('reconnect requires live/stored correlation and actual readiness',async t=>{
  const url=await server(t,answer);
  const wrong=await probeReconnectRPC(url,{storedId:'wrong-stored',history:validatePromptHistory(savedTurn()),readHistory:async()=>savedTurn()});
  assert.equal(wrong.reconnectIdentity,false);assert.equal(wrong.reconnectHistory,false);
  const quiet=await server(t,()=>assert.fail('RPC sent before ready'),{ready:false});
  const timeout=await probeReconnectRPC(quiet,{storedId:'new-stored',history:[],timeoutMs:100});
  assert.ok(Object.values(timeout).every(v=>v===false));
});
test('reconnect malformed responses and connection loss are bounded failures',async t=>{
  for(const mode of ['close','wrong-id','error']) {
    const url=await server(t,(socket,request)=>{
      if(mode==='close')socket.close();
      else socket.send(JSON.stringify(mode==='error'?{jsonrpc:'2.0',id:request.id,error:{code:4000,message:'synthetic-secret'}}:
        {jsonrpc:'2.0',id:'wrong-id',result:results[request.method]}));
    });
    const checks=await probeReconnectRPC(url,{storedId:'new-stored',history:[],timeoutMs:100});
    assert.equal(checks.reconnectCapabilities,false);assert.equal(checks.reconnectHistory,false);
    assert.equal(JSON.stringify(checks).includes('synthetic-secret'),false);
  }
});
