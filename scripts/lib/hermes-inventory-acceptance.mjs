// Synthetic CI acceptance, using the real disposable Gateway supplied by the caller.
// Client archives are inert fixture bytes; no claim about APK/DMG executability is made.
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { createServer } from 'node:http';
import { mkdtemp, mkdir, readFile, writeFile, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { packageWebApp } from '../package-web-app.mjs';
import { prepareReleaseInventory } from '../hermes-compatibility-inventory.mjs';
import { CompatibilitySigner, releasedInventory, parseCompatibilityPublicationConfiguration } from '../../gateway/dist/account/hermes-compatibility-publication.js';

const hash=value=>createHash('sha256').update(value).digest('hex');
export async function verifyInventoryAcceptance({manifestPath,containerName,gateway}) {
  const root=await realpath(await mkdtemp(join(tmpdir(),'hermes-inventory-acceptance-')));let server;
  try {
    const origin='https://mrlgs.net',version='1.0.0',sourceCommit='a'.repeat(40);
    const createdAt=new Date().toISOString().replace(/\.\d{3}Z$/,'Z');
    const expiresAt=new Date(Date.now()+60*60_000).toISOString().replace(/\.\d{3}Z$/,'Z');
    const blobs={desktop:Buffer.from('synthetic DMG fixture'),android:Buffer.from('synthetic APK fixture'),connector:Buffer.from('synthetic Connector fixture')};
    const routes=new Map();
    const desktop={schemaVersion:1,channel:'internal',architecture:'arm64',appVersion:version,buildNumber:1,minimumMacOS:'14.0',
      downloadURL:`${origin}/desktop/apps/${version}/Hermes-Go-Desktop-${version}.dmg`,sizeBytes:blobs.desktop.length,sha256:hash(blobs.desktop),releaseNotes:[],sourceCommit,updatedAt:createdAt};
    const android={versionName:version,versionCode:1,applicationId:'com.hermes.remote',channel:'internal',publishedAt:createdAt,
      fileName:`Hermes-Remote-${version}-debug.apk`,downloadUrl:`${origin}/releases/Hermes-Remote-${version}-debug.apk`,
      sizeBytes:blobs.android.length,sha256:hash(blobs.android),certificateSha256:'06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
      minSdk:26,releaseNotes:[],sourceCommit};
    routes.set(new URL(desktop.downloadURL).pathname,blobs.desktop);routes.set(new URL(android.downloadUrl).pathname,blobs.android);
    const pair=generateKeyPairSync('ed25519'),keyId='synthetic-release';
    const prefix=`${origin}/desktop/releases/${version}/`;
    const connectorFile=`Hermes-Connector-${version}-arm64.tar.gz`;
    const release={schemaVersion:1,releaseVersion:version,channel:'internal',platform:'macos',architecture:'arm64',minimumMacOS:'14.0',createdAt,expiresAt,
      artifacts:[{component:'hermes_server',version,entrypoint:'bin/hermes-server',fileName:`Hermes-Server-${version}-arm64.tar.gz`,
        downloadURL:prefix+`Hermes-Server-${version}-arm64.tar.gz`,sizeBytes:1,sha256:'b'.repeat(64)},
      {component:'connector',version,entrypoint:'bin/hermes-connector',fileName:connectorFile,downloadURL:prefix+connectorFile,
        sizeBytes:blobs.connector.length,sha256:hash(blobs.connector)}]};
    const bytes=Buffer.from(JSON.stringify(release));
    const envelope={payload:bytes.toString('base64url'),algorithm:'Ed25519',keyId,signature:sign(null,bytes,pair.privateKey).toString('base64url')};
    routes.set(`/desktop/releases/${version}/Hermes-Desktop-${version}-arm64.manifest.json`,Buffer.from(JSON.stringify(envelope)));
    routes.set(new URL(prefix+connectorFile).pathname,blobs.connector);
    const dist=join(root,'dist'),out=join(root,'out');await mkdir(join(dist,'assets'),{recursive:true});await mkdir(out);
    await writeFile(join(dist,'index.html'),'<html>synthetic inventory</html>');await writeFile(join(dist,'assets/main.js'),'synthetic script');
    const web=await packageWebApp({distDir:dist,outputDir:out,version,sourceCommit});
    const webManifest=JSON.parse(await readFile(web.manifestPath,'utf8'));
    routes.set('/app/',await readFile(join(dist,'index.html')));routes.set('/app/assets/main.js',await readFile(join(dist,'assets/main.js')));
    server=createServer((req,res)=>{const data=routes.get(req.url);res.writeHead(data?200:404);res.end(data??'missing');});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const fetcher=(url,options)=>{
      assert.equal(new URL(url).origin,origin);assert.equal(options.credentials,'omit');assert.equal(options.redirect,'error');
      return fetch(`http://127.0.0.1:${server.address().port}${new URL(url).pathname}`,options);
    };
    const component=name=>({name,version,artifactSha256:hash(blobs[name])});
    const components=[component('desktop'),component('connector'),gateway,component('android'),{name:'web',version,artifactSha256:webManifest.archiveSha256}];
    const policy={schemaVersion:1,origin,combinations:[{components,sources:{desktop:{manifest:desktop},android:{manifest:android},
      connector:{releaseVersion:version,layout:'bundled',architecture:'arm64',channel:'internal',keyId,publicKey:pair.publicKey.export({format:'jwk'}).x},
      gateway:{manifestPath,containerName},web:{manifestPath:web.manifestPath,archivePath:web.archivePath}}}]};
    const draft=await prepareReleaseInventory(policy,{fetcher});
    assert.equal(draft.evidence.length,5);assert.ok(Date.parse(draft.payload.expiresAt)<=Date.parse(expiresAt));
    const signerPair=generateKeyPairSync('ed25519');
    const signer=new CompatibilitySigner('synthetic-inventory',signerPair.privateKey.export({format:'pem',type:'pkcs8'}),origin);
    const inventory=signer.signInventory(draft.payload);
    assert.deepEqual(releasedInventory(inventory,new Map([[signer.id,signer.publicKey]]),origin,{components}),components);
    const configurationPath=join(root,'publication.json'),envelopePath=join(root,'candidate.json');
    const trust={id:signer.id,publicKeyPem:signer.publicKey.export({format:'pem',type:'spki'})};
    const configuration={schemaVersion:1,origin,goCommit:sourceCommit,signingKeys:[trust],releaseKeys:[trust],inventoryFile:join(root,'inventory.json')};
    await writeFile(configurationPath,JSON.stringify(configuration),{mode:0o600});
    await writeFile(envelopePath,JSON.stringify(inventory),{mode:0o600});
    const install=action=>spawnSync('python3',['-I','-B',resolve('scripts/hermes-compatibility-inventory-install.py'),action,configurationPath,envelopePath],
      {encoding:'utf8',timeout:15_000,maxBuffer:64*1024});
    const initial=install('initialize');assert.equal(initial.status,0);assert.equal(JSON.parse(initial.stdout).status,'installed');
    const policyReader=parseCompatibilityPublicationConfiguration(configuration);
    assert.deepEqual(releasedInventory(policyReader.readInventory(),policyReader.releaseKeys,origin,{components}),components);
    assert.equal(JSON.parse(install('update').stdout).status,'unchanged');
    await delay(1100); // issuedAt is canonical whole seconds; a distinct generation must be later.
    const empty=await prepareReleaseInventory({schemaVersion:1,origin,combinations:[]});
    await writeFile(envelopePath,JSON.stringify(signer.signInventory(empty.payload)),{mode:0o600});
    assert.equal(install('update').status,0);
    assert.deepEqual(releasedInventory(policyReader.readInventory(),policyReader.releaseKeys,origin,{components}),[]);
    await writeFile(envelopePath,JSON.stringify(inventory),{mode:0o600});assert.equal(install('update').status,1);
    assert.deepEqual(releasedInventory(policyReader.readInventory(),policyReader.releaseKeys,origin,{components}),[]);
    routes.set(new URL(desktop.downloadURL).pathname,Buffer.from('changed fixture'));
    await assert.rejects(prepareReleaseInventory(policy,{fetcher}),error=>error.code==='HR-HERMESCOMPAT-004');
    return {schemaVersion:1,kind:'inventory-producer-acceptance',scope:'real-disposable-gateway-and-synthetic-public-client-records',
      collectedComponents:5,signAndVerifyPassed:true,changedClientRejected:true,atomicInstallPassed:true,
      retryIdempotent:true,emptyInventoryApplied:true,oldInventoryRejected:true,compatibilityApproved:false};
  }finally{
    if(server)await new Promise(resolve=>{server.close(resolve);server.closeAllConnections();});
    await rm(root,{recursive:true,force:true});
  }
}
