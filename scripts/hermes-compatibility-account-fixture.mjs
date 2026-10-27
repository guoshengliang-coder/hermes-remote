#!/usr/bin/env node
// Seeds only a freshly-created disposable cluster. No login, email or real administrator grant.
import {createHash,generateKeyPairSync,randomBytes,randomUUID} from 'node:crypto';
import {lstat,realpath,readdir,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname,resolve,basename} from 'node:path';
import {pathToFileURL} from 'node:url';
import pg from 'pg';
import {TokenCodec} from '../gateway/dist/account/token-codec.js';
const hash=data=>createHash('sha256').update(data).digest('hex');
export async function seedAccount({url,dataDirectory,workspace,web=false}){
  if(typeof web!=='boolean')throw Error('invalid_web_fixture');
  const info=await lstat(workspace),canonical=await realpath(workspace),root=dirname(dirname(canonical));
  const rootInfo=await lstat(root);
  if(dirname(root)!==await realpath(tmpdir())||rootInfo.uid!==process.getuid()||(rootInfo.mode&0o077)||canonical!==workspace||!info.isDirectory()||info.uid!==process.getuid()||(info.mode&0o077)
    ||basename(dirname(canonical))!=='tmp'||!basename(root).startsWith('hermes-compat-lab-')
    ||dataDirectory!==resolve(workspace,'postgres-data'))throw Error('unsafe_account_fixture');
  const address=new URL(url);
  if(address.protocol!=='postgresql:'||address.hostname!=='127.0.0.1'||!/^\d+$/.test(address.port)
    ||address.username!=='hermes_compat'||!address.password||address.pathname!=='/postgres'||address.search||address.hash)
    throw Error('unsafe_account_database');
  const pool=new pg.Pool({connectionString:url,max:1,connectionTimeoutMillis:3000,query_timeout:15000});
  try{
    const identity=await pool.query("SELECT current_setting('data_directory') AS directory");
    if(await realpath(identity.rows[0]?.directory)!==dataDirectory)throw Error('database_identity_mismatch');
    const tables=await pool.query("SELECT count(*)::int AS count FROM pg_tables WHERE schemaname='public'");
    if(tables.rows[0].count!==0)throw Error('account_database_not_empty');
    const folder=new URL('../gateway/migrations/',import.meta.url),records=[];
    for(const name of (await readdir(folder)).filter(n=>/^\d{3}_[a-z0-9_]+\.sql$/.test(n)).sort()){
      const bytes=await readFile(new URL(name,folder));records.push([name,hash(bytes)]);await pool.query(bytes.toString());
    }
    const tokenKey=randomBytes(32).toString('base64url'),codec=new TokenCodec(tokenKey);
    const account=randomUUID(),stranger=randomUUID(),phone=randomUUID(),otherPhone=randomUUID(),desktop=randomUUID();
    const session=randomUUID(),otherSession=randomUUID(),binding=randomUUID(),device='compat-account-'+randomUUID();
    const token=codec.issueAccessToken(),otherToken=codec.issueAccessToken();
    const keys=generateKeyPairSync('ed25519'),publicKey=keys.publicKey.export({format:'der',type:'spki'}).subarray(-32);
    const fingerprint=hash(publicKey);
    await pool.query('INSERT INTO accounts (id) VALUES ($1),($2)',[account,stranger]);
    await pool.query(`INSERT INTO installations (id,account_id,client_installation_id,kind,platform,display_name,app_version)
      VALUES ($1,$2,$1,'phone','android','Synthetic phone','test'),($3,$4,$3,'phone','android','Synthetic other phone','test'),
      ($5,$2,$5,'desktop','macos','Synthetic Mac','test')`,[phone,account,otherPhone,stranger,desktop]);
    for(const [id,owner,installation,access] of [[session,account,phone,token],[otherSession,stranger,otherPhone,otherToken]]){
      await pool.query(`INSERT INTO account_sessions (id,account_id,installation_id,refresh_family_id,access_token_hash,access_expires_at)
        VALUES ($1,$2,$3,$1,$4,now()+interval '1 hour')`,[id,owner,installation,codec.hashAccessToken(access)]);
      await pool.query(`INSERT INTO refresh_tokens (id,session_id,family_id,token_hash,expires_at)
        VALUES ($1,$2,$2,$3,now()+interval '1 day')`,[randomUUID(),id,randomBytes(32).toString('hex')]);
    }
    await pool.query(`INSERT INTO connector_bindings (id,account_id,desktop_installation_id,display_name,device_id,public_key,
      key_algorithm,public_key_fingerprint,generation,status,activated_at)
      VALUES ($1,$2,$3,'Synthetic Mac',$4,$5,'Ed25519',$6,1,'active',now())`,[binding,account,desktop,device,publicKey,fingerprint]);
    let browserSession;
    if(web){
      const installation=randomUUID(),id=randomUUID(),access=codec.issueAccessToken(),refresh=codec.issueRefreshToken();
      await pool.query(`INSERT INTO installations (id,account_id,client_installation_id,kind,platform,display_name,app_version)
        VALUES ($1,$2,$1,'browser','web','Synthetic browser','test')`,[installation,account]);
      await pool.query(`INSERT INTO account_sessions (id,account_id,installation_id,refresh_family_id,access_token_hash,access_expires_at)
        VALUES ($1,$2,$3,$1,$4,now()+interval '1 hour')`,[id,account,installation,codec.hashAccessToken(access)]);
      await pool.query(`INSERT INTO refresh_tokens (id,session_id,family_id,token_hash,expires_at)
        VALUES ($1,$2,$2,$3,now()+interval '1 day')`,[randomUUID(),id,codec.hashRefreshToken(refresh)]);
      browserSession={token:access,refreshToken:refresh,installationId:installation};
    }
    const credentialPath=resolve(workspace,'account-credential.json');
    await writeFile(credentialPath,JSON.stringify({schemaVersion:1,bindingId:binding,generation:1,publicKeyFingerprint:fingerprint,
      privateKey:keys.privateKey.export({format:'der',type:'pkcs8'}).subarray(-32).toString('base64url')}),{flag:'wx',mode:0o600});
    return {tokenKey,token,otherToken,device,credentialPath,...(browserSession?{browserSession}:{}),migrationsSha256:hash(JSON.stringify(records)),
      databaseIdentityVerified:true,emptyDatabaseVerified:true};
  }finally{await pool.end();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 try{let input='';for await(const part of process.stdin){input+=part;if(input.length>8192)throw Error('input');}
   console.log(JSON.stringify(await seedAccount(JSON.parse(input))));
 }catch{console.error('HR-HERMESCOMPAT-004：账号验证环境未就绪，请查看测试记录。 / Account verification environment is not ready; review the test record.');process.exitCode=1;}
}
