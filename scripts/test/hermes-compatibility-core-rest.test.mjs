import assert from 'node:assert/strict';
import test from 'node:test';
import {findSeedSessionPage,validateCoreStatus,validateCoreSessions,validateCoreHistory} from '../hermes-compatibility-contract-probe.mjs';
const session=()=>({id:'go-compat-lab-history',last_active:1792896000,message_count:1,profile:'default',is_default_profile:true,archived:false});
const history=()=>({messages:[{id:1,role:'user',content:'Hermes GO 升级前的合成会话',timestamp:1792896000.125}]});
test('core readers accept the consumed field types and optional metadata',()=>{
  validateCoreStatus({version:'0.22.5',gateway_running:false},'0.22.5');
  validateCoreSessions({sessions:[{...session(),title:null,model:'synthetic',unconsumed:'allowed'}]});
  validateCoreHistory(history());
  validateCoreHistory({...history(),pagination:{limit:1,offset:0,order:'oldest',returned:1}},{pagination:true});
  validateCoreHistory({messages:[],pagination:{limit:1,offset:1,order:'oldest',returned:0}},{pagination:true,empty:true,offset:1});
});
test('session ID rename, numeric/boolean coercion and duplicate/missing seeded rows are rejected',()=>{
  const renamed={...session(),session_id:session().id};delete renamed.id;
  for(const row of [renamed,{...session(),message_count:'1'},{...session(),last_active:'2026-10-25'},
    {...session(),archived:0},{...session(),is_default_profile:1},{...session(),profile:null},{...session(),model:123}])
    assert.throws(()=>validateCoreSessions({sessions:[row]}));
  for(const sessions of [[],[session(),session()]])assert.throws(()=>validateCoreSessions({sessions}));
});
test('timestamp regression cannot pass merely because history text remains present',()=>{
  const old=history(), renamed=history();renamed.messages[0].created_at=renamed.messages[0].timestamp;delete renamed.messages[0].timestamp;
  assert.equal(JSON.stringify(renamed).includes('Hermes GO 升级前的合成会话'),true);
  assert.throws(()=>validateCoreHistory(renamed));
  for(const patch of [{timestamp:'2026-10-25'},{timestamp:1792896000000},{id:'1'},{role:'assistant'},{content:'different'}])
    assert.throws(()=>validateCoreHistory({messages:[{...old.messages[0],...patch}]}));
});
test('ignored offsets and mismatched pagination metadata fail',()=>{
  assert.throws(()=>validateCoreHistory(history(),{empty:true,offset:1}));
  for(const pagination of [undefined,{limit:1,offset:0,order:'latest',returned:1},{limit:1,offset:1,order:'oldest',returned:1},
    {limit:100,offset:0,order:'oldest',returned:1},{limit:1,offset:0,order:'oldest',returned:0}])
    assert.throws(()=>validateCoreHistory({...history(),pagination},{pagination:true}));
  assert.throws(()=>validateCoreStatus({version:'0.22.5'},'0.22.5'));
  assert.throws(()=>validateCoreStatus({version:'0.21.3',gateway_running:false},'0.22.5'));
});

const recentPage=offset=>({sessions:Array.from({length:100},(_,i)=>({...session(),id:`recent-${offset+i}`}))});
test('reused lab seed beyond 100 recent sessions is found without deleting history',async()=>{
  assert.throws(()=>validateCoreSessions(recentPage(0)),'previous page-one check rejected an intact seed');
  const offsets=[];
  const page=await findSeedSessionPage(async offset=>{offsets.push(offset);return offset?{sessions:[session()]}:recentPage(0);});
  validateCoreSessions(page);assert.deepEqual(offsets,[0,100]);
});
test('seed search has bounded pages and rejects ignored offsets or malformed rows',async()=>{
  const offsets=[];
  await assert.rejects(findSeedSessionPage(async offset=>{offsets.push(offset);return recentPage(offset);}));
  assert.deepEqual(offsets,[0,100,200,300,400]);
  await assert.rejects(findSeedSessionPage(async()=>recentPage(0)),/offset_ignored/);
  for(const page of [{},{sessions:[null]},{sessions:[{id:1}]},{sessions:[]}])
    await assert.rejects(findSeedSessionPage(async()=>page));
});
