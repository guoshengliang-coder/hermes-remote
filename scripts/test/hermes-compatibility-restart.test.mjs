import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {WebSocketServer} from 'ws';
import {probeBackendRestart,validatePromptHistory,PROMPT_TEXT,PROMPT_ANSWER} from '../hermes-compatibility-contract-probe.mjs';

const token='n'.repeat(40),previousToken='o'.repeat(40),storedId='synthetic-stored';
const messages=()=>[{id:2,role:'user',content:PROMPT_TEXT,timestamp:1792926000},
  {id:3,role:'assistant',content:PROMPT_ANSWER,timestamp:1792926001}];
async function fixture(t){
  const state={oldAccepted:false,version:'0.22.5',messages:messages(),requests:[]};
  const http=createServer((req,res)=>{
    if(req.headers['x-hermes-session-token']!==token){res.writeHead(state.oldAccepted?200:403);res.end('{}');return;}
    let value;
    if(req.url==='/api/status')value={version:state.version,gateway_running:false};
    else if(req.url==='/api/sessions/go-compat-lab-history/messages')value={messages:[{id:1,role:'user',content:'Hermes GO 升级前的合成会话',timestamp:1792926000}]};
    else if(req.url===`/api/sessions/${storedId}/messages`)value={messages:state.messages};
    else {res.writeHead(404);res.end('{}');return;}
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));
  });
  const wss=new WebSocketServer({noServer:true});
  http.on('upgrade',(req,socket,head)=>{
    if(new URL(req.url,'http://localhost').searchParams.get('token')!==token){socket.destroy();return;}
    wss.handleUpgrade(req,socket,head,ws=>wss.emit('connection',ws));
  });
  wss.on('connection',ws=>{
    ws.on('message',raw=>{
      const request=JSON.parse(raw.toString());state.requests.push(request);
      const values={'client.capabilities':{server_requests:[]},'session.resume':{session_id:'restarted-live',messages:[],message_count:2},
        'session.active_list':{sessions:[{id:'restarted-live',session_key:storedId,status:'idle'}]}};
      assert.ok(Object.hasOwn(values,request.method),'restart must not submit a new prompt or create a conversation');
      ws.send(JSON.stringify({jsonrpc:'2.0',id:request.id,result:values[request.method]}));
    });
    ws.send(JSON.stringify({jsonrpc:'2.0',method:'event',params:{type:'gateway.ready'}}));
  });
  http.listen(0,'127.0.0.1');await once(http,'listening');
  t.after(async()=>{for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));http.closeAllConnections();await new Promise(r=>http.close(r));});
  return {state,input:{port:http.address().port,token,previousToken,version:'0.22.5',checkpoint:{storedId,history:validatePromptHistory({messages:messages()})}}};
}
test('restart probe authenticates anew and resumes exact stored rows without another prompt',async t=>{
  const {input,state}=await fixture(t),result=await probeBackendRestart(input);
  assert.ok(Object.values(result.checks).every(Boolean));
  assert.equal(state.requests.find(r=>r.method==='session.resume').params.session_id,storedId);
  for(const privateValue of [token,previousToken,storedId,PROMPT_TEXT,PROMPT_ANSWER])assert.equal(JSON.stringify(result).includes(privateValue),false);
});
test('old credential reuse, wrong running version and changed persisted history are recorded as failures',async t=>{
  const {input,state}=await fixture(t);state.oldAccepted=true;state.version='0.21.3';state.messages[1].id++;
  const result=await probeBackendRestart(input);
  for(const key of ['restartOldTokenRejected','restartVersion','restartHistory'])assert.equal(result.checks[key],false);
  assert.equal(result.checks.restartResume,true);
});
test('missing, invalid or duplicate checkpoints and unchanged credentials fail before access',async()=>{
  const input={port:1,token,previousToken,version:'0.22.5',checkpoint:{storedId,history:validatePromptHistory({messages:messages()})}};
  for(const patch of [{checkpoint:null},{checkpoint:{storedId,history:[]}},{previousToken:token},
    {checkpoint:{storedId,history:[input.checkpoint.history[0],input.checkpoint.history[0]]}}])
    await assert.rejects(probeBackendRestart({...input,...patch}));
});
