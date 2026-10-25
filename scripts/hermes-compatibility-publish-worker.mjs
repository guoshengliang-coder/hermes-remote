#!/usr/bin/env node
import { writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { workerRequest, readJSON } from './hermes-compatibility-worker.mjs';
import { compatibilityUUID, compatibilityCommit, compatibilityDigest } from '../gateway/dist/account/hermes-compatibility-model.js';
import { CompatibilitySigner, parseRecommendation } from '../gateway/dist/account/hermes-compatibility-publication.js';

export function validatePublicationTask(value, env = process.env) {
  if (!value || Object.keys(value).sort().join(',') !== 'goCommit,payload,payloadSha256,schemaVersion,taskId' || value.schemaVersion !== 1) throw new Error('invalid_task');
  compatibilityUUID(value.taskId); compatibilityCommit(value.goCommit);
  if (value.taskId !== env.HERMES_COMPATIBILITY_TASK_ID || value.goCommit !== env.GITHUB_SHA) throw new Error('task_mismatch');
  const origin = new URL(env.HERMES_COMPATIBILITY_GATEWAY_URL).origin;
  const payload = parseRecommendation(value.payload, origin);
  if (compatibilityDigest(payload) !== value.payloadSha256) throw new Error('payload_mismatch');
  return value;
}
async function main() {
  const [action, file] = process.argv.slice(2);
  const taskId = compatibilityUUID(process.env.HERMES_COMPATIBILITY_TASK_ID);
  if (action === 'claim') {
    const task = validatePublicationTask(await workerRequest('claim',taskId,{},process.env,fetch,'publisher'));
    await writeFile(file,JSON.stringify(task)+'\n',{mode:0o600,flag:'wx'});
  } else if (action === 'sign-and-report') {
    const task = validatePublicationTask(await readJSON(file));
    const signer = new CompatibilitySigner(process.env.SIGNING_KEY_ID,process.env.SIGNING_PRIVATE_KEY,new URL(process.env.HERMES_COMPATIBILITY_GATEWAY_URL).origin);
    if (signer.publicKey.export({format:'jwk'}).x !== process.env.SIGNING_PUBLIC_KEY) throw new Error('key_continuity_mismatch');
    const envelope = signer.signRecommendation(task.payload);
    // The same signed bytes are retried; transport loss never creates a different recommendation.
    for (let attempt=0;attempt<3;attempt++) {
      try { await workerRequest('report',taskId,envelope,process.env,fetch,'publisher'); return; }
      catch { if(attempt===2)throw new Error('report_failed'); await new Promise(resolve=>setTimeout(resolve,3000)); }
    }
  } else throw new Error('invalid_action');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  main().catch(()=>{console.error('HR-HERMESCOMPAT-006：推荐发布未完成，请检查受保护发布任务后重试。 / Publication incomplete; review the protected release job and retry.');process.exitCode=1;});
}
