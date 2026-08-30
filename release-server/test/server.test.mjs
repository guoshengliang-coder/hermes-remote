import assert from 'node:assert/strict';
import {mkdtemp, symlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {Writable} from 'node:stream';
import {createReleaseHandler} from '../src/server.mjs';

const entry = {versionName:'1.2.3',versionCode:3,applicationId:'com.hermes.remote',channel:'internal',publishedAt:'2026-01-01T00:00:00Z',fileName:'Hermes-Remote-1.2.3-debug.apk',downloadUrl:'https://mrlgs.net/releases/Hermes-Remote-1.2.3-debug.apk',sizeBytes:3,sha256:'a'.repeat(64),certificateSha256:'06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',minSdk:26,releaseNotes:['x'],sourceCommit:'abc'};

async function fixture() {
  const root = await mkdtemp(path.join(tmpdir(), 'release-server-'));
  await writeFile(path.join(root, entry.fileName), 'apk');
  await writeFile(path.join(root, 'secret.txt'), 'no');
  await writeFile(path.join(root, 'index.json'), JSON.stringify({schemaVersion:1,channel:'internal',latestVersionCode:3,generatedAt:'2026-01-01T00:00:00Z',versions:[entry]}));
  const handler=createReleaseHandler(root);
  const request = async (url,method='GET') => await new Promise((resolve,reject)=>{
    const chunks=[];const res=new Writable({write(chunk,encoding,done){chunks.push(Buffer.from(chunk));done();}});
    res.writeHead=(status,headers={})=>{res.status=status;res.headers=Object.fromEntries(Object.entries(headers).map(([k,v])=>[k.toLowerCase(),String(v)]));};
    res.on('finish',()=>resolve({status:res.status,headers:{get:k=>res.headers?.[k.toLowerCase()]??null},text:async()=>Buffer.concat(chunks).toString()}));res.on('error',reject);
    Promise.resolve(handler({url,method},res)).catch(reject);
  });
  request.root=root;
  return request;
}

test('health supports GET and HEAD', async()=>{const request=await fixture();for (const method of ['GET','HEAD']) assert.equal((await request('/health',method)).status,200);});
test('index GET/HEAD is no-store', async()=>{const request=await fixture();for (const method of ['GET','HEAD']) {const r=await request('/releases/index.json',method);assert.equal(r.status,200);assert.equal(r.headers.get('cache-control'),'no-store');}});
test('registered APK GET and HEAD expose safe headers', async()=>{const request=await fixture();for (const method of ['GET','HEAD']) {const r=await request(`/releases/${entry.fileName}`,method);assert.equal(r.status,200);assert.equal(r.headers.get('content-length'),'3');assert.equal(r.headers.get('content-type'),'application/vnd.android.package-archive');assert.match(r.headers.get('content-disposition'),/attachment/);assert.match(r.headers.get('cache-control'),/immutable/);if(method==='GET') assert.equal(await r.text(),'apk');}});
test('root redirects to latest versioned APK', async()=>{const request=await fixture();const r=await request('/');assert.equal(r.status,302);assert.equal(r.headers.get('location'),`/releases/${entry.fileName}`);});
test('rejects missing, unregistered and traversal paths', async()=>{const request=await fixture();for(const target of ['/missing','/releases/secret.txt','/releases/%2e%2e/secret.txt','/releases/%2Fetc%2Fpasswd']) assert.equal((await request(target)).status,404);});
test('rejects unsupported methods', async()=>{const request=await fixture();const r=await request('/releases/index.json','POST');assert.equal(r.status,405);assert.equal(r.headers.get('allow'),'GET, HEAD');});

test('empty valid index serves index but root is 404',async()=>{
  const root=await mkdtemp(path.join(tmpdir(),'release-empty-'));
  await writeFile(path.join(root,'index.json'),JSON.stringify({schemaVersion:1,channel:'internal',latestVersionCode:0,generatedAt:'2026-01-01T00:00:00Z',versions:[]}));
  const handler=createReleaseHandler(root);
  const request=async url=>await new Promise((resolve,reject)=>{const res=new Writable({write(c,e,d){d();}});res.writeHead=(status)=>{res.status=status};res.on('finish',()=>resolve(res.status));Promise.resolve(handler({url,method:'GET'},res)).catch(reject)});
  assert.equal(await request('/releases/index.json'),200);assert.equal(await request('/'),404);
});

test('invalid indexes and referenced files fail closed',async()=>{
  const invalid=[
    {...entry,versionName:'1.2'},
    {...entry,fileName:'evil\"\r\nX-Evil: yes.apk'},
    {...entry,downloadUrl:'https://mrlgs.net:444/releases/Hermes-Remote-1.2.3-debug.apk'},
    {...entry,downloadUrl:'https://mrlgs.net/releases/Hermes-Remote-1.2.3-debug.apk?q=1'},
  ];
  for(const bad of invalid){const root=await mkdtemp(path.join(tmpdir(),'release-invalid-'));await writeFile(path.join(root,'index.json'),JSON.stringify({schemaVersion:1,channel:'internal',latestVersionCode:bad.versionCode,generatedAt:'2026-01-01T00:00:00Z',versions:[bad]}));const handler=createReleaseHandler(root);const status=await new Promise(resolve=>{const res=new Writable({write(c,e,d){d();}});res.writeHead=s=>res.status=s;res.on('finish',()=>resolve(res.status));handler({url:'/releases/index.json',method:'GET'},res)});assert.equal(status,500);}
});
test('index references to missing or wrong-size APKs return 404',async()=>{
  const request=await fixture();
  const missing={...entry,versionName:'1.2.4',versionCode:4,fileName:'Hermes-Remote-1.2.4-debug.apk',downloadUrl:'https://mrlgs.net/releases/Hermes-Remote-1.2.4-debug.apk'};
  await writeFile(path.join(request.root,'index.json'),JSON.stringify({schemaVersion:1,channel:'internal',latestVersionCode:4,generatedAt:'2026-01-01T00:00:00Z',versions:[missing]}));
  assert.equal((await request(`/releases/${missing.fileName}`)).status,404);
  await writeFile(path.join(request.root,'index.json'),JSON.stringify({schemaVersion:1,channel:'internal',latestVersionCode:3,generatedAt:'2026-01-01T00:00:00Z',versions:[{...entry,sizeBytes:4}]}));
  assert.equal((await request(`/releases/${entry.fileName}`)).status,404);
});
test('rejects symlink APKs and oversized indexes',async()=>{const request=await fixture();await writeFile(path.join(request.root,'real.apk'),'apk');await writeFile(path.join(request.root,entry.fileName),'gone');await (await import('node:fs/promises')).rm(path.join(request.root,entry.fileName));await symlink(path.join(request.root,'real.apk'),path.join(request.root,entry.fileName));assert.equal((await request(`/releases/${entry.fileName}`)).status,404);await writeFile(path.join(request.root,'index.json'),' '.repeat(1024*1024+1));assert.equal((await request('/releases/index.json')).status,500);});
