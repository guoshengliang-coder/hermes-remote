// Read-only OCI/runtime observation for the protected release evidence pipeline. This does not
// establish which container receives public traffic, sign an inventory or approve Hermes.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { constants, createWriteStream } from 'node:fs';
import { open, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { loadBundleManifest } from '../ops/lib/config.mjs';
import { inspectGatewayArchiveIdentity } from './lib/gateway-archive-identity.mjs';
import { compatibilityErrors, compatibilityHash, compatibilityVersion } from '../gateway/dist/account/hermes-compatibility-model.js';
import { compatibilityOrigin } from '../gateway/dist/account/hermes-compatibility-publication.js';

const LIMIT = 2 * 1024 * 1024 * 1024;
const PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';
// Do not ask Docker for full inspect/config/environment output: it includes live credentials.
// Only mount destinations and closed loader flags leave Docker's formatting step.
export const gatewayIdentityFormat = '{"id":{{json .Id}},"image":{{json .Image}},"running":{{json .State.Running}},'
  + '"startedAt":{{json .State.StartedAt}},"restartCount":{{json .RestartCount}},'
  + '"readonly":{{json .HostConfig.ReadonlyRootfs}},"privileged":{{json .HostConfig.Privileged}},'
  + '"path":{{json .Path}},"args":{{json .Args}},"workingDir":{{json .Config.WorkingDir}},"user":{{json .Config.User}},'
  + '"mounts":[{{range $i,$m := .Mounts}}{{if $i}},{{end}}{{json $m.Destination}}{{end}}],'
  + '"loader":"{{range .Config.Env}}{{$p := split . "="}}{{if or (eq (index $p 0) "NODE_OPTIONS") (eq (index $p 0) "NODE_PATH") (eq (index $p 0) "LD_PRELOAD") (eq (index $p 0) "LD_LIBRARY_PATH")}}blocked{{end}}{{if eq (index $p 0) "PATH"}}{{if ne . "PATH=' + PATH + '"}}blocked{{end}}path{{end}}{{end}}"}';

function command(executable, args) {
  return spawnSync(executable, args, {encoding:'utf8', shell:false, timeout:30_000,
    maxBuffer:2*1024*1024, stdio:['ignore','pipe','pipe']});
}
function extract(archive, name) {
  const result=command('tar',['-xOf',archive,name]);
  if(result.status!==0||result.error||typeof result.stdout!=='string')throw 0;
  return Buffer.from(result.stdout);
}

async function privateArchive(source, destination, expectedHash) {
  const handle=await open(source,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
    const before=await handle.stat();
    if(!before.isFile()||before.size<1||before.size>LIMIT)throw 0;
    const hash=createHash('sha256');let count=0;
    const bounded=new Transform({transform(chunk,_encoding,done){
      count+=chunk.length;
      if(count>before.size){done(new Error('archive_changed'));return;}
      hash.update(chunk);done(null,chunk);
    }});
    await pipeline(handle.createReadStream({autoClose:false}),bounded,
      createWriteStream(destination,{flags:'wx',mode:0o600}),{signal:AbortSignal.timeout(120_000)});
    const after=await handle.stat();
    if(count!==before.size||hash.digest('hex')!==expectedHash||after.size!==before.size
        ||after.mtimeMs!==before.mtimeMs||after.ctimeMs!==before.ctimeMs)throw 0;
  } finally { await handle.close(); }
}

function runtime(raw, manifest) {
  if(!raw||Object.keys(raw).sort().join(',')!=='args,id,image,loader,mounts,path,privileged,readonly,restartCount,running,startedAt,user,workingDir'
      ||!/^[a-f0-9]{64}$/.test(raw.id)||![manifest.imageId,manifest.containerdImageId].includes(raw.image)
      ||raw.running!==true||raw.readonly!==true||raw.privileged!==false
      ||!Number.isSafeInteger(raw.restartCount)||raw.restartCount<0
      ||typeof raw.startedAt!=='string'||!Number.isFinite(Date.parse(raw.startedAt))
      ||raw.path!=='docker-entrypoint.sh'||JSON.stringify(raw.args)!=='["node","gateway/dist/index.js"]'
      ||raw.workingDir!=='/app'||raw.user!=='node'||raw.loader!=='path'
      ||!Array.isArray(raw.mounts)||raw.mounts.length>32)throw 0;
  for(const destination of raw.mounts){
    if(typeof destination!=='string'||!destination.startsWith('/')||destination==='/'
        ||resolve(destination)!==destination||/[\x00-\x20\x7f]/.test(destination)
        ||['/app','/usr','/bin','/sbin','/lib','/lib64','/etc'].some(root=>destination===root||destination.startsWith(root+'/')||root.startsWith(destination+'/')))throw 0;
  }
  return raw;
}

export async function collectGatewayReleaseEvidence(input, run=command, now=new Date()) {
  let stage='bundle',workspace;
  try {
    const {manifestPath,expected,containerName,origin}=input;
    compatibilityOrigin(origin);
    if(!expected||Object.keys(expected).sort().join(',')!=='artifactSha256,name,version'||expected.name!=='gateway'
        ||typeof manifestPath!=='string'||!manifestPath.startsWith('/')
        ||typeof containerName!=='string'||!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(containerName)
        ||!(now instanceof Date)||!Number.isFinite(now.getTime()))throw 0;
    compatibilityVersion(expected.version);compatibilityHash(expected.artifactSha256);
    stage='manifest';
    const manifest=await loadBundleManifest(manifestPath,{verifyArchive:false});
    if(manifest.schemaVersion!==3||manifest.serverVersion!==expected.version||manifest.archiveSha256!==expected.artifactSha256)throw 0;
    stage='prepare_reference';
    workspace=await mkdtemp(join(tmpdir(),'hermes-gateway-evidence-'));
    const archive=join(workspace,'reference.tar');
    stage='copy_archive';
    await privateArchive(manifest.archivePath,archive,expected.artifactSha256);
    stage='archive_identity';
    const identity=inspectGatewayArchiveIdentity(archive,manifest.imageReference,manifest.imageId,{extractEntry:extract});
    if(identity.containerdImageId!==manifest.containerdImageId)throw 0;
    const config=JSON.parse(extract(archive,`blobs/sha256/${manifest.imageId.slice(7)}`));
    if(config.config?.WorkingDir!=='/app'||config.config.User!=='node'
        ||JSON.stringify(config.config.Entrypoint)!=='["docker-entrypoint.sh"]'
        ||JSON.stringify(config.config.Cmd)!=='["node","gateway/dist/index.js"]'
        ||config.config.Labels?.['org.opencontainers.image.version']!==expected.version
        ||config.config.Labels?.['org.opencontainers.image.revision']!==manifest.sourceCommit)throw 0;
    stage='runtime_identity';
    function inspect(){
      const result=run('docker',['container','inspect','--format',gatewayIdentityFormat,containerName]);
      if(result.status!==0||result.error||typeof result.stdout!=='string'||Buffer.byteLength(result.stdout)>128*1024)throw 0;
      return runtime(JSON.parse(result.stdout),manifest);
    }
    const before=inspect();
    stage='runtime_recheck';
    const after=inspect();
    if(JSON.stringify(before)!==JSON.stringify(after))throw 0;
    stage='cleanup';await rm(workspace,{recursive:true});workspace=undefined;
    return {schemaVersion:1,kind:'gateway-oci-runtime-observation',origin,component:{...expected},
      sourceCommit:manifest.sourceCommit,imageId:identity.configImageId,containerdImageId:identity.containerdImageId,
      architecture:'amd64',observedAt:now.toISOString().replace(/\.\d{3}Z$/,'Z'),
      runningImageVerified:true,publicRouteVerified:false,compatibilityApproved:false};
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();error.details={component:'gateway',stage};throw error;
  }finally{if(workspace)await rm(workspace,{recursive:true,force:true}).catch(()=>{});}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try{
    const {values}=parseArgs({options:{manifest:{type:'string'},container:{type:'string'},origin:{type:'string'},
      version:{type:'string'},sha256:{type:'string'}},allowPositionals:false});
    if(Object.keys(values).length!==5||Object.values(values).some(value=>!value))throw 0;
    console.log(JSON.stringify(await collectGatewayReleaseEvidence({manifestPath:values.manifest,containerName:values.container,
      origin:values.origin,expected:{name:'gateway',version:values.version,artifactSha256:values.sha256}})));
  }catch{
    const error=compatibilityErrors.evidenceIncomplete();
    console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,retryable:error.retryable,action:error.action}));
    process.exitCode=1;
  }
}
