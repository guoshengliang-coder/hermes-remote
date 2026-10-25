#!/usr/bin/env node
// Actual Connector adapters against a disposable upstream server. No model calls or mutation RPCs.
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import WebSocket from 'ws';
import { HermesAuth } from '../connector/dist/hermes-auth.js';
import { evaluateHermesContract } from '../connector/dist/hermes-contract.js';
import { parseActiveList } from '../connector/dist/session-observer.js';

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
  await check('runningVersion', async () => {
    const status = JSON.parse(await body(await auth.request('/api/status', {method:'GET'})));
    if (status.version !== version) throw new Error('version');
  });
  for (const [name,path] of [['sessionList','/api/sessions?limit=100'], ['profileSessionList','/api/profiles/sessions?limit=100']]) {
    await check(name, async () => {
      const value = JSON.parse(await body(await auth.request(path,{method:'GET'})));
      if (!Array.isArray(value.sessions) || !value.sessions.some(s=>s.id==='go-compat-lab-history')) throw new Error('history');
    });
  }
  await check('historyRead', async () => {
    const value = JSON.parse(await body(await auth.request('/api/sessions/go-compat-lab-history/messages',{method:'GET'})));
    if (!JSON.stringify(value).includes('Hermes GO 升级前的合成会话')) throw new Error('history');
  });
  await check('connectorSchema', async () => {
    const report = evaluateHermesContract({openapi:{kind:'ok',body:await body(await auth.openApiDocument(),8*1024*1024)},hermesVersion:version,checkedAt:new Date()});
    if (report.status !== 'compatible') throw new Error('contract');
  });
  await check('websocketMissingToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws', true));
  await check('websocketWrongToken', () => websocket(base.replace('http:', 'ws:') + '/api/ws?token=deliberately-wrong', true));
  await check('connectorActiveListRPC', async () => websocket(await auth.websocketUrl('/api/ws'), false));
  return {schemaVersion:1,kind:'live-connector-contract-probe',version,checks,compatibilityApproved:false};
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    let input=''; for await (const chunk of process.stdin) { input+=chunk; if(input.length>4096)throw new Error('input'); }
    console.log(JSON.stringify(await probeContract(JSON.parse(input))));
  } catch { console.error('HR-HERMESCOMPAT-004：接口验证未完成，请查看测试记录。 / Contract verification incomplete; review the test record.'); process.exitCode=1; }
}
