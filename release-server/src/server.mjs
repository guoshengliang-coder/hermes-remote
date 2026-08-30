import {constants} from 'node:fs';
import {open, readFile} from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {MAX_INDEX_BYTES, validateIndex} from './schema.mjs';

const send = (res,status,headers={},body='') => {res.writeHead(status,headers);res.end(body);};

function parseRange(value, size) {
  if (!value) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value.trim());
  if (!match || (!match[1] && !match[2])) return false;
  let start;
  let end;
  if (!match[1]) {
    const suffixLength = Number(match[2]);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) return false;
    start = Math.max(0, size - suffixLength);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)) return false;
  }
  if (start < 0 || start >= size || end < start) return false;
  return {start, end: Math.min(end, size - 1)};
}

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
      const range=parseRange(req.headers?.range,info.size);
      if(range===false){await file.close();return send(res,416,{'Content-Range':`bytes */${info.size}`,'Accept-Ranges':'bytes'});}
      const contentLength=range?range.end-range.start+1:info.size;
      const headers={'Content-Type':'application/vnd.android.package-archive','Content-Length':String(contentLength),'Content-Disposition':`attachment; filename="${fileName}"`,'Cache-Control':'public, max-age=31536000, immutable','X-Content-Type-Options':'nosniff','Accept-Ranges':'bytes'};
      if(range) headers['Content-Range']=`bytes ${range.start}-${range.end}/${info.size}`;
      if(req.method==='HEAD'){await file.close();return send(res,range?206:200,headers);}
      res.writeHead(range?206:200,headers);file.createReadStream({autoClose:true,...(range??{})}).pipe(res);
    } catch (error) {if(['ENOENT','ELOOP'].includes(error?.code)) return send(res,404); console.error(error);send(res,500);}
  };
}

if (process.argv[1]===fileURLToPath(import.meta.url)) {
  const {TLS_CERT,TLS_KEY,RELEASE_DATA_ROOT,PORT='443',HOST='0.0.0.0'}=process.env;
  if(!TLS_CERT||!TLS_KEY||!RELEASE_DATA_ROOT) throw new Error('TLS_CERT, TLS_KEY and RELEASE_DATA_ROOT are required');
  const [cert,key]=await Promise.all([readFile(TLS_CERT),readFile(TLS_KEY)]);
  https.createServer({cert,key},createReleaseHandler(RELEASE_DATA_ROOT)).listen(Number(PORT),HOST);
}
