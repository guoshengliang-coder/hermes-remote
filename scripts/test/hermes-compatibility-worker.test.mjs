import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parseTask, validateBundle, infrastructureReport, workerRequest } from '../hermes-compatibility-worker.mjs';
import { labEvidence, buildBundle } from '../hermes-compatibility-validate.mjs';
import { compatibilityDigest } from '../../gateway/dist/account/hermes-compatibility-model.js';

const normal = JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-runtime.json', import.meta.url)));
const fault = JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-dependency-failure.json', import.meta.url)));
const task = parseTask({ schemaVersion: 1, runId: '11111111-1111-4111-8111-111111111111', identity: {
  schemaVersion: 1, candidateId: '22222222-2222-4222-8222-222222222222', version: '0.22.5',
  sourceCommit: normal.case.sourceCommit, targetCommit: normal.case.targetCommit, installerSha256: normal.case.installerSha256,
  goCommit: 'a'.repeat(40), suiteSha256: normal.suiteSha256,
  environment: { os: 'macos', arch: 'arm64', osVersion: '26.0.0', installation: 'legacy-venv-to-pm' },
  components: ['desktop', 'connector', 'gateway', 'android', 'web'].map(name => ({ name, version: '1.0.0', artifactSha256: 'b'.repeat(64) })),
} });

test('real isolated rehearsal evidence proves four checks, never full compatibility', () => {
  const evidence = { normal: labEvidence(normal, task.identity), fault: labEvidence(fault, task.identity, true) };
  evidence.recovery = { ...evidence };
  const bundle = buildBundle(task, evidence, '2026-10-25T01:00:00Z', '2026-10-25T02:00:00Z');
  assert.equal(bundle.report.outcome, 'failed');
  assert.deepEqual(bundle.report.checks.filter(c => c.status === 'passed').map(c => c.name), ['installation', 'history', 'upgrade', 'recovery']);
  assert.equal(bundle.report.checks.filter(c => c.status === 'not_run').length, 11);
  assert.deepEqual(validateBundle(task, bundle), bundle.report);
  const changed = structuredClone(bundle); changed.evidence.normal.snapshotRestored = false;
  assert.throws(() => validateBundle(task, changed), /evidence_mismatch/);
  const otherTask = structuredClone(task); otherTask.identity.targetCommit = 'c'.repeat(40);
  assert.throws(() => validateBundle(otherTask, bundle), /evidence_mismatch/);
  changed.evidence = bundle.evidence; changed.report.checks[0].evidenceSha256 = 'd'.repeat(64);
  assert.throws(() => validateBundle(task, changed), /missing_evidence/);
});

test('missing recovery, mismatched suite and failed isolation cannot become passing evidence', () => {
  const evidence = { normal: labEvidence(normal, task.identity) };
  assert.equal(buildBundle(task, evidence, '2026-10-25T01:00:00Z').report.checks.find(c => c.name === 'recovery').status, 'failed');
  evidence.normal.phases.evidenceFence = false;
  assert.equal(buildBundle(task, evidence, '2026-10-25T01:00:00Z').report.checks.filter(c => c.status === 'passed').length, 0);
  assert.throws(() => labEvidence({ ...normal, suiteSha256: '0'.repeat(64) }, task.identity), /identity/);
  const report = infrastructureReport(task);
  assert.equal(report.outcome, 'infrastructure_failed');
  assert.ok(report.checks.every(c => c.status === 'not_run' && c.evidenceSha256 === null));
});

test('default-profile configuration observations are retained without claiming all configuration support', async () => {
  const raw=JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-runtime-configuration.json',import.meta.url)));
  const currentTask=parseTask({...task,identity:{...task.identity,suiteSha256:raw.suiteSha256}});
  for(const phase of ['seedConfiguration','sourceConfiguration','candidateConfiguration','restoredConfiguration']) {
    assert.equal(raw.checks[phase].passed,true);
    raw.checks[phase].privateLog='synthetic-private-data';
  }
  const evidence=labEvidence(raw,currentTask.identity);
  assert.deepEqual(evidence.configuration,{scope:'seeded-default-profile-and-dotenv',seeded:true,source:true,candidate:true,restored:true});
  assert.equal(JSON.stringify(evidence).includes('synthetic-private-data'),false);
  assert.equal(buildBundle(currentTask,{normal:evidence},'2026-10-25T01:00:00Z').report.checks.find(c=>c.name==='configuration').status,'not_run');
  raw.checks.candidateConfiguration.passed=false;
  assert.equal(labEvidence(raw,currentTask.identity).configuration.candidate,false);
});

test('real configured upgrade and dependency failure both recover with their fixed suite identity', async () => {
  const upgrade=JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-runtime-configuration.json',import.meta.url)));
  const failure=JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-dependency-configuration.json',import.meta.url)));
  const currentTask=parseTask({...task,identity:{...task.identity,suiteSha256:upgrade.suiteSha256}});
  const evidence={normal:labEvidence(upgrade,currentTask.identity),fault:labEvidence(failure,currentTask.identity,true)};
  assert.equal(evidence.fault.configuration.source,true);
  assert.equal(evidence.fault.configuration.restored,true);
  assert.equal(evidence.fault.configuration.candidate,false,'fault precedes candidate startup');
  evidence.recovery={...evidence};
  const bundle=buildBundle(currentTask,evidence,'2026-10-25T01:00:00Z');
  assert.equal(bundle.report.checks.find(c=>c.name==='recovery').status,'passed');
  assert.equal(bundle.report.outcome,'failed','partial verification is not a compatibility approval');
  assert.deepEqual(validateBundle(currentTask,bundle),bundle.report);
});

test('observed legacy lock gap is a failed check, never hidden as not-run or approved', async () => {
  const lock = JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-update-lock.json', import.meta.url)));
  const { lockEvidence } = await import('../hermes-compatibility-validate.mjs');
  const evidence = { concurrency: lockEvidence(lock, task.identity, lock.suiteSha256) };
  const bundle = buildBundle(task, evidence, '2026-10-25T01:00:00Z');
  assert.equal(bundle.report.checks.find(c => c.name === 'concurrentUpdate').status, 'failed');
  assert.equal(bundle.report.outcome, 'failed');
  assert.deepEqual(validateBundle(task, bundle), bundle.report);
  assert.throws(() => lockEvidence({...lock, targetCommit: '0'.repeat(40)}, task.identity, lock.suiteSha256));
  const success = structuredClone(lock);
  success.checks.oldRefusesSurvivingChild = true;
  success.status = 'passed';
  const partial = buildBundle(task, {concurrency: lockEvidence(success, task.identity, lock.suiteSha256)}, '2026-10-25T01:00:00Z');
  assert.equal(partial.report.checks.find(c => c.name === 'concurrentUpdate').status, 'not_run', 'one lock probe cannot certify all concurrency');
});

test('real transport evidence certifies only the exercised authentication scope', async () => {
  const { contractEvidence } = await import('../hermes-compatibility-validate.mjs');
  const raw = JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-live-contracts.json', import.meta.url)));
  const contracts = contractEvidence(raw,task.identity,raw.suiteSha256,raw.probeSha256);
  const privateFields=structuredClone(raw);privateFields.privateLog='synthetic-secret';privateFields.stages.candidate.token='synthetic-secret';
  assert.equal(JSON.stringify(contractEvidence(privateFields,task.identity,raw.suiteSha256,raw.probeSha256)).includes('synthetic-secret'),false);
  const evidence = {normal:labEvidence(normal,task.identity),contracts};
  const bundle = buildBundle(task,evidence,'2026-10-25T01:00:00Z');
  assert.equal(bundle.report.checks.find(c=>c.name==='authentication').status,'passed');
  for(const name of ['rest','rpc','connector','clientBusiness','newDataProtection']) {
    assert.equal(bundle.report.checks.find(c=>c.name===name).status,'not_run');
  }
  assert.deepEqual(validateBundle(task,bundle),bundle.report);
  assert.throws(()=>contractEvidence({...raw,targetCommit:'0'.repeat(40)},task.identity,raw.suiteSha256,raw.probeSha256));
  const broken=structuredClone(raw);broken.stages.candidate.checks.websocketWrongToken=false;broken.status='contract_gap';
  const failed=buildBundle(task,{...evidence,contracts:contractEvidence(broken,task.identity,raw.suiteSha256,raw.probeSha256)},'2026-10-25T01:00:00Z');
  assert.equal(failed.report.checks.find(c=>c.name==='authentication').status,'failed');
});

test('evidence publication strips arbitrary lab fields and never includes private logs', () => {
  const evidence = labEvidence({ ...normal, privateLog: 'secret', reason: 'secret', path: '/Users/private' }, task.identity);
  assert.equal(JSON.stringify(evidence).includes('secret'), false);
  assert.equal(JSON.stringify(evidence).includes('/Users'), false);
  assert.equal(compatibilityDigest(evidence).length, 64);
});

test('worker uses runner OIDC and fixed HTTPS origin without redirects', async () => {
  const calls = [];
  const env = { HERMES_COMPATIBILITY_GATEWAY_URL: 'https://relay.example', ACTIONS_ID_TOKEN_REQUEST_URL: 'https://example.actions.githubusercontent.com/token?x=1', ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'runner-only' };
  const fetcher = async (url, options) => { calls.push([String(url), options]); return new Response(JSON.stringify(calls.length === 1 ? { value: 'workflow-proof' } : task)); };
  assert.deepEqual(await workerRequest('claim', task.runId, {}, env, fetcher), task);
  assert.equal(new URL(calls[0][0]).searchParams.get('audience'), 'https://relay.example/hermes-compatibility');
  assert.equal(calls[1][0], `https://relay.example/v2/hermes-compatibility/worker/${task.runId}/claim`);
  assert.equal(calls[1][1].headers.authorization, 'Bearer workflow-proof');
  assert.ok(calls.every(c => c[1].redirect === 'error'));
  for (const origin of ['http://relay.example', 'https://relay.example/private', 'https://user:pass@relay.example', 'https://relay.example/?q=x']) {
    await assert.rejects(workerRequest('claim', task.runId, {}, { ...env, HERMES_COMPATIBILITY_GATEWAY_URL: origin }, fetcher), /origin/);
  }
  await assert.rejects(workerRequest('claim', task.runId, {}, { ...env, ACTIONS_ID_TOKEN_REQUEST_URL: 'https://evil.example/token' }, fetcher), /oidc_endpoint/);
  assert.equal(calls.length, 2);
});

test('workflow isolates upstream execution from OIDC and never accepts scripts or versions as inputs', async () => {
  const workflow = await readFile(new URL('../../.github/workflows/hermes-compatibility.yml', import.meta.url), 'utf8');
  const validator = workflow.split('  validate:\n')[1].split('  report:\n')[0];
  assert.match(validator, /runs-on: macos-26/);
  assert.doesNotMatch(validator, /id-token: write|secrets\./);
  assert.match(validator, /persist-credentials: false/);
  assert.equal((workflow.match(/id-token: write/g) || []).length, 2);
  assert.doesNotMatch(workflow, /run:.*\$\{\{/);
});


test('scoped real core REST evidence requires every stage and binds the contract digest', async () => {
  const { contractEvidence } = await import('../hermes-compatibility-validate.mjs');
  const raw = JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-core-rest-contracts.json', import.meta.url)));
  const parse = value => contractEvidence(value,task.identity,raw.suiteSha256,raw.probeSha256);
  const contracts = parse(raw);
  const evidence = {normal:labEvidence(normal,task.identity),contracts};
  const bundle = buildBundle(task,evidence,'2026-10-25T01:00:00Z');
  assert.deepEqual(bundle.report.checks.find(c=>c.name==='rest'),
    {name:'rest',status:'passed',evidenceSha256:compatibilityDigest(contracts)});
  assert.deepEqual(validateBundle(task,bundle),bundle.report);
  assert.equal(bundle.report.outcome,'failed');
  for (const name of ['rpc','connector','clientBusiness','newDataProtection'])
    assert.equal(bundle.report.checks.find(c=>c.name===name).status,'not_run');
  for (const stage of ['source','candidate','restored']) {
    for (const name of ['restCoreStatus','restCoreSessions','restCoreProfileSessions','restCoreHistory','restCorePagination']) {
      const missing=structuredClone(raw);delete missing.stages[stage].checks[name];assert.throws(()=>parse(missing));
      const wrong=structuredClone(raw);wrong.stages[stage].checks[name]='true';assert.throws(()=>parse(wrong));
      const failed=structuredClone(raw);failed.stages[stage].checks[name]=false;
      assert.throws(()=>parse(failed),/status_mismatch/);
      failed.status='contract_gap';
      assert.equal(buildBundle(task,{...evidence,contracts:parse(failed)},'2026-10-25T01:00:00Z')
        .report.checks.find(c=>c.name==='rest').status,'failed');
    }
    for (const scope of [undefined,'unknown-scope']) {
      const changed=structuredClone(raw);changed.stages[stage].restScope=scope;assert.throws(()=>parse(changed));
    }
  }
  const noScope=structuredClone(raw);for(const stage of Object.values(noScope.stages))delete stage.restScope;
  assert.throws(()=>parse(noScope),'checks without their scope cannot silently disappear');
  const failedIsolation=structuredClone(evidence);failedIsolation.normal.phases.sandbox=false;
  assert.equal(buildBundle(task,failedIsolation,'2026-10-25T01:00:00Z').report.checks.find(c=>c.name==='rest').status,'failed');
  const privateFields=structuredClone(raw);privateFields.stages.candidate.response='synthetic-private-data';
  assert.equal(JSON.stringify(parse(privateFields)).includes('synthetic-private-data'),false);
});


test('real session RPC lifecycle evidence retains partial scope and propagates failures', async () => {
  const {contractEvidence}=await import('../hermes-compatibility-validate.mjs');
  const raw=JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-session-rpc-contracts.json',import.meta.url)));
  const parse=value=>contractEvidence(value,task.identity,raw.suiteSha256,raw.probeSha256);
  const normalEvidence=labEvidence(normal,task.identity);
  const bundle=value=>buildBundle(task,{normal:normalEvidence,contracts:parse(value)},'2026-10-25T01:00:00Z');
  const success=bundle(raw);
  assert.equal(success.report.checks.find(c=>c.name==='rpc').status,'not_run','no prompt/tool/server-request coverage yet');
  assert.equal(success.report.checks.find(c=>c.name==='rest').status,'passed');
  assert.deepEqual(validateBundle(task,success),success.report);
  for(const stage of ['source','candidate','restored']) {
    for(const name of ['rpcReady','rpcCapabilities','rpcCreate','rpcResume','rpcReasoning','rpcActiveIdentity']) {
      const missing=structuredClone(raw);delete missing.stages[stage].checks[name];assert.throws(()=>parse(missing));
      const failed=structuredClone(raw);failed.stages[stage].checks[name]=false;failed.status='contract_gap';
      const result=bundle(failed);
      assert.deepEqual(result.report.checks.find(c=>c.name==='rpc'),
        {name:'rpc',status:'failed',evidenceSha256:compatibilityDigest(result.evidence.contracts)});
      assert.deepEqual(validateBundle(task,result),result.report);
    }
    for(const scope of [undefined,'unknown']) {
      const changed=structuredClone(raw);changed.stages[stage].rpcScope=scope;assert.throws(()=>parse(changed));
    }
  }
  const noScope=structuredClone(raw);for(const stage of Object.values(noScope.stages))delete stage.rpcScope;
  assert.throws(()=>parse(noScope));
});

test('local-model prompt evidence binds its fixture and never certifies full RPC', async () => {
  const {contractEvidence}=await import('../hermes-compatibility-validate.mjs');
  const raw=JSON.parse(await readFile(new URL('../../docs/evidence/hermes-compatibility/2026-10-25-prompt-contracts.json',import.meta.url)));
  const fixture=raw.stages.source.modelFixtureSha256;
  const parse=(value,hash=fixture)=>contractEvidence(value,task.identity,raw.suiteSha256,raw.probeSha256,hash);
  const bundle=value=>buildBundle(task,{normal:labEvidence(normal,task.identity),contracts:parse(value)},'2026-10-25T01:00:00Z');
  const good=bundle(raw);
  assert.equal(good.report.checks.find(c=>c.name==='rpc').status,'not_run');
  assert.equal(good.report.checks.find(c=>c.name==='clientBusiness').status,'not_run');
  assert.deepEqual(validateBundle(task,good),good.report);
  assert.throws(()=>parse(raw,'a'.repeat(64)));
  assert.throws(()=>contractEvidence(raw,task.identity,raw.suiteSha256,raw.probeSha256));
  for(const stage of ['source','candidate','restored']) {
    for(const name of ['promptAccepted','promptStream','promptHistory','promptModelObserved','promptConfigurationRestored','promptNetworkFence']) {
      const missing=structuredClone(raw);delete missing.stages[stage].checks[name];assert.throws(()=>parse(missing));
      const failed=structuredClone(raw);failed.stages[stage].checks[name]=false;failed.status='contract_gap';
      assert.equal(bundle(failed).report.checks.find(c=>c.name==='rpc').status,'failed');
    }
    for(const field of ['promptScope','modelFixtureSha256']) {
      const changed=structuredClone(raw);delete changed.stages[stage][field];assert.throws(()=>parse(changed));
    }
  }
  const missing=structuredClone(raw);
  for(const stage of Object.values(missing.stages))delete stage.promptScope;
  assert.throws(()=>parse(missing),'prompt observations without scope must not disappear');
});
