import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
import {WebSocketServer} from 'ws';
import {probeClarifyRPC,clarifyAnswerCall,CLARIFY_CHECKS,CLARIFY_PROMPT,CLARIFY_QUESTION,CLARIFY_ANSWER,CLARIFY_REPLY,CLARIFY_CALL} from '../hermes-compatibility-clarify-probe.mjs';
const result={responses:[{question:CLARIFY_QUESTION,user_response:CLARIFY_ANSWER}]};
const history={messages:[{id:1,role:'user',content:CLARIFY_PROMPT,timestamp:1792926000},
  {id:2,role:'assistant',content:CLARIFY_REPLY,timestamp:1792926001}]};
async function fixture(t,variant='ok'){
  const server=new WebSocketServer({host:'127.0.0.1',port:0});await once(server,'listening');
  t.after(async()=>{for(const socket of server.clients)socket.terminate();await new Promise(r=>server.close(r));});
  const calls=[];
  server.on('connection',socket=>{
    const send=frame=>socket.send(JSON.stringify({jsonrpc:'2.0',...frame}));
    const event=(type,payload)=>send({method:'event',params:{session_id:'live',type,payload}});
    let prompt;
    socket.on('message',raw=>{
      const call=JSON.parse(raw);calls.push(call);
      if(call.method==='client.capabilities')send({id:call.id,result:{server_requests:['clarify']}});
      if(call.method==='session.create')send({id:call.id,result:{session_id:'live',stored_session_id:'stored'}});
      if(call.method==='prompt.submit'){
        assert.equal(call.params.text,CLARIFY_PROMPT);prompt=call;
        // Intentionally deliver server request before the outstanding prompt response.
        event('tool.start',{tool_id:CLARIFY_CALL,name:'clarify'});
        if(variant==='timeout')return;
        send({id:'srq-0123456789ab',method:'clarify',params:{session_id:variant==='wrong-session'?'other':'live',questions:[
          {qid:variant==='missing-qid'?'':'q0',question:CLARIFY_QUESTION,choices:[CLARIFY_ANSWER+' (Recommended)','Synthetic choice B'],multi_select:false}]}});
      }
      if(call.method==='clarify.lock'){
        assert.deepEqual({method:call.method,params:call.params},clarifyAnswerCall('srq-0123456789ab','q0'));
        send({id:prompt.id,result:{status:'streaming'}});
        event('tool.complete',{tool_id:variant==='wrong-tool'?'wrong':CLARIFY_CALL,name:'clarify',result:variant==='wrong-result'?{}:result});
        event('message.delta',{text:variant==='overflow'?'x'.repeat(65537):variant==='paragraph'?'\n\n'+CLARIFY_REPLY:variant==='extra-text'?'unexpected '+CLARIFY_REPLY:CLARIFY_REPLY});
        event('message.complete',{text:CLARIFY_REPLY,status:variant==='interrupted'?'interrupted':'completed'});
        // Complete event can precede acknowledgement; success must await this response.
        if(variant!=='missing-ack')send({id:call.id,result:{status:variant==='expired'?'expired':'ok',remaining:variant==='wrong-remaining'?0:[]}});
      }
    });
    event('gateway.ready',{});
  });
  return {url:`ws://127.0.0.1:${server.address().port}`,calls};
}
test('batch answer matches the real Web client, including a one-question batch',async()=>{
  const source=await readFile(new URL('../../web/src/hermes/params.ts',import.meta.url),'utf8');
  const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}}).outputText;
  const client=await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`);
  assert.deepEqual(clarifyAnswerCall('request','q0'),client.clarifyLock('request','q0',CLARIFY_ANSWER));
});
test('tool/request/answer/stream/history verified despite interleaved RPC responses',async t=>{
  const {url}=await fixture(t);
  const checks=await probeClarifyRPC(url,{readHistory:async id=>{assert.equal(id,'stored');return history;}});
  assert.ok(CLARIFY_CHECKS.every(key=>checks[key]),JSON.stringify(checks));
});
for(const variant of ['timeout','wrong-session','missing-qid','wrong-tool','wrong-result','overflow','interrupted','missing-ack','expired','wrong-remaining','extra-text']){
  test(`refuses ${variant} without reporting verified history`,async t=>{
    const {url,calls}=await fixture(t,variant);
    const checks=await probeClarifyRPC(url,{timeoutMs:150,readHistory:async()=>{assert.fail('unverified turn read');}});
    assert.equal(checks.clarifyHistory,false);assert.equal(checks.clarifyStream,false);
    if(['wrong-session','missing-qid'].includes(variant))assert.equal(calls.some(c=>c.method==='clarify.lock'),false);
  });
}
test('missing stored answer cannot pass history evidence',async t=>{
  const {url}=await fixture(t);const checks=await probeClarifyRPC(url,{readHistory:async()=>({messages:[]})});
  assert.equal(checks.clarifyStream,true);assert.equal(checks.clarifyHistory,false);
});

test('upstream paragraph separator after a tool round is accepted without trimming other text',async t=>{
  const {url}=await fixture(t,'paragraph');let observed;
  const checks=await probeClarifyRPC(url,{readHistory:async()=>history,onObservation:v=>{observed=v;}});
  assert.ok(CLARIFY_CHECKS.every(key=>checks[key]));assert.equal(observed.paragraphBreak,true);
});
