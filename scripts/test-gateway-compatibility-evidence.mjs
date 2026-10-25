// CI-only real Docker acceptance. Creates only disposable, network-isolated containers from the
// just-packaged image. It never contacts production or publishes compatibility decisions.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { loadBundleManifest } from '../ops/lib/config.mjs';
import { collectGatewayReleaseEvidence } from './hermes-compatibility-gateway-evidence.mjs';
import { compatibilityErrors } from '../gateway/dist/account/hermes-compatibility-model.js';

function docker(args, env=process.env){
  const result=spawnSync('docker',args,{encoding:'utf8',env,shell:false,timeout:30_000,maxBuffer:1024*1024,
    stdio:['ignore','pipe','pipe']});
  if(result.status!==0||result.error)throw 0;
  return result.stdout.trim();
}
async function main(){
  if(process.argv.length!==3)throw 0;
  const root=resolve(process.argv[2]);
  const files=(await readdir(root)).filter(name=>name.endsWith('.manifest.json'));
  if(files.length!==1)throw 0;
  const manifestPath=join(root,files[0]),manifest=await loadBundleManifest(manifestPath);
  const expected={name:'gateway',version:manifest.serverVersion,artifactSha256:manifest.archiveSha256};
  for(const injected of [false,true]){
    const name=`hermes-compat-evidence-${randomBytes(6).toString('hex')}`;
    let created=false;
    try{
      const env={...process.env,APP_TOKEN:randomBytes(32).toString('hex'),CONNECTOR_TOKEN:randomBytes(32).toString('hex'),
        INTERNAL_STATUS_TOKEN:randomBytes(32).toString('hex')};
      docker(['create','--name',name,'--read-only','--network','none','--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=16m,uid=1000,gid=1000',
        '--cap-drop=ALL','--security-opt=no-new-privileges','--memory=256m','--pids-limit=128',
        '--env','APP_TOKEN','--env','CONNECTOR_TOKEN','--env','INTERNAL_STATUS_TOKEN',
        '--env','ACCOUNT_AUTH_ENABLED=0','--env','ACCOUNT_BINDING_ENABLED=0',
        '--env','LIFECYCLE_EVENT_STORE_FILE=/tmp/lifecycle-events.json',
        ...(injected?['--env','NODE_OPTIONS=--no-warnings']:[]),manifest.imageReference],env);
      created=true;docker(['start',name]);
      let healthy=false;
      for(let attempt=0;attempt<75;attempt++){
        if(docker(['inspect','--format','{{.State.Health.Status}}',name])==='healthy'){healthy=true;break;}
        await setTimeout(1000);
      }
      if(!healthy)throw 0;
      const input={manifestPath,expected,containerName:name,origin:'https://ci-gateway.example'};
      if(injected){
        await assert.rejects(collectGatewayReleaseEvidence(input),error=>error.code==='HR-HERMESCOMPAT-004'
          &&error.details?.stage==='runtime_identity');
      }else{
        const evidence=await collectGatewayReleaseEvidence(input);
        assert.deepEqual(evidence.component,expected);
        assert.equal(evidence.runningImageVerified,true);assert.equal(evidence.publicRouteVerified,false);
        console.log(JSON.stringify({...evidence,scope:'disposable-ci-container'}));
      }
    }finally{if(created)docker(['rm','--force',name]);}
  }
  console.log('GATEWAY_COMPATIBILITY_EVIDENCE_ACCEPTANCE_OK');
}
try{await main();}catch{
  const error=compatibilityErrors.evidenceIncomplete();
  console.error(JSON.stringify({code:error.code,summaryZh:error.message,summaryEn:error.summaryEn,retryable:error.retryable,action:error.action}));
  process.exitCode=1;
}
