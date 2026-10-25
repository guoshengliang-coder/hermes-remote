#!/usr/bin/env node
import { readFile, writeFile, mkdtemp, chmod, realpath, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { CORE_REST_SCOPE, CORE_REST_CHECKS, SESSION_RPC_SCOPE, SESSION_RPC_CHECKS, PROMPT_SCOPE, PROMPT_CHECKS, RECONNECT_SCOPE, RECONNECT_CHECKS, RESTART_SCOPE, RESTART_CHECKS } from './hermes-compatibility-contract-probe.mjs';
import { parseTask, readJSON, infrastructureReport } from './hermes-compatibility-worker.mjs';
import { compatibilityDigest, REQUIRED_COMPATIBILITY_CHECKS } from '../gateway/dist/account/hermes-compatibility-model.js';

const exec = promisify(execFile);
const timestamp = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
const SHA = /^[a-f0-9]{64}$/;
export const RELAY_SCOPE = 'legacy-source-build-relay-v1';
export const LAUNCH_AGENT_SCOPE = 'temporary-desktop-launchagent-v1';
export async function launchAgentSourceIdentity() {
  const hash=async path=>createHash('sha256').update(await readFile(path)).digest('hex');
  const directory='desktop/Sources/HermesGoDesktopCore';
  const names=(await readdir(directory)).filter(n=>n.endsWith('.swift')).sort();
  const records=await Promise.all(names.map(async name=>[`${directory}/${name}`,await hash(`${directory}/${name}`)]));
  return {helperSourceSha256:await hash('scripts/hermes-compatibility-launchagent-helper.swift'),
    fixtureSha256:await hash('scripts/hermes-compatibility-launchagent-fixture.py'),
    coreSourceSha256:createHash('sha256').update(JSON.stringify(records)).digest('hex')};
}
export function contractEvidence(raw, identity, suiteSha256, probeSha256, modelFixtureSha256, relayFixtureSha256, launchAgentIdentity) {
  return parseContracts(raw, identity, suiteSha256, probeSha256, modelFixtureSha256, relayFixtureSha256, 'direct', launchAgentIdentity);
}
function parseContracts(raw, identity, suiteSha256, probeSha256, modelFixtureSha256, relayFixtureSha256, transport, launchAgentIdentity) {
  const names=['httpMissingToken','httpWrongToken','httpValidToken','runningVersion','sessionList','profileSessionList',
    'historyRead','connectorSchema','websocketMissingToken','websocketWrongToken','connectorActiveListRPC'];
  if(raw?.schemaVersion!==1||raw.kind!=='live-runtime-contracts'||raw.sourceCommit!==identity.sourceCommit
    ||raw.targetCommit!==identity.targetCommit||raw.compatibilityApproved!==false||raw.suiteSha256!==suiteSha256
    ||raw.probeSha256!==probeSha256||!SHA.test(suiteSha256)||!SHA.test(probeSha256))throw new Error('contract_identity_mismatch');
  if (raw.relayScope !== undefined && (raw.relayScope !== RELAY_SCOPE || transport !== 'direct' || !SHA.test(relayFixtureSha256??'')))
    throw new Error('contract_relay_scope_mismatch');
  const restScope=raw.stages?.source?.restScope;
  if (restScope !== undefined && restScope !== CORE_REST_SCOPE) throw new Error('contract_scope_mismatch');
  const rpcScope=raw.stages?.source?.rpcScope;
  if (rpcScope !== undefined && rpcScope !== SESSION_RPC_SCOPE) throw new Error('contract_scope_mismatch');
  const promptScope=raw.stages?.source?.promptScope;
  if (promptScope !== undefined && (promptScope !== PROMPT_SCOPE || !restScope || !rpcScope || !SHA.test(modelFixtureSha256??'')))
    throw new Error('contract_scope_mismatch');
  const reconnectScope=raw.stages?.source?.reconnectScope;
  if (reconnectScope !== undefined && (reconnectScope !== RECONNECT_SCOPE || !promptScope)) throw new Error('contract_scope_mismatch');
  const required = [...names, ...(restScope ? CORE_REST_CHECKS : []), ...(rpcScope ? SESSION_RPC_CHECKS : []),
    ...(promptScope ? PROMPT_CHECKS : []),...(reconnectScope ? RECONNECT_CHECKS : [])];
  const stages={},restartStages={};
  const restartScope=raw.stages?.source?.restart?.scope;
  if(restartScope!==undefined&&(restartScope!==RESTART_SCOPE||transport!=='direct'||!promptScope||!reconnectScope))throw new Error('contract_restart_scope_mismatch');
  for(const [name,version] of [['source','0.21.3'],['candidate',identity.version],['restored','0.21.3']]) {
    const stage=raw.stages?.[name];
    if(stage?.schemaVersion!==1||stage.kind!=='live-connector-contract-probe'||stage.version!==version
      ||(stage.transport??'direct')!==transport||(!raw.relayScope && stage.relay!==undefined)
      ||stage.compatibilityApproved!==false||stage.restScope!==restScope||stage.rpcScope!==rpcScope||stage.promptScope!==promptScope||stage.reconnectScope!==reconnectScope
      ||(!reconnectScope && RECONNECT_CHECKS.some(n=>Object.hasOwn(stage.checks??{},n)))
      ||(promptScope && stage.modelFixtureSha256!==modelFixtureSha256)
      ||(!promptScope && (stage.modelFixtureSha256!==undefined || PROMPT_CHECKS.some(n=>Object.hasOwn(stage.checks??{},n))))
      ||(!restScope && CORE_REST_CHECKS.some(n=>Object.hasOwn(stage.checks??{},n)))
      ||(!rpcScope && SESSION_RPC_CHECKS.some(n=>Object.hasOwn(stage.checks??{},n)))
      ||!required.every(n=>typeof stage.checks?.[n]==='boolean'))throw new Error('contract_stage_mismatch');
    stages[name]={version,checks:Object.fromEntries(required.map(n=>[n,stage.checks[n]]))};
    const restarted=stage.restart;
    if(restartScope){
      if(restarted?.schemaVersion!==1||restarted.kind!=='live-backend-restart-probe'||restarted.scope!==restartScope
        ||restarted.version!==version||restarted.compatibilityApproved!==false||typeof restarted.attempted!=='boolean'
        ||(restarted.attempted?!RESTART_CHECKS.every(n=>typeof restarted.checks?.[n]==='boolean'):
          (stage.checks.promptHistory!==false||!restarted.checks||Object.keys(restarted.checks).length!==0)))throw new Error('contract_restart_mismatch');
      restartStages[name]={version,attempted:restarted.attempted,checks:restarted.attempted?
        Object.fromEntries(RESTART_CHECKS.map(n=>[n,restarted.checks[n]])):{}};
    }else if(restarted!==undefined)throw new Error('contract_restart_scope_mismatch');
  }
  let relay;
  if (raw.relayScope) {
    // These are source builds in a legacy token channel, never released artifacts or account-mode certification.
    const first=raw.stages.source.relay;
    const fields=['gatewayVersion','connectorVersion','nodeSha256','buildSha256'];
    if (!first || !['gatewayVersion','connectorVersion'].every(k=>/^[0-9]+\.[0-9]+\.[0-9]+$/.test(first[k]??''))
        || !['nodeSha256','buildSha256'].every(k=>SHA.test(first[k]??''))) throw new Error('contract_relay_identity_mismatch');
    const probes={}, observations={};
    for (const name of ['source','candidate','restored']) {
      const item=raw.stages[name].relay;
      if (!item || item.fixtureSha256!==relayFixtureSha256 || !fields.every(k=>item[k]===first[k])
          || typeof item.roleSeparation!=='boolean' || typeof item.stopped!=='boolean'
          || item.probe?.relay!==undefined || !item.probe?.checks) throw new Error('contract_relay_identity_mismatch');
      probes[name]=item.probe;
      observations[name]={roleSeparation:item.roleSeparation,stopped:item.stopped};
    }
    const nested=parseContracts({...raw,relayScope:undefined,stages:probes,
      status:Object.values(probes).every(p=>Object.values(p.checks).every(v=>v===true))?'passed':'contract_gap'},
      identity,suiteSha256,probeSha256,modelFixtureSha256,undefined,'legacy-relay');
    if (!restScope || !rpcScope || !promptScope || !reconnectScope ||
        ['restScope','rpcScope','promptScope','reconnectScope'].some(k=>nested[k]!==raw.stages.source[k]))
      throw new Error('contract_relay_scope_mismatch');
    relay={scope:RELAY_SCOPE,fixtureSha256:relayFixtureSha256,...Object.fromEntries(fields.map(k=>[k,first[k]])),
      stages:nested.stages,observations};
  }
  let launchAgent;
  const firstNative=raw.stages.source.launchAgent;
  const nativeChecks=['loaded','runningOwned','stopped','portReleased'];
  if(firstNative!==undefined){
    const fields=['helperSourceSha256','helperBinarySha256','coreSourceSha256','fixtureSha256'];
    if(transport!=='direct'||!restartScope||firstNative?.scope!==LAUNCH_AGENT_SCOPE
      ||!fields.every(k=>SHA.test(firstNative[k]??''))
      ||!['helperSourceSha256','coreSourceSha256','fixtureSha256'].every(k=>firstNative[k]===launchAgentIdentity?.[k]))
      throw new Error('launchagent_identity_mismatch');
    const cycles={};
    for(const name of ['source','candidate','restored']){
      const stage=raw.stages[name];cycles[name]={};
      for(const [phase,owner] of [['initial',stage],['restarted',stage.restart]]){
        const item=owner.launchAgent;
        if(phase==='restarted'&&!owner.attempted){if(item!==undefined)throw new Error('launchagent_unattempted');continue;}
        if(item?.scope!==LAUNCH_AGENT_SCOPE||!fields.every(k=>item[k]===firstNative[k])
          ||!nativeChecks.every(k=>typeof item.checks?.[k]==='boolean'))throw new Error('launchagent_cycle_mismatch');
        cycles[name][phase]=Object.fromEntries(nativeChecks.map(k=>[k,item.checks[k]]));
      }
    }
    launchAgent={scope:LAUNCH_AGENT_SCOPE,...Object.fromEntries(fields.map(k=>[k,firstNative[k]])),cycles};
  }else if(Object.values(raw.stages).some(stage=>stage.launchAgent!==undefined||stage.restart?.launchAgent!==undefined)){
    throw new Error('launchagent_scope_missing');
  }
  const passed=Object.values(stages).every(stage=>Object.values(stage.checks).every(Boolean))
    && (!restartScope || Object.values(restartStages).every(stage=>Object.values(stage.checks).every(Boolean))) && (!relay ||
    (Object.values(relay.stages).every(stage=>Object.values(stage.checks).every(Boolean)) &&
     Object.values(relay.observations).every(stage=>stage.roleSeparation && stage.stopped)))
    && (!launchAgent||Object.values(launchAgent.cycles).every(cycle=>Object.values(cycle).every(checks=>Object.values(checks).every(Boolean))));
  if(raw.status!==(passed?'passed':'contract_gap'))throw new Error('contract_status_mismatch');
  return {kind:'live-runtime-contracts',scope:'desktop-owned-loopback-session-token',...(restScope?{restScope}:{}),...(rpcScope?{rpcScope}:{}),...(promptScope?{promptScope,modelFixtureSha256}:{}),...(reconnectScope?{reconnectScope}:{}),suiteSha256,probeSha256,stages,...(relay?{relay}:{}),
    ...(restartScope?{restart:{scope:restartScope,stages:restartStages}}:{}),...(launchAgent?{launchAgent}:{})};
}
export function lockEvidence(raw, identity, suiteSha256) {
  const names = ['oldRefusesLiveOwner', 'newRefusesLiveOwner', 'childSurvivesOwnerInterruption',
    'newRefusesSurvivingChild', 'oldRefusesSurvivingChild', 'newAcquiresAfterTreeExit'];
  if (raw?.schemaVersion !== 1 || raw.sourceCommit !== identity.sourceCommit || raw.targetCommit !== identity.targetCommit
      || raw.suiteSha256 !== suiteSha256 || !SHA.test(suiteSha256) || raw.compatibilityApproved !== false
      || !SHA.test(raw.sourceLockSha256) || !SHA.test(raw.targetLockSha256)
      || !names.every(name => typeof raw.checks?.[name] === 'boolean')) throw new Error('lock_identity_mismatch');
  const checks = Object.fromEntries(names.map(name => [name, raw.checks[name]]));
  const gap = Object.values(checks).some(value => !value);
  if (raw.status !== (gap ? 'compatibility_gap' : 'passed')) throw new Error('lock_status_mismatch');
  return {kind: 'official-update-lock-probe', suiteSha256, sourceLockSha256: raw.sourceLockSha256,
    targetLockSha256: raw.targetLockSha256, checks, gap};
}
/** Whitelist evidence fields. Never publish paths, subprocess logs, environment or credentials. */
export function labEvidence(raw, identity, fault = false) {
  if (raw?.schemaVersion !== 1 || raw.kind !== 'runtime-rehearsal' || raw.suiteSha256 !== identity.suiteSha256
      || raw.case?.sourceCommit !== identity.sourceCommit || raw.case?.targetCommit !== identity.targetCommit
      || raw.case?.installerSha256 !== identity.installerSha256 || raw.platform !== 'Darwin' || raw.arch !== 'arm64'
      || raw.compatibilityApproved !== false || (fault && raw.faultAfter !== 'target_python-deps')) throw new Error('lab_identity_mismatch');
  const names = ['sandbox', 'writeFence', 'evidenceFence', 'clone', 'origin', 'sourceCheckout', 'targetTag',
    'source_venv', 'source_python-deps', 'source_config', 'seedHistory', 'sourceHistory', 'snapshot', 'repository',
    'exactTarget', 'target_venv', 'target_python-deps', 'target_config', 'target_products', 'target_complete', 'candidateHistory', 'restore', 'restoredHistory',
    'seedConfiguration','sourceConfiguration','candidateConfiguration','restoredConfiguration'];
  const phases = Object.fromEntries(names.map(name => [name, raw.checks?.[name]?.passed === true]));
  const snapshot = raw.checks?.snapshot?.digest, restored = raw.checks?.restore?.digest;
  return { kind: fault ? 'dependency-failure-recovery' : 'runtime-upgrade-recovery', phases,
    completed: fault ? raw.status === 'failed' && raw.reason === 'injected_failure_after_target_dependencies' : raw.status === 'runtime_stage_passed',
    exactTarget: raw.checks?.exactTarget?.actual === identity.targetCommit,
    exactRestoredSource: raw.checks?.restore?.actual === identity.sourceCommit,
    snapshotRestored: typeof snapshot === 'string' && SHA.test(snapshot) && snapshot === restored,
    originalHistory: raw.checks?.sourceHistory?.historyPresent === true,
    upgradedHistory: raw.checks?.candidateHistory?.historyPresent === true && raw.checks?.candidateHistory?.runningVersion === identity.version,
    restoredHistory: raw.checks?.restoredHistory?.historyPresent === true,
    ...(raw.checks?.seedConfiguration ? {configuration:{scope:'seeded-default-profile-and-dotenv',
      seeded:phases.seedConfiguration,source:phases.sourceConfiguration,
      candidate:phases.candidateConfiguration,restored:phases.restoredConfiguration}} : {}) };
}
export function buildBundle(task, evidence, startedAt, finishedAt = timestamp()) {
  const normal = evidence.normal, fault = evidence.fault;
  const fences = v => v && ['sandbox', 'writeFence', 'evidenceFence'].every(n => v.phases[n]);
  const normalOK = fences(normal) && normal.completed && normal.exactTarget;
  const recoveryOK = normalOK && fences(fault) && fault.completed && [normal, fault].every(v =>
    v.exactRestoredSource && v.snapshotRestored && v.restoredHistory && v.phases.restore && v.phases.restoredHistory);
  const conditions = {
    installation: normalOK && ['source_venv', 'source_python-deps', 'target_venv', 'target_python-deps', 'target_products', 'target_complete'].every(n => normal.phases[n]),
    upgrade: normalOK && normal.phases.repository && normal.phases.exactTarget && normal.phases.candidateHistory,
    history: normalOK && normal.originalHistory && normal.upgradedHistory && normal.restoredHistory,
    recovery: recoveryOK,
    ...(evidence.contracts ? {authentication:normalOK && Object.values(evidence.contracts.stages).every(stage=>
      ['httpMissingToken','httpWrongToken','httpValidToken','websocketMissingToken','websocketWrongToken','connectorActiveListRPC']
        .every(name=>stage.checks[name]))} : {}),
    ...(evidence.contracts?.restScope === CORE_REST_SCOPE ? {rest:normalOK &&
      ['source','candidate','restored'].every(stage => CORE_REST_CHECKS.every(name =>
        evidence.contracts.stages[stage]?.checks[name] === true))} : {}),
  };
  const checks = REQUIRED_COMPATIBILITY_CHECKS.map(name => {
    // A demonstrated collision is enough to reject compatibility. This narrow probe alone
    // cannot certify the complete service/installer/GUI concurrency matrix if it passes.
    if (name === 'concurrentUpdate' && evidence.concurrency?.gap === true) {
      return {name, status: 'failed', evidenceSha256: compatibilityDigest(evidence.concurrency)};
    }
    // Session lifecycle success does not prove prompts/tools/server requests. A failure
    // in an exercised required path does, however, rule out full RPC compatibility.
    if (name === 'rpc' && evidence.contracts?.rpcScope === SESSION_RPC_SCOPE &&
        ['source','candidate','restored'].some(stage => [...SESSION_RPC_CHECKS,...(evidence.contracts.promptScope===PROMPT_SCOPE?PROMPT_CHECKS:[]),...(evidence.contracts.reconnectScope===RECONNECT_SCOPE?RECONNECT_CHECKS:[])].some(check =>
          evidence.contracts.stages[stage]?.checks[check] === false))) {
      return {name,status:'failed',evidenceSha256:compatibilityDigest(evidence.contracts)};
    }
    if (name === 'connector' && evidence.contracts?.relay &&
        (Object.values(evidence.contracts.relay.stages).some(stage=>Object.values(stage.checks).some(value=>value===false)) ||
         Object.values(evidence.contracts.relay.observations).some(stage=>!stage.roleSeparation || !stage.stopped))) {
      return {name,status:'failed',evidenceSha256:compatibilityDigest(evidence.contracts)};
    }
    // Backend restart does not cover LaunchAgent ownership or the Desktop lifecycle. A failed
    // completed-turn recovery is nevertheless a concrete service recovery incompatibility.
    if(name==='serviceRecovery'&&evidence.contracts?.restart?.scope===RESTART_SCOPE&&
      Object.values(evidence.contracts.restart.stages).some(stage=>stage.attempted&&Object.values(stage.checks).some(value=>value===false))) {
      return {name,status:'failed',evidenceSha256:compatibilityDigest(evidence.contracts)};
    }
    if(name==='serviceRecovery'&&evidence.contracts?.launchAgent&&Object.values(evidence.contracts.launchAgent.cycles)
      .some(cycle=>Object.values(cycle).some(checks=>Object.values(checks).some(value=>value===false)))){
      return {name,status:'failed',evidenceSha256:compatibilityDigest(evidence.contracts)};
    }
    const available = Object.hasOwn(conditions, name);
    const proof = name === 'recovery' ? evidence.recovery : ['authentication','rest'].includes(name) ? evidence.contracts : normal;
    return { name, status: available ? conditions[name] ? 'passed' : 'failed' : 'not_run',
      evidenceSha256: available && proof ? compatibilityDigest(proof) : null };
  });
  return { schemaVersion: 1, evidence, report: { schemaVersion: 1, runId: task.runId, identity: task.identity,
    startedAt, finishedAt, outcome: evidence.infrastructure ? 'infrastructure_failed' : checks.every(c => c.status === 'passed') ? 'passed' : 'failed',
    checks, evidenceBundleSha256: compatibilityDigest(evidence) } };
}
async function main() {
  const [input, output] = process.argv.slice(2); const task = parseTask(await readJSON(input));
  const startedAt = timestamp(), evidence = {};
  try {
    if (process.platform !== 'darwin' || process.arch !== 'arm64' || process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN) throw new Error('wrong_runner');
    const osVersion = (await exec('/usr/bin/sw_vers', ['-productVersion'])).stdout.trim();
    const normalizedOS = osVersion.split('.').length === 2 ? `${osVersion}.0` : osVersion;
    const head = (await exec('git', ['rev-parse', 'HEAD'])).stdout.trim();
    const suite = resolve('scripts/hermes-compatibility-lab.py');
    if (normalizedOS !== task.identity.environment.osVersion || head !== task.identity.goCommit
        || createHash('sha256').update(await readFile(suite)).digest('hex') !== task.identity.suiteSha256) throw new Error('environment_mismatch');
    const root = await mkdtemp(join(tmpdir(), 'hermes-compat-ci-')); await chmod(root, 0o700);
    const cache = join(root, 'upstream'), casePath = join(root, 'case.json');
    // Fetch only upstream Git objects here. All upstream execution remains inside the lab sandbox.
    const env = { PATH: process.env.PATH, HOME: root, TMPDIR: tmpdir(), GIT_CONFIG_NOSYSTEM: '1', GIT_TERMINAL_PROMPT: '0' };
    await exec('git', ['clone', '--filter=blob:none', '--no-checkout', 'https://github.com/NousResearch/hermes-agent.git', cache], { env, timeout: 180_000, maxBuffer: 1024 * 1024 });
    for (const commit of [task.identity.sourceCommit, task.identity.targetCommit]) {
      await exec('git', ['-C', cache, 'fetch', '--no-tags', 'origin', commit], { env, timeout: 180_000 });
      await exec('git', ['-C', cache, 'show', `${commit}:scripts/install.sh`], { env, timeout: 180_000, maxBuffer: 1024 * 1024 });
    }
    await writeFile(casePath, JSON.stringify({ schemaVersion: 1, sourceCommit: task.identity.sourceCommit,
      targetCommit: task.identity.targetCommit, installerSha256: task.identity.installerSha256, branch: 'main' }), { mode: 0o600 });
    for (const fault of [false, true]) {
      const args = [suite, '--runtime', '--cache', cache, '--case', casePath, ...(fault ? ['--fault-after', 'target_python-deps'] : [])];
      let stdout;
      try { ({ stdout } = await exec('python3', args, { timeout: 35 * 60_000, maxBuffer: 64 * 1024 })); }
      catch (error) { if (!fault || error.code !== 1) throw new Error('lab_failed'); stdout = error.stdout; }
      const result = stdout.split('\n').filter(Boolean).map(line => JSON.parse(line)).find(value => typeof value.report === 'string');
      if (!result) throw new Error('lab_report_missing');
      const reportPath = await realpath(result.report), temporary = await realpath(tmpdir());
      if (!reportPath.startsWith(`${temporary}/hermes-compat-lab-`) || !reportPath.endsWith('/report.json')) throw new Error('lab_path_invalid');
      evidence[fault ? 'fault' : 'normal'] = labEvidence(await readJSON(reportPath), task.identity, fault);
      if (!fault) {
        const lockSuite = resolve('scripts/hermes-compatibility-lock-lab.py');
        const labRoot = dirname(reportPath);
        const probe = await exec('python3', [lockSuite, '--source', join(labRoot, 'user/.hermes/hermes-agent'),
          '--target', join(labRoot, 'candidate-after-experiment/.hermes/hermes-agent')], {timeout: 90_000, maxBuffer: 64 * 1024});
        const lockResult = JSON.parse(probe.stdout.trim());
        const lockPath = await realpath(lockResult.report);
        if (!lockPath.startsWith(`${temporary}/hermes-compat-lock-lab-`) || !lockPath.endsWith('/report.json')) throw new Error('lock_path_invalid');
        evidence.concurrency = lockEvidence(await readJSON(lockPath), task.identity,
          createHash('sha256').update(await readFile(lockSuite)).digest('hex'));
        const contractSuite=resolve('scripts/hermes-compatibility-contract-lab.py');
        const contractProbe=resolve('scripts/hermes-compatibility-contract-probe.mjs');
        const contract=await exec('python3',[contractSuite,'--prompt','--relay','--restart','--launchagent','--lab-root',labRoot],{timeout:30*60_000,maxBuffer:64*1024});
        const contractResult=JSON.parse(contract.stdout.trim());
        const contractPath=await realpath(contractResult.report);
        if(dirname(contractPath)!==labRoot||!/^contract-report-[0-9]+\.json$/.test(contractPath.slice(labRoot.length+1)))throw new Error('contract_path_invalid');
        evidence.contracts=contractEvidence(await readJSON(contractPath),task.identity,
          createHash('sha256').update(await readFile(contractSuite)).digest('hex'),
          createHash('sha256').update(await readFile(contractProbe)).digest('hex'),
          createHash('sha256').update(await readFile(resolve('scripts/hermes-compatibility-model-fixture.py'))).digest('hex'),
          createHash('sha256').update(await readFile(resolve('scripts/hermes-compatibility-relay-fixture.py'))).digest('hex'),
          await launchAgentSourceIdentity());
      }
    }
    evidence.recovery = { normal: evidence.normal, fault: evidence.fault };
  } catch { evidence.infrastructure = { reason: 'isolated_validation_incomplete' }; }
  const bundle = buildBundle(task, evidence, startedAt);
  if (!evidence.normal) { bundle.report = infrastructureReport(task); bundle.report.startedAt = startedAt;
    bundle.report.evidenceBundleSha256 = compatibilityDigest(evidence); }
  await writeFile(output, JSON.stringify(bundle) + '\n', { mode: 0o600, flag: 'wx' });
  // Incomplete validation is a recorded result, not an approval. The reporter still runs.
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(() => { console.error('HR-HERMESCOMPAT-004：验证报告未完成，请查看任务记录。 / Validation report incomplete; review the job.'); process.exitCode = 1; });
}
