import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {probeWeb,permittedProxyPath,permittedSocketPath} from '../hermes-compatibility-web-probe.mjs';
test('browser fixture refuses unsafe executables and quarantines uncertain cleanup',()=>{
  const result=spawnSync('python3',['-B',fileURLToPath(new URL('./hermes-compatibility-web-fixture.test.py',import.meta.url))],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
});
test('browser probe rejects foreign origins and malformed ports before certificate or browser access',async()=>{
  const valid={port:1234,gatewayPort:5678,origin:'https://127.0.0.1:1234',session:{token:'hga_'+'a'.repeat(43),refreshToken:'hgr_'+'b'.repeat(43)}};
  for(const patch of [{port:0},{gatewayPort:65536},{origin:'https://example.invalid'},{session:{token:'private'}}])
    await assert.rejects(probeWeb({...valid,...patch}));
});

test('browser proxy permits local refresh but rejects login and email routes',()=>{
  assert.equal(permittedProxyPath('/v2/web/auth/refresh'),true);
  assert.equal(permittedProxyPath('/v2/web/session'),true);
  for(const path of [undefined,'https://example.invalid','//example.invalid','/v2/web/auth/email/start','/v2/web/auth/refresh?redirect=x'])
    assert.equal(permittedProxyPath(path),false);
});

test('browser socket proxy uses the real device route and rejects auth or foreign paths',()=>{
  assert.equal(permittedSocketPath('/v2/devices/compat-account-123/ws'),true);
  for(const path of ['/v2/web/devices/123/ws','/v2/devices/123/voice','//example.invalid/ws','/v2/devices/123/ws?token=x'])
    assert.equal(permittedSocketPath(path),false);
});
