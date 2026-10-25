import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { createServer } from 'node:http';
import { collectConnectorReleaseEvidence } from '../hermes-compatibility-connector-evidence.mjs';

const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const now=new Date('2026-10-25T12:00:00Z');
function fixture(layout='bundled') {
  const {privateKey,publicKey}=generateKeyPairSync('ed25519');
  const origin='https://releases.example',releaseVersion='0.4.12';
  // Synthetic bytes test readback identity, not archive execution or installation.
  const archive=Buffer.from('synthetic original connector package');
  const prefix=`${origin}/desktop/${layout==='bundled'?'releases':'components'}/${releaseVersion}/`;
  const fileName=layout==='bundled'?'Hermes-Connector-0.1.17-arm64.tar.gz':'Hermes-Component-connector-0.1.17-arm64.tar.gz';
  const connector={version:'0.1.17',fileName,entrypoint:'bin/hermes-connector',downloadURL:prefix+fileName,sizeBytes:archive.length,sha256:hash(archive)};
  const runtime=layout==='bundled'?{component:'hermes_server',version:'0.21.0',fileName:'Hermes-Server-0.21.0-arm64.tar.gz',
    entrypoint:'bin/hermes-server',downloadURL:prefix+'Hermes-Server-0.21.0-arm64.tar.gz',sizeBytes:1,sha256:'b'.repeat(64)}:
    {kind:'node_runtime',version:'22.0.0',fileName:'Hermes-Component-node_runtime-22.0.0-arm64.tar.gz',
      entrypoint:'bin/node',downloadURL:prefix+'Hermes-Component-node_runtime-22.0.0-arm64.tar.gz',sizeBytes:1,sha256:'b'.repeat(64),
      architecture:'arm64',installPhase:'bootstrap',requiredForBootstrap:true,reuseContract:'exact_content',contentSHA256:'c'.repeat(64),dependencies:[]};
  Object.assign(connector,layout==='bundled'?{component:'connector'}:{kind:'connector',architecture:'arm64',installPhase:'bootstrap',
    requiredForBootstrap:true,reuseContract:'exact_content',contentSHA256:'d'.repeat(64),dependencies:[{kind:'node_runtime',contentSHA256:'c'.repeat(64)}]});
  const payload={schemaVersion:layout==='bundled'?1:2,releaseVersion,channel:'internal',platform:'macos',architecture:'arm64',
    minimumMacOS:'14.0',createdAt:'2026-10-24T12:00:00Z',expiresAt:'2026-10-26T12:00:00Z',
    [layout==='bundled'?'artifacts':'components']:[runtime,connector]};
  function envelope(value=payload) {
    const bytes=Buffer.from(typeof value==='string'?value:JSON.stringify(value));
    return {payload:bytes.toString('base64url'),algorithm:'Ed25519',keyId:'test-key',signature:sign(null,bytes,privateKey).toString('base64url')};
  }
  const input={origin,releaseVersion,layout,architecture:'arm64',channel:'internal',keyId:'test-key',
    publicKey:publicKey.export({format:'jwk'}).x,expected:{name:'connector',version:'0.1.17',artifactSha256:hash(archive)}};
  const requests=[];
  const fetcher=async(url,options)=>{requests.push({url,options});return new Response(url.endsWith('.json')?JSON.stringify(envelope()):archive);};
  return {input,payload,connector,archive,envelope,fetcher,requests};
}
const refused=error=>error.code==='HR-HERMESCOMPAT-004'&&error.retryable===false&&error.action==='review_report'
  &&/验证证据/.test(error.message)&&/evidence/.test(error.summaryEn);

test('both signed release layouts bind Connector identity and stream the full artifact without credentials',async()=>{
  for(const layout of ['bundled','components']) {
    const f=fixture(layout),evidence=await collectConnectorReleaseEvidence(f.input,f.fetcher,now);
    assert.deepEqual(evidence.component,f.input.expected);assert.equal(evidence.releaseVersion,'0.4.12');
    assert.equal(evidence.kind,'published-signed-connector-artifact');assert.equal(evidence.compatibilityApproved,false);
    assert.equal(evidence.manifestSha256,hash(JSON.stringify(f.envelope())));
    assert.equal(f.requests.length,3);assert.equal(f.requests[0].url,f.requests[2].url);
    assert.equal(f.requests[1].url,f.connector.downloadURL);
    for(const {options} of f.requests) {
      assert.equal(options.credentials,'omit');assert.equal(options.redirect,'error');assert.equal(options.cache,'no-store');
      assert.equal(options.headers.authorization,undefined);assert.equal(options.headers.cookie,undefined);assert.ok(options.signal);
    }
    assert.equal(JSON.stringify(evidence).includes('synthetic original'),false);
  }
});

test('invalid trust and target inputs fail before network access',async()=>{
  const f=fixture();let requests=0;const fetcher=async()=>{requests++;throw 0;};
  for(const patch of [{origin:'http://releases.example'},{origin:'https://user:password@releases.example'},
    {releaseVersion:'../x'},{layout:'unknown'},{architecture:'unknown'},{keyId:''},{publicKey:'x'},
    {expected:{...f.input.expected,name:'desktop'}},{expected:{...f.input.expected,artifactSha256:'bad'}}]) {
    await assert.rejects(collectConnectorReleaseEvidence({...f.input,...patch},fetcher,now),refused);
  }
  await assert.rejects(collectConnectorReleaseEvidence(null,fetcher,now),refused);assert.equal(requests,0);
});

test('signature, expiry, scope and exact component identity failures prevent archive requests',async()=>{
  for(const layout of ['bundled','components'])for(const mutation of [
    f=>{f.payload.releaseVersion='0.4.13';},f=>{f.payload.expiresAt=now.toISOString();},
    f=>{f.payload.createdAt='2026-10-25T12:01:00Z';},f=>{f.payload.channel='stable';},
    f=>{f.payload.platform='linux';},f=>{f.payload.architecture='x86_64';},
    f=>{f.connector.version='0.1.18';},f=>{f.connector.sha256='a'.repeat(64);},
    f=>{f.connector.downloadURL='https://outside.example/'+f.connector.fileName;},
    f=>{f.connector.downloadURL=f.connector.downloadURL.replace('/0.4.12/','/0.4.11/');},
    f=>{f.connector.downloadURL+='?token=synthetic-private';},
  ]) {
    const f=fixture(layout);mutation(f);
    await assert.rejects(collectConnectorReleaseEvidence(f.input,f.fetcher,now),refused);
    assert.equal(f.requests.length,1);
  }
  for(const transform of [e=>({...e,signature:'a'.repeat(86)}),e=>({...e,keyId:'other'}),e=>({...e,algorithm:'other'}),
    e=>({...e,payload:Buffer.from('{}').toString('base64url')})]) {
    const f=fixture();let count=0;
    await assert.rejects(collectConnectorReleaseEvidence(f.input,async()=>{count++;return new Response(JSON.stringify(transform(f.envelope())));},now),refused);
    assert.equal(count,1);
  }
  const f=fixture();
  const duplicate=JSON.stringify(f.payload).replace('"schemaVersion":1','"schemaVersion":1,"schemaVersion":1');
  await assert.rejects(collectConnectorReleaseEvidence(f.input,async()=>new Response(JSON.stringify(f.envelope(duplicate))),now),refused);
});

test('truncated, changed, oversized, redirected and partial artifacts cannot become published evidence',async()=>{
  for(const artifact of [()=>new Response('wrong'),()=>new Response('x'.repeat(100)),()=>new Response(null,{status:302}),
    ()=>new Response('partial',{status:206}),()=>new Response(null,{status:404}),
    ()=>{const r=new Response('wrong');Object.defineProperty(r,'redirected',{value:true});return r;}]) {
    const f=fixture();
    await assert.rejects(collectConnectorReleaseEvidence(f.input,async url=>url.endsWith('.json')
      ?new Response(JSON.stringify(f.envelope())):artifact(),now),refused);
  }
  const f=fixture();let manifests=0;
  await assert.rejects(collectConnectorReleaseEvidence(f.input,async url=>{
    if(!url.endsWith('.json'))return new Response(f.archive);
    return new Response(JSON.stringify(f.envelope())+(++manifests===2?' ':''));
  },now),refused);
  await assert.rejects(collectConnectorReleaseEvidence(f.input,async()=>new Response('x'.repeat(256*1024+1)),now),refused);
});

test('raw transport diagnostics remain private',async()=>{
  const f=fixture();
  await assert.rejects(collectConnectorReleaseEvidence(f.input,async()=>{throw new Error('token=synthetic-private');},now),error=>{
    assert.equal(JSON.stringify(error).includes('synthetic-private'),false);assert.equal(error.message.includes('synthetic-private'),false);
    return refused(error);
  });
});

test('real HTTP readback detects replacement of a signed artifact',async t=>{
  const f=fixture();let replaced=false;
  const server=createServer((req,res)=>{
    res.writeHead(200);res.end(req.url.endsWith('.json')?JSON.stringify(f.envelope()):replaced?'replaced':f.archive);
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));
  // Only the test transport substitutes loopback HTTP. Production accepts pinned HTTPS origins.
  const fetcher=(url,options)=>fetch(`http://127.0.0.1:${server.address().port}${new URL(url).pathname}`,options);
  assert.deepEqual((await collectConnectorReleaseEvidence(f.input,fetcher,now)).component,f.input.expected);
  replaced=true;await assert.rejects(collectConnectorReleaseEvidence(f.input,fetcher,now),refused);
});
