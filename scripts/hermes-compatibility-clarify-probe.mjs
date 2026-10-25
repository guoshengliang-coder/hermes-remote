// Isolated one-question batch only. No production credentials or model-facing network.
import WebSocket from 'ws';
export const CLARIFY_SCOPE='loopback-model-one-question-batch-v1';
export const CLARIFY_PROMPT='Hermes GO compatibility synthetic clarification';
export const CLARIFY_QUESTION='Choose the synthetic compatibility answer';
export const CLARIFY_ANSWER='Synthetic choice A';
export const CLARIFY_REPLY='Hermes GO synthetic clarification completed';
export const CLARIFY_CALL='call_go_compat_clarify';
export const CLARIFY_CHECKS=['clarifyReady','clarifyCapabilities','clarifyCreate','clarifyAccepted',
  'clarifyToolStart','clarifyQuestion','clarifyAnswerAcknowledged','clarifyToolComplete','clarifyStream','clarifyHistory'];
export const clarifyAnswerCall=(requestId,questionId)=>({method:'clarify.lock',params:{request_id:requestId,question_id:questionId,answer:CLARIFY_ANSWER}});
// Upstream stream_delivery prepends one paragraph break after a tool iteration.
const streamMatches=text=>text===CLARIFY_REPLY||text==='\n\n'+CLARIFY_REPLY;
const resultMatches=value=>value?.timed_out!==true&&Array.isArray(value?.responses)&&value.responses.length===1
  &&value.responses[0]?.question===CLARIFY_QUESTION&&value.responses[0]?.user_response===CLARIFY_ANSWER;

export function probeClarifyRPC(url,{headers,readHistory,onObservation,timeoutMs=45_000}={}) {
  return new Promise(resolve=>{
    const checks=Object.fromEntries(CLARIFY_CHECKS.map(key=>[key,false]));
    const socket=new WebSocket(url,{headers,handshakeTimeout:10_000,maxPayload:1024*1024});
    const pending=new Map();
    let done=false,started=false,sequence=0,created,questionId,answerPromise,stream='',complete,events=0;
    let endTurn;const turn=new Promise(r=>{endTurn=r;});
    const finish=()=>{
      if(done)return;done=true;clearTimeout(timer);endTurn(false);
      for(const request of pending.values())request.reject(new Error('closed'));pending.clear();
      const closed=()=>{
        onObservation?.({deltaCharacters:stream.length,deltaMatches:streamMatches(stream),paragraphBreak:stream==='\n\n'+CLARIFY_REPLY,
          completeMatches:complete?.text===CLARIFY_REPLY,
          completeStatus:['complete','error','interrupted'].includes(complete?.status)?complete.status:'other'});
        resolve({...checks});
      };
      if(socket.readyState===WebSocket.CLOSED)closed();else{socket.once('close',closed);socket.terminate();}
    };
    const timer=setTimeout(finish,timeoutMs);
    const call=({method,params})=>new Promise((accept,reject)=>{
      if(done){reject(new Error('closed'));return;}
      const id=`compat-clarify-${++sequence}`;pending.set(id,{accept,reject});
      socket.send(JSON.stringify({jsonrpc:'2.0',id,method,params}),error=>{if(error)finish();});
    });
    async function run(){
      const capabilities=await call({method:'client.capabilities',params:{server_requests:true}});
      if(!Array.isArray(capabilities?.server_requests)||!capabilities.server_requests.includes('clarify'))throw new Error('capabilities');
      checks.clarifyCapabilities=true;
      created=await call({method:'session.create',params:{source:'hermes_remote',profile:'default'}});
      if(typeof created?.session_id!=='string'||!created.session_id||typeof created?.stored_session_id!=='string'||!created.stored_session_id)throw new Error('session');
      checks.clarifyCreate=true;
      const accepted=await call({method:'prompt.submit',params:{session_id:created.session_id,text:CLARIFY_PROMPT}});
      if(accepted?.status!=='streaming')throw new Error('prompt');checks.clarifyAccepted=true;
      if(!await turn)throw new Error('turn');
      if(answerPromise)await answerPromise;
      if(!checks.clarifyAnswerAcknowledged||!checks.clarifyToolComplete||!streamMatches(stream)
        ||complete?.text!==CLARIFY_REPLY||['error','interrupted'].includes(complete?.status))throw new Error('stream');
      checks.clarifyStream=true;
      const history=await readHistory(created.stored_session_id);
      if(!Array.isArray(history?.messages))throw new Error('history');
      const matched=[];
      for(const [role,content] of [['user',CLARIFY_PROMPT],['assistant',CLARIFY_REPLY]]){
        const rows=history.messages.filter(m=>m?.role===role&&m.content===content);
        if(rows.length!==1||!Number.isSafeInteger(rows[0].id)||rows[0].id<1||!Number.isFinite(rows[0].timestamp)
          ||rows[0].timestamp<=0||rows[0].timestamp>=100_000_000_000)throw new Error('history');
        matched.push(rows[0].id);
      }
      if(matched[0]===matched[1])throw new Error('history_identity');
      checks.clarifyHistory=true;finish();
    }
    socket.on('error',finish);socket.on('close',finish);
    socket.on('unexpected-response',(_request,response)=>{response.resume();finish();});
    socket.on('message',raw=>{
      if(done)return;
      try{
        if(++events>512)throw new Error('event_limit');
        const frame=JSON.parse(raw.toString());if(frame?.jsonrpc!=='2.0')throw new Error('envelope');
        if(frame.method==='event'&&frame.params?.type==='gateway.ready'&&!started){
          started=true;checks.clarifyReady=true;void run().catch(finish);return;
        }
        if(pending.has(frame.id)){
          const request=pending.get(frame.id);pending.delete(frame.id);
          if(Object.hasOwn(frame,'error')||!Object.hasOwn(frame,'result'))request.reject(new Error('rpc'));
          else request.accept(frame.result);return;
        }
        if(!created)return;
        if(frame.method==='clarify'){
          const params=frame.params,questions=params?.questions;
          if(questionId||params?.session_id!==created.session_id||typeof frame.id!=='string'||!/^srq-[a-f0-9]{12}$/.test(frame.id)
            ||!Array.isArray(questions)||questions.length!==1||questions[0]?.question!==CLARIFY_QUESTION
            ||questions[0]?.qid!=='q0'||questions[0]?.multi_select===true
            ||!Array.isArray(questions[0]?.choices)||questions[0].choices[0]!==CLARIFY_ANSWER+' (Recommended)')throw new Error('question');
          questionId=frame.id;checks.clarifyQuestion=true;
          // A server request may arrive before prompt.submit's response. Correlate both calls.
          answerPromise=call(clarifyAnswerCall(frame.id,questions[0].qid)).then(result=>{
            if(result?.status!=='ok'||(!Array.isArray(result.remaining)||result.remaining.length!==0))throw new Error('answer');
            if(!done)checks.clarifyAnswerAcknowledged=true;
          }).catch(finish);
          return;
        }
        if(frame.method!=='event'||frame.params?.session_id!==created.session_id)return;
        const {type,payload}=frame.params;
        if(type==='tool.start'||type==='tool.complete'){
          if(payload?.name!=='clarify'||payload?.tool_id!==CLARIFY_CALL)throw new Error('tool_identity');
          if(type==='tool.start'){
            if(checks.clarifyToolStart)throw new Error('duplicate_tool');checks.clarifyToolStart=true;
          }else{
            if(!checks.clarifyToolStart||checks.clarifyToolComplete||!resultMatches(payload.result))throw new Error('tool_result');
            checks.clarifyToolComplete=true;
          }
        }
        if(type==='message.delta'){
          if(typeof payload?.text!=='string'||stream.length+payload.text.length>65536)throw new Error('delta');stream+=payload.text;
        }
        if(type==='message.complete'){
          if(complete)throw new Error('duplicate_complete');complete=payload;endTurn(true);
        }
      }catch{finish();}
    });
  });
}
