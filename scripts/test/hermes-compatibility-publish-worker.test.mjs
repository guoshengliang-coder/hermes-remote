import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {validatePublicationTask} from '../hermes-compatibility-publish-worker.mjs';
import {makeCompatibilityRecommendation} from '../../gateway/dist/account/hermes-compatibility-publication.js';
import {compatibilityDigest} from '../../gateway/dist/account/hermes-compatibility-model.js';

test('protected signing input is a fixed server task, exact checkout and exact payload digest',()=>{
  const env={HERMES_COMPATIBILITY_TASK_ID:'10000000-0000-4000-8000-000000000001',GITHUB_SHA:'a'.repeat(40),HERMES_COMPATIBILITY_GATEWAY_URL:'https://relay.example'};
  const payload=makeCompatibilityRecommendation('https://relay.example',7,[]);
  const task={schemaVersion:1,taskId:env.HERMES_COMPATIBILITY_TASK_ID,goCommit:env.GITHUB_SHA,payload,payloadSha256:compatibilityDigest(payload)};
  assert.deepEqual(validatePublicationTask(task,env),task);
  for(const change of [{taskId:'20000000-0000-4000-8000-000000000002'},{goCommit:'b'.repeat(40)},{payloadSha256:'0'.repeat(64)},{command:'arbitrary command'},
    {payload:{...payload,purpose:'other'}},{payload:{...payload,revision:8}}])assert.throws(()=>validatePublicationTask({...task,...change},env));
});

test('publication keeps existing environment gate and step-scoped signing credentials out of Gateway and validation',async()=>{
  const workflow=await readFile(new URL('../../.github/workflows/hermes-compatibility-publish.yml',import.meta.url),'utf8');
  const validation=await readFile(new URL('../../.github/workflows/hermes-compatibility.yml',import.meta.url),'utf8');
  const runtime=await readFile(new URL('../../gateway/src/account/account-runtime.ts',import.meta.url),'utf8');
  assert.match(workflow,/environment: desktop-release/);
  assert.match(workflow,/verify-desktop-release-key-continuity\.mjs/);
  assert.equal((workflow.match(/secrets\./g)||[]).length,1);
  assert.match(workflow,/secrets\.DESKTOP_RELEASE_SIGNING_PRIVATE_KEY/);
  assert.ok(workflow.indexOf('secrets.DESKTOP_RELEASE_SIGNING_PRIVATE_KEY')>workflow.indexOf('name: Sign and submit'));
  assert.doesNotMatch(validation,/SIGNING_PRIVATE_KEY|desktop-release/);
  assert.doesNotMatch(runtime,/HERMES_COMPATIBILITY_SIGNING_PRIVATE_KEY/);
  assert.doesNotMatch(workflow,/run:.*\$\{\{/);
});
