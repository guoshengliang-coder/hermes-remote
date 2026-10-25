import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { packageWebApp, writeTar } from '../package-web-app.mjs';
import { publishWebApp } from '../../deploy/publish-web-app.mjs';
import { collectWebReleaseEvidence } from '../hermes-compatibility-web-evidence.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
function fixture() {
  const files = [
    {path:'assets/app.js',data:Buffer.from('fixture javascript')},
    {path:'index.html',data:Buffer.from('<html>fixture</html>')},
    {path:'sw.js',data:Buffer.from('fixture service worker')},
  ];
  const archive = gzipSync(writeTar(files));
  const manifest = {schemaVersion:1,kind:'hermes-go-web-app-release-v1',version:'0.1.1',
    sourceCommit:'a'.repeat(40),releaseId:'0.1.1-aaaaaaaaaaaa',archiveFile:'Hermes-Web-0.1.1-aaaaaaaaaaaa.tar.gz',
    archiveSha256:hash(archive),files:files.map(f=>({path:f.path,size:f.data.length,sha256:hash(f.data)}))};
  return {files, input:{manifest,archive,origin:'https://releases.example',
    expected:{name:'web',version:manifest.version,artifactSha256:hash(archive)}}};
}
const failure = error => error.code === 'HR-HERMESCOMPAT-004'
  && error.retryable === false && error.action === 'review_report'
  && /验证证据/.test(error.message) && /evidence/.test(error.summaryEn);

test('web evidence binds the original package and every public file, with index checked again last', async () => {
  const {files,input} = fixture(), requests=[];
  const evidence = await collectWebReleaseEvidence(input, async (url,options) => {
    requests.push(url);
    assert.equal(options.redirect,'error'); assert.equal(options.credentials,'omit');
    assert.equal(options.headers.authorization,undefined); assert.equal(options.headers.cookie,undefined);
    assert.equal(options.headers['cache-control'],'no-cache'); assert.ok(options.signal);
    const path = url.endsWith('/app/') ? 'index.html' : url.split('/app/')[1];
    return new Response(files.find(f=>f.path===path).data);
  }, new Date('2026-10-25T08:00:00Z'));
  assert.deepEqual(evidence.component,input.expected);
  assert.equal(evidence.sourceCommit,input.manifest.sourceCommit);
  assert.equal(evidence.checkedFiles,3); assert.equal(evidence.kind,'published-web-readback');
  assert.equal(evidence.compatibilityApproved,false);
  assert.deepEqual(requests.map(u=>u.split('/app/')[1]),['','assets/app.js','sw.js','']);
  assert.equal(JSON.stringify(evidence).includes('fixture javascript'),false);
});

test('local identity and archive failures are refused before any network request', async () => {
  const {input} = fixture(); let calls=0;
  const fetcher=async()=>{calls++;throw new Error('unexpected');};
  await assert.rejects(collectWebReleaseEvidence(null,fetcher),failure);
  for(const patch of [
    {archive:Buffer.from('tampered')},
    {expected:{...input.expected,version:'0.1.2'}},
    {expected:{...input.expected,name:'connector'}},
    {manifest:{...input.manifest,files:input.manifest.files.slice(1)}},
    {manifest:{...input.manifest,files:input.manifest.files.map(f=>({...f,sha256:'0'.repeat(64)}))}},
    {origin:'https://user:secret@releases.example'},
  ]) await assert.rejects(collectWebReleaseEvidence({...input,...patch},fetcher),failure);
  assert.equal(calls,0);
});

test('an unchanged homepage cannot hide a missing, stale or oversized script', async () => {
  for(const response of [()=>new Response('missing',{status:404}),()=>new Response('wrong bytes'),
    ()=>new Response('x'.repeat(100)),()=>new Response(null,{status:302,headers:{location:'https://elsewhere.example/'}}),
    ()=>new Response('partial',{status:206})]) {
    const {input,files}=fixture();
    await assert.rejects(collectWebReleaseEvidence(input,async url=> url.endsWith('/app/')
      ? new Response(files[1].data) : response()),failure);
  }
});

test('a deployment switch during readback and transport diagnostics cannot produce evidence', async () => {
  const {input,files}=fixture(); let indexReads=0;
  await assert.rejects(collectWebReleaseEvidence(input,async url=>{
    if(url.endsWith('/app/'))return new Response(++indexReads===1?files[1].data:'changed deployment');
    return new Response(files.find(f=>url.endsWith(f.path)).data);
  }),failure);
  await assert.rejects(collectWebReleaseEvidence(input,async()=>{throw new Error('token=private-value');}),error=>{
    assert.equal(JSON.stringify(error).includes('private-value'),false);
    assert.equal(error.message.includes('private-value'),false);return failure(error);
  });
});

test('real package, local publication and HTTP readback detect a changed asset with the same homepage', async t => {
  const base=await mkdtemp(path.join(tmpdir(),'hermes-web-evidence-'));
  const dist=path.join(base,'dist'),out=path.join(base,'out'),root=path.join(base,'site');
  t.after(()=>rm(base,{recursive:true,force:true}));
  await mkdir(path.join(dist,'assets'),{recursive:true});await mkdir(out);
  await mkdir(path.join(root,'releases'),{recursive:true});
  await writeFile(path.join(dist,'index.html'),'<html>synthetic local release</html>');
  await writeFile(path.join(dist,'assets/app.js'),'synthetic local application');
  const packaged=await packageWebApp({distDir:dist,outputDir:out,version:'0.1.1',sourceCommit:'a'.repeat(40)});
  await publishWebApp({root,archivePath:packaged.archivePath,manifestPath:packaged.manifestPath});
  const server=createServer(async(req,res)=>{
    const file={'/app/':'index.html','/app/assets/app.js':'assets/app.js'}[req.url];
    if(!file){res.writeHead(404).end();return;}
    try{res.end(await readFile(path.join(root,'current',file)));}catch{res.writeHead(500).end();}
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));
  // Transport substitution is confined to this synthetic fixture; production still requires HTTPS.
  const fetcher=(url,options)=>fetch(`http://127.0.0.1:${server.address().port}${new URL(url).pathname}`,options);
  const input={manifest:packaged.manifest,archive:await readFile(packaged.archivePath),origin:'https://releases.example',
    expected:{name:'web',version:'0.1.1',artifactSha256:packaged.manifest.archiveSha256}};
  assert.equal((await collectWebReleaseEvidence(input,fetcher)).checkedFiles,2);
  await writeFile(path.join(root,'current/assets/app.js'),'different application');
  await assert.rejects(collectWebReleaseEvidence(input,fetcher),failure);
});

test('CLI failures serialize the bilingual evidence error without paths or raw diagnostics', () => {
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('../hermes-compatibility-web-evidence.mjs',import.meta.url)),
    '--manifest','/missing-private-fixture/manifest.json','--archive','/missing-private-fixture/archive.gz',
    '--origin','https://releases.example','--version','0.1.1','--sha256','a'.repeat(64)],{encoding:'utf8'});
  assert.equal(result.status,1);assert.equal(result.stdout,'');
  const error=JSON.parse(result.stderr);
  assert.equal(error.code,'HR-HERMESCOMPAT-004');assert.equal(error.retryable,false);
  assert.equal(error.action,'review_report');assert.match(error.summaryZh,/验证证据/);assert.match(error.summaryEn,/evidence/);
  assert.equal(result.stderr.includes('missing-private-fixture'),false);
});
