import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, readFile, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { writeTar } from '../package-web-app.mjs';
import { collectGatewayReleaseEvidence, gatewayIdentityFormat } from '../hermes-compatibility-gateway-evidence.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const json=value=>Buffer.from(JSON.stringify(value));
const failure=error=>error.code==='HR-HERMESCOMPAT-004'&&error.retryable===false
  &&error.action==='review_report'&&/验证证据/.test(error.message)&&/evidence/.test(error.summaryEn);

async function fixture(t){
  const root=await realpath(await mkdtemp(join(tmpdir(),'hermes-gateway-evidence-test-')));t.after(()=>rm(root,{recursive:true,force:true}));
  const version='0.4.27',commit='a'.repeat(40),reference=`hermes-remote-gateway:${version}-${commit.slice(0,12)}`;
  const config=json({architecture:'amd64',os:'linux',config:{WorkingDir:'/app',User:'node',Entrypoint:['docker-entrypoint.sh'],
    Cmd:['node','gateway/dist/index.js'],Labels:{'org.opencontainers.image.version':version,'org.opencontainers.image.revision':commit}},
    rootfs:{type:'layers',diff_ids:[]}});
  const configID=`sha256:${hash(config)}`,layer=Buffer.from('synthetic layer; never loaded or executed');
  const image=json({schemaVersion:2,mediaType:'application/vnd.oci.image.manifest.v1+json',
    config:{mediaType:'application/vnd.oci.image.config.v1+json',digest:configID,size:config.length},
    layers:[{mediaType:'application/vnd.oci.image.layer.v1.tar',digest:`sha256:${hash(layer)}`,size:layer.length}]});
  const imageID=`sha256:${hash(image)}`;
  const archive=writeTar([
    {path:'manifest.json',data:json([{Config:`blobs/sha256/${hash(config)}`,RepoTags:[reference],Layers:[`blobs/sha256/${hash(layer)}`]}])},
    {path:'index.json',data:json({schemaVersion:2,mediaType:'application/vnd.oci.image.index.v1+json',
      manifests:[{mediaType:'application/vnd.oci.image.manifest.v1+json',digest:imageID,size:image.length}]})},
    {path:`blobs/sha256/${hash(config)}`,data:config},{path:`blobs/sha256/${hash(image)}`,data:image},
    {path:`blobs/sha256/${hash(layer)}`,data:layer},
  ]);
  const manifest={schemaVersion:3,kind:'hermes-go-gateway-oci',serverVersion:version,sourceCommit:commit,
    imageReference:reference,imageId:configID,containerdImageId:imageID,architecture:'amd64',
    archiveFile:`Hermes-Gateway-${version}-${commit.slice(0,12)}-linux-amd64.tar`,archiveSha256:hash(archive),
    createdAt:'2026-10-25T00:00:00Z',releaseContract:JSON.parse(await readFile(new URL('../../gateway/release-contract.json',import.meta.url)))};
  const manifestPath=join(root,'manifest.json'),archivePath=join(root,manifest.archiveFile);
  await writeFile(manifestPath,json(manifest));await writeFile(archivePath,archive);
  const runtime={id:'b'.repeat(64),image:configID,running:true,startedAt:'2026-10-25T01:00:00.000000000Z',restartCount:0,
    readonly:true,privileged:false,path:'docker-entrypoint.sh',args:['node','gateway/dist/index.js'],workingDir:'/app',user:'node',
    mounts:['/tmp','/run/hermes-go/secrets','/var/lib/hermes-go','/opt/hermes-go/web'],loader:'path'};
  const input={manifestPath,containerName:'hermes-fixture-blue',origin:'https://gateway.example',
    expected:{name:'gateway',version,artifactSha256:manifest.archiveSha256}};
  return {root,input,manifest,manifestPath,archivePath,runtime};
}
const response=value=>({status:0,stdout:JSON.stringify(value),stderr:''});

test('real OCI tar inspection binds expected archive, both image IDs and stable restricted runtime',async t=>{
  const f=await fixture(t),calls=[];
  const evidence=await collectGatewayReleaseEvidence(f.input,(cmd,args)=>{calls.push([cmd,args]);return response(f.runtime);},new Date('2026-10-25T02:00:00Z')).catch(error=>{assert.fail(JSON.stringify(error.details));});
  assert.deepEqual(evidence.component,f.input.expected);assert.equal(evidence.imageId,f.manifest.imageId);
  assert.equal(evidence.containerdImageId,f.manifest.containerdImageId);assert.equal(evidence.sourceCommit,f.manifest.sourceCommit);
  assert.equal(evidence.runningImageVerified,true);assert.equal(evidence.publicRouteVerified,false);assert.equal(evidence.compatibilityApproved,false);
  assert.equal(calls.length,2);
  for(const [cmd,args]of calls){assert.equal(cmd,'docker');assert.deepEqual(args,['container','inspect','--format',gatewayIdentityFormat,f.input.containerName]);}
  for(const privateValue of [f.root,f.input.containerName,f.runtime.id])assert.equal(JSON.stringify(evidence).includes(privateValue),false);
});

test('archive, expected identity and descriptor mismatches fail before Docker observation',async t=>{
  const f=await fixture(t);let calls=0;const run=()=>{calls++;return response(f.runtime);};
  for(const patch of [{expected:{...f.input.expected,version:'9.9.9'}},{expected:{...f.input.expected,name:'desktop'}},
    {containerName:'--format=secret'},{origin:'https://user:secret@gateway.example'}])
    await assert.rejects(collectGatewayReleaseEvidence({...f.input,...patch},run),failure);
  await writeFile(f.manifestPath,json({...f.manifest,containerdImageId:`sha256:${'c'.repeat(64)}`}));
  await assert.rejects(collectGatewayReleaseEvidence(f.input,run),failure);
  await writeFile(f.manifestPath,json(f.manifest));await writeFile(f.archivePath,'changed original archive');
  await assert.rejects(collectGatewayReleaseEvidence(f.input,run),failure);assert.equal(calls,0);
});

test('different image, mutable root, privilege, loader overrides and code mounts cannot identify runtime',async t=>{
  const f=await fixture(t);
  for(const patch of [{image:`sha256:${'c'.repeat(64)}`},{running:false},{readonly:false},{privileged:true},
    {loader:'blockedpath'},{loader:''},{loader:'pathpath'},{path:'sh'},{args:['node','other.js']},{user:'root'},
    {workingDir:'/tmp'},{mounts:['/']},{mounts:['/app/gateway']},{mounts:['/usr/local/bin']},{mounts:['/app/../tmp']},
    {restartCount:-1},{startedAt:'unknown'}])
    await assert.rejects(collectGatewayReleaseEvidence(f.input,()=>response({...f.runtime,...patch})),error=>failure(error)&&error.details.stage==='runtime_identity');
});

test('restart or container replacement during observation is rejected; containerd identity is supported',async t=>{
  const f=await fixture(t);
  for(const patch of [{id:'c'.repeat(64)},{startedAt:'2026-10-25T03:00:00Z'},{restartCount:1}]){
    let count=0;
    await assert.rejects(collectGatewayReleaseEvidence(f.input,()=>response(++count===1?f.runtime:{...f.runtime,...patch})),error=>failure(error)&&error.details.stage==='runtime_recheck');
  }
  assert.equal((await collectGatewayReleaseEvidence(f.input,()=>response({...f.runtime,image:f.manifest.containerdImageId}))).runningImageVerified,true);
});

test('inspection errors and unbounded/raw output cannot leak credentials into a report',async t=>{
  const f=await fixture(t);
  for(const result of [{status:1,stdout:'',stderr:'token=private-value'},
    {status:0,stdout:'private-value'}, {status:0,stdout:'x'.repeat(128*1024+1)},
    response({...f.runtime,environment:'private-value'})]){
    await assert.rejects(collectGatewayReleaseEvidence(f.input,()=>result),error=>{
      assert.equal(JSON.stringify(error).includes('private-value'),false);return failure(error);
    });
  }
  assert.equal(gatewayIdentityFormat.includes('json .Config.Env'),false);
  assert.equal(gatewayIdentityFormat.includes('.Source'),false);
  assert.equal(gatewayIdentityFormat.includes('json .Mounts'),false);
});

test('CLI failures expose only the bilingual structured evidence error',()=>{
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('../hermes-compatibility-gateway-evidence.mjs',import.meta.url)),
    '--manifest','/private-fixture/missing.json','--container','synthetic','--origin','https://gateway.example',
    '--version','0.4.27','--sha256','a'.repeat(64)],{encoding:'utf8'});
  assert.equal(result.status,1);assert.equal(result.stdout,'');const error=JSON.parse(result.stderr);
  assert.equal(error.code,'HR-HERMESCOMPAT-004');assert.match(error.summaryZh,/验证证据/);assert.match(error.summaryEn,/evidence/);
  assert.equal(error.retryable,false);assert.equal(error.action,'review_report');assert.equal(result.stderr.includes('private-fixture'),false);
});
