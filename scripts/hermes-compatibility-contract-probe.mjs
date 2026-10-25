#!/usr/bin/env node
// Actual Connector adapters against a disposable upstream server. No model calls or mutation RPCs.
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import WebSocket from 'ws';
import { HermesAuth } from '../connector/dist/hermes-auth.js';
import { evaluateHermesContract } from '../connector/dist/hermes-contract.js';
import { parseActiveList } from '../connector/dist/session-observer.js';

export const CORE_REST_SCOPE = 'required-read-surfaces-v1';
export const CORE_REST_CHECKS = ['restCoreStatus','restCoreSessions','restCoreProfileSessions','restCoreHistory','restCorePagination'];
const HISTORY_ID = 'go-compat-lab-history';
const HISTORY_TEXT = 'Hermes GO 升级前的合成会话';
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const seconds = value => typeof value === 'number' && Number.isFinite(value) && value > 0 && value < 100_000_000_000;
export function validateCoreStatus(value, version) {
  if (!object(value) || value.version !== version || typeof value.gateway_running !== 'boolean') throw new Error('status_shape');
}
export function validateCoreSessions(value) {
  if (!object(value) || !Array.isArray(value.sessions)) throw new Error('session_shape');
  const matches = value.sessions.filter(row => row?.id === HISTORY_ID);
  if (matches.length !== 1) throw new Error('session_identity');
  const row = matches[0];
  if (!seconds(row.last_active) || !Number.isSafeInteger(row.message_count) || row.message_count < 1
      || typeof row.archived !== 'boolean' || typeof row.is_default_profile !== 'boolean'
      || row.profile !== 'default') throw new Error('session_fields');
  for (const key of ['title','model','provider','cwd','source','git_branch','git_repo_root']) {
    if (row[key] !== undefined && row[key] !== null && typeof row[key] !== 'string') throw new Error('session_field_type');
  }
}
export function validateCoreHistory(value, {empty = false, pagination = false, offset = 0} = {}) {
  if (!object(value) || !Array.isArray(value.messages)) throw new Error('history_shape');
  if (empty) { if (value.messages.length !== 0) throw new Error('history_offset'); }
  else {
    if (value.messages.length !== 1) throw new Error('history_count');
    const row=value.messages[0];
    if (!object(row) || !Number.isSafeInteger(row.id) || row.id < 1 || row.role !== 'user'
        || row.content !== HISTORY_TEXT || !seconds(row.timestamp)) throw new Error('history_fields');
  }
  if (pagination && (!object(value.pagination) || value.pagination.limit !== 1 || value.pagination.offset !== offset
      || value.pagination.order !== 'oldest' || value.pagination.returned !== value.messages.length)) throw new Error('history_pagination');
}

async function body(response, maximum = 2 * 1024 * 1024) {
  if (!response.ok || !response.body) throw new Error('response');
  let count = 0; const chunks = [];
  const reader = response.body.getReader();
  try {
    while (true) {
      const item = await reader.read(); if (item.done) break;
      count += item.value.length; if (count > maximum) throw new Error('size');
      chunks.push(item.value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  return Buffer.concat(chunks).toString('utf8');
}
function websocket(url, rejectExpected) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url, {handshakeTimeout: 10_000, maxPayload: 1024 * 1024});
    const timer = setTimeout(() => finish(false), 20_000);
    let done = false;
    function finish(ok) {
      if (done) return; done = true; clearTimeout(timer); socket.terminate();
      ok ? resolve(true) : reject(new Error('websocket_probe'));
    }
    socket.on('error', () => finish(false));
    socket.on('unexpected-response', (_request, response) => {
      response.resume(); finish(rejectExpected && [401,403].includes(response.statusCode));
    });
    socket.on('open', () => {
      if (rejectExpected) { finish(false); return; }
      socket.send(JSON.stringify({jsonrpc:'2.0', id:'compatibility-probe', method:'session.active_list', params:{}}));
    });
    socket.on('message', raw => {
      try {
        const frame = JSON.parse(raw.toString());
        if (frame.id !== 'compatibility-probe') return;
        if (frame.error) throw new Error('rpc_error');
        parseActiveList(frame.result); finish(true);
      } catch { finish(false); }
    });
    socket.on('close', () => { if (!done) finish(false); });
  });
}
export async function probeContract({port, token, version}) {
  if (!Number.isInteger(port) || port < 1 || port > 65535 || typeof token !== 'string'
      || !/^[A-Za-z0-9_-]{32,128}$/.test(token) || !['0.21.3','0.22.5'].includes(version)) throw new Error('invalid_probe');
  const base = `http://127.0.0.1:${port}`, auth = new HermesAuth({baseUrl:base,sessionToken:token,requestTimeoutMs:15_000});
  const checks = {};
  async function check(name, fn) { try { await fn(); checks[name] = true; } catch { checks[name] = false; } }
  for (const [name, headers] of [['httpMissingToken',{}],['httpWrongToken',{'X-Hermes-Session-Token':'deliberately-wrong'}]]) {
    await check(name, async () => {
      const response = await fetch(base + '/api/profiles/active', {headers,redirect:'error',signal:AbortSignal.timeout(15_000)});
      await response.body?.cancel(); if (![401,403].includes(response.status)) throw new Error('not_rejected');
    });
  }
  await check('httpValidToken', async () => { await body(await auth.request('/api/profiles/active', {method:'GET'})); });
  await check('restCoreStatus', async () => validateCoreStatus(JSON.parse(await body(await auth.request('/api/status',{method:'GET'}))),version));
  await check('runningVersion', async () => {
    const status = JSON.parse(await body(await auth.request('/api/status', {method:'GET'})));
    if (status.version !== version) throw new Error('version');
  });
  for (const [name,path] of [['sessionList','/api/sessions?limit=100'], ['profileSessionList','/api/profiles/sessions?limit=100']]) {
    await check(name === 'sessionList' ? 'restCoreSessions' : 'restCoreProfileSessions', async () =>
      validateCoreSessions(JSON.parse(await body(await auth.request(path,{method:'GET'})))));
    await check(name, async () => {
      const value = JSON.parse(await body(await auth.request(path,{method:'GET'})));
      if (!Array.isArray(value.sessions) || !value.sessions.some(s=>s.id==='go-compat-lab-history')) throw new Error('history');
    });
  }
  await check('historyRead', async () => {
    const value = JSON.parse(await body(await auth.request('/api/sessions/go-compat-lab-history/messages',{method:'GET'})));
    if (!JSON.stringify(value).includes('Hermes GO 升级前的合成会话')) throw new Error('history');
  });
  await check('restCoreHistory', async () => validateCoreHistory(JSON.parse(await body(
    await auth.request(`/api/sessions/${HISTORY_ID}/messages`,{method:'GET'})))));
  await check('restCorePagination', async () => {
    for (const offset of [0,1]) validateCoreHistory(JSON.parse(await body(await auth.request(
      `/api/sessions/${HISTORY_ID}/messages?limit=1&offset=${offset}&order=oldest`,{method:'GET'}))),
      {empty:offset===1,pagination:true,offset});
  });
  await check('connectorSchema', async () => {
    const report = evaluateHermesContract({openapi:{kind:'ok',body:await body(await auth.openApiDocument(),8*1024*1024)},hermesVersion:version,checkedAt:new Date()});
    if (report.status !== 'compatible') throw new Error('contract');
  });
  await check('websocketMissingToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws', true));
  await check('websocketWrongToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws?token=deliberately-wrong', true));
  await check('connectorActiveListRPC', async () => websocket(await auth.websocketUrl('/api/ws'), false));
  return {schemaVersion:1,kind:'live-connector-contract-probe',version,restScope:CORE_REST_SCOPE,checks,compatibilityApproved:false};
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    let input=''; for await (const chunk of process.stdin) { input+=chunk; if(input.length>4096)throw new Error('input'); }
    console.log(JSON.stringify(await probeContract(JSON.parse(input))));
  } catch { console.error('HR-HERMESCOMPAT-004：接口验证未完成，请查看测试记录。 / Contract verification incomplete; review the test record.'); process.exitCode=1; }
}
