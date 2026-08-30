import {constants} from 'node:fs';
import {open, readFile} from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {MAX_INDEX_BYTES, validateIndex} from './schema.mjs';

const send = (res,status,headers={},body='') => {res.writeHead(status,headers);res.end(body);};
export function createReleaseHandler(dataRoot) {
  return async (req,res) => {
    try {
      if (!['GET','HEAD'].includes(req.method)) return send(res,405,{Allow:'GET, HEAD'});
      const pathname = new URL(req.url,'https://localhost').pathname;
      if (pathname==='/health'||pathname==='/ping') return send(res,200,{'Content-Type':'text/plain','Content-Length':'2'},req.method==='HEAD'?'':'ok');
      const indexHandle=await open(path.join(dataRoot,'index.json'),constants.O_RDONLY|constants.O_NOFOLLOW);
      let indexText;try{const indexInfo=await indexHandle.stat();if(!indexInfo.isFile()||indexInfo.size>MAX_INDEX_BYTES)throw new Error('invalid index size');indexText=await indexHandle.readFile('utf8');}finally{await indexHandle.close();}
      const index=validateIndex(JSON.parse(indexText));
      if(pathname==='/') {const latest=index.versions.find(v=>v.versionCode===index.latestVersionCode);return latest?send(res,302,{Location:`/releases/${encodeURIComponent(latest.fileName)}`}):send(res,404);}
      if(pathname==='/releases/index.json') return send(res,200,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Content-Length':Buffer.byteLength(indexText)},req.method==='HEAD'?'':indexText);
      const prefix='/releases/';
      if(!pathname.startsWith(prefix)) return send(res,404);
      let fileName; try {fileName=decodeURIComponent(pathname.slice(prefix.length));} catch {return send(res,404);}
      if(!fileName||/[/\\]/.test(fileName)||fileName==='index.json') return send(res,404);
      const version=index.versions.find(v=>v.fileName===fileName);
      if(!version) return send(res,404);
      const filePath=path.join(dataRoot,fileName);const file=await open(filePath,constants.O_RDONLY|constants.O_NOFOLLOW);const info=await file.stat();
      if(!info.isFile()||info.size!==version.sizeBytes){await file.close();return send(res,404);}
      const headers={'Content-Type':'application/vnd.android.package-archive','Content-Length':String(info.size),'Content-Disposition':`attachment; filename="${fileName}"`,'Cache-Control':'public, max-age=31536000, immutable','X-Content-Type-Options':'nosniff'};
      if(req.method==='HEAD'){await file.close();return send(res,200,headers);}
      res.writeHead(200,headers);file.createReadStream({autoClose:true}).pipe(res);
    } catch (error) {if(['ENOENT','ELOOP'].includes(error?.code)) return send(res,404); console.error(error);send(res,500);}
  };
}

if (process.argv[1]===fileURLToPath(import.meta.url)) {
  const {TLS_CERT,TLS_KEY,RELEASE_DATA_ROOT,PORT='443'}=process.env;
  if(!TLS_CERT||!TLS_KEY||!RELEASE_DATA_ROOT) throw new Error('TLS_CERT, TLS_KEY and RELEASE_DATA_ROOT are required');
  const [cert,key]=await Promise.all([readFile(TLS_CERT),readFile(TLS_KEY)]);
  https.createServer({cert,key},createReleaseHandler(RELEASE_DATA_ROOT)).listen(Number(PORT));
}
