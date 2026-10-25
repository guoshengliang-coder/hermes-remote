import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync } from 'node:crypto';
import { prepareReleaseInventory } from '../hermes-compatibility-inventory.mjs';
import { collectClientReleaseEvidence } from '../hermes-compatibility-client-evidence.mjs';
import { CompatibilitySigner, releasedInventory, parseReleaseInventory, verifyCompatibilityEnvelope, parseRecommendation } from '../../gateway/dist/account/hermes-compatibility-publication.js';
import { COMPONENT_NAMES, compatibilityDigest } from '../../gateway/dist/account/hermes-compatibility-model.js';

const now=new Date('2026-10-25T12:00:00Z'),clock=()=>now;
const origin='https://mrlgs.net';
const hash=value=>createHash('sha256').update(value).digest('hex');
const kinds={desktop:'pinned-public-client-archive',android:'pinned-public-client-archive',connector:'published-signed-connector-artifact',gateway:'gateway-oci-runtime-observation',web:'published-web-readback'};
const components=COMPONENT_NAMES.map(name=>({name,version:'1.0.0',artifactSha256:hash(name)}));
function fixture() {
  const sources={desktop:{manifest:{}},android:{manifest:{}},connector:{releaseVersion:'1.0.0',layout:'bundled',architecture:'arm64',channel:'internal',keyId:'test',publicKey:'test'},
    gateway:{manifestPath:'/synthetic/gateway.json',containerName:'synthetic'},web:{manifestPath:'/synthetic/web.json',archivePath:'/synthetic/web.tar.gz'}};
  const policy={schemaVersion:1,origin,combinations:[{components:structuredClone(components),sources}]};
  const calls=[];
  const collectors=Object.fromEntries(COMPONENT_NAMES.map(name=>[name,async(input,_fetcher,at)=>{
    calls.push(name);return {schemaVersion:1,kind:kinds[name],component:input.expected,origin:input.origin,
      observedAt:at.toISOString().replace('.000Z','Z'),compatibilityApproved:false,
      ...(name==='connector'?{releaseExpiresAt:'2026-10-25T13:00:00Z'}:{}),...(name==='gateway'?{runningImageVerified:true}:{}),
      privateLog:'synthetic-private'};
  }]));
  return {policy,collectors,calls};
}
const refused=error=>error.code==='HR-HERMESCOMPAT-004'&&error.retryable===false&&error.action==='review_report';

test('five-source preparation signs and verifies an exact bounded inventory without approving Hermes',async()=>{
  const f=fixture(),draft=await prepareReleaseInventory(f.policy,{collectors:f.collectors,clock});
  assert.deepEqual(f.calls,COMPONENT_NAMES);assert.equal(draft.payload.expiresAt,'2026-10-25T13:00:00Z');
  assert.equal(draft.payloadSha256,compatibilityDigest(draft.payload));
  assert.equal(JSON.stringify(draft).includes('synthetic-private'),false);assert.equal(JSON.stringify(draft).includes('/synthetic'),false);
  const pair=generateKeyPairSync('ed25519');
  const signer=new CompatibilitySigner('inventory-test',pair.privateKey.export({format:'pem',type:'pkcs8'}),origin);
  const signed=signer.signInventory(draft.payload,now),keys=new Map([[signer.id,pair.publicKey]]);
  assert.deepEqual(releasedInventory(signed,keys,origin,{components},now),components);
  assert.deepEqual(parseReleaseInventory(verifyCompatibilityEnvelope(signed,keys),origin,now),draft.payload);
  assert.throws(()=>parseRecommendation(verifyCompatibilityEnvelope(signed,keys),origin,now));
  assert.throws(()=>releasedInventory(signed,keys,origin,{components},new Date(draft.payload.expiresAt)));
  assert.throws(()=>signer.signInventory({...draft.payload,combinations:[components,components]},now));
  assert.throws(()=>signer.signInventory({...draft.payload,combinations:[components.slice(1)]},now));
  assert.throws(()=>signer.signInventory({...draft.payload,expiresAt:'2026-10-27T12:00:00Z'},now));
});

test('all policy shapes are checked before collection; empty policy can withdraw every combination',async()=>{
  for(const change of [p=>{p.combinations[0].components.pop();},p=>{p.combinations.push(structuredClone(p.combinations[0]));},
    p=>{p.combinations[0].sources.web.archivePath='relative';},p=>{p.combinations[0].sources.desktop.proof={passed:true};},
    p=>{p.combinations[0].sources.connector.publicKey='test';p.combinations[0].sources.extra={};}]) {
    const f=fixture();change(f.policy);await assert.rejects(prepareReleaseInventory(f.policy,{collectors:f.collectors,clock}),refused);assert.equal(f.calls.length,0);
  }
  const f=fixture();f.policy.combinations=[];
  const draft=await prepareReleaseInventory(f.policy,{collectors:f.collectors,clock});
  assert.deepEqual(draft.payload.combinations,[]);assert.deepEqual(draft.evidence,[]);assert.equal(f.calls.length,0);
});

test('every missing, wrong or failed source refuses the entire inventory',async()=>{
  for(const name of COMPONENT_NAMES)for(const mutation of [r=>({...r,component:{...r.component,artifactSha256:'0'.repeat(64)}}),
    r=>({...r,origin:'https://elsewhere.example'}),r=>({...r,compatibilityApproved:true}),r=>({...r,kind:'untrusted-report'}),()=>null]) {
    const f=fixture(),collect=f.collectors[name];f.collectors[name]=async(...args)=>mutation(await collect(...args));
    await assert.rejects(prepareReleaseInventory(f.policy,{collectors:f.collectors,clock}),refused);
  }
  const f=fixture();f.collectors.web=async()=>{throw new Error('secret=synthetic-private');};
  await assert.rejects(prepareReleaseInventory(f.policy,{collectors:f.collectors,clock}),error=>{
    assert.equal(JSON.stringify(error).includes('synthetic-private'),false);assert.equal(error.message.includes('synthetic-private'),false);return refused(error);
  });
});

test('stale/future observations and expired release authority cannot be renewed into a fresh inventory',async()=>{
  for(const patch of [{observedAt:'2026-10-25T11:44:59Z'},{observedAt:'2026-10-25T12:00:31Z'},
    {observedAt:'2026-02-30T12:00:00Z'},{releaseExpiresAt:'2026-10-25T12:00:00Z'},{releaseExpiresAt:'invalid'}]) {
    const f=fixture(),collect=f.collectors.connector;f.collectors.connector=async(...args)=>({...await collect(...args),...patch});
    await assert.rejects(prepareReleaseInventory(f.policy,{collectors:f.collectors,clock}),refused);
  }
  const f=fixture();await prepareReleaseInventory(f.policy,{collectors:f.collectors,clock});
  await prepareReleaseInventory(f.policy,{collectors:f.collectors,clock});
  assert.equal(f.calls.length,10,'a new prepare recollects every source; no persisted observations are accepted');
});

function clientFixture(name) {
  const bytes=Buffer.from('synthetic released client archive'),expected={name,version:'1.0.0',artifactSha256:hash(bytes)};
  const common={sizeBytes:bytes.length,sha256:hash(bytes),sourceCommit:'a'.repeat(40),releaseNotes:[]};
  const manifest=name==='desktop'?{...common,schemaVersion:1,channel:'internal',architecture:'arm64',appVersion:'1.0.0',buildNumber:1,
    minimumMacOS:'14.0',downloadURL:origin+'/desktop/apps/1.0.0/Hermes-Go-Desktop-1.0.0.dmg',updatedAt:'2026-10-25T11:00:00Z'}:
    {...common,versionName:'1.0.0',versionCode:1,applicationId:'com.hermes.remote',channel:'internal',minSdk:26,publishedAt:'2026-10-25T11:00:00Z',
      certificateSha256:'06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
      fileName:'Hermes-Remote-1.0.0-debug.apk',downloadUrl:origin+'/releases/Hermes-Remote-1.0.0-debug.apk'};
  return {input:{origin,expected,manifest},bytes};
}
test('original Desktop and Android metadata bind pinned complete public bytes, without package-signing claims',async()=>{
  for(const name of ['desktop','android']) {
    const f=clientFixture(name);let calls=0;
    const result=await collectClientReleaseEvidence(f.input,async(url,options)=>{
      calls++;assert.equal(options.credentials,'omit');assert.equal(options.redirect,'error');assert.ok(options.signal);
      assert.equal(url,f.input.manifest.downloadURL??f.input.manifest.downloadUrl);return new Response(f.bytes);
    },now);
    assert.equal(calls,1);assert.deepEqual(result.component,f.input.expected);assert.equal(result.compatibilityApproved,false);
    assert.equal(result.kind,'pinned-public-client-archive');assert.equal(result.signatureVerified,undefined);
    const p=fixture();p.policy.combinations[0].components.find(c=>c.name===name).artifactSha256=f.input.expected.artifactSha256;
    p.policy.combinations[0].sources[name].manifest=f.input.manifest;delete p.collectors[name];
    const draft=await prepareReleaseInventory(p.policy,{collectors:p.collectors,clock,fetcher:async()=>new Response(f.bytes)});
    assert.equal(draft.evidence.find(e=>e.component.name===name).kind,'pinned-public-client-archive');
  }
});

test('client metadata failures prevent download and partial/changed/redirected archives are rejected',async()=>{
  for(const name of ['desktop','android']) {
    const f=clientFixture(name);
    for(const patch of [{sha256:'a'.repeat(64)},{sizeBytes:0},{sourceCommit:'bad'},{channel:'wrong'},
      {[name==='desktop'?'appVersion':'versionName']:'2.0.0'},
      {[name==='desktop'?'updatedAt':'publishedAt']:'2026-10-25T13:00:00Z'},
      {[name==='desktop'?'downloadURL':'downloadUrl']:'https://elsewhere.example/archive'}]) {
      let calls=0;await assert.rejects(collectClientReleaseEvidence({...f.input,manifest:{...f.input.manifest,...patch}},async()=>{calls++;return new Response(f.bytes);},now),refused);
      assert.equal(calls,0);
    }
    for(const response of [()=>new Response('truncated'),()=>new Response('x'.repeat(100)),()=>new Response(f.bytes,{status:206}),
      ()=>new Response(null,{status:302}),()=>{const r=new Response(f.bytes);Object.defineProperty(r,'redirected',{value:true});return r;}])
      await assert.rejects(collectClientReleaseEvidence(f.input,async()=>response(),now),refused);
  }
});

test('shared sources are reused only inside one preparation and slow collection expires earlier observations',async()=>{
  const f=fixture(),second=structuredClone(f.policy.combinations[0]);second.components[0].artifactSha256='1'.repeat(64);
  f.policy.combinations.push(second);
  const result=await prepareReleaseInventory(f.policy,{collectors:f.collectors,clock});
  assert.equal(result.payload.combinations.length,2);assert.equal(f.calls.length,6);assert.equal(result.evidence.length,6);
  const slow=fixture();let count=0;
  await assert.rejects(prepareReleaseInventory(slow.policy,{collectors:slow.collectors,
    clock:()=>new Date(now.getTime()+(++count>5?16*60_000:0))}),refused);
  const stopped=fixture(),collect=stopped.collectors.gateway;
  stopped.collectors.gateway=async(...args)=>({...await collect(...args),runningImageVerified:false});
  await assert.rejects(prepareReleaseInventory(stopped.policy,{collectors:stopped.collectors,clock}),refused);
});
