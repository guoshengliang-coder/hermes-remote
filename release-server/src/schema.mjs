const HEX64 = /^[0-9a-f]{64}$/i;
const ISO = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/;
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const COMMIT = /^[0-9a-f]{3,64}$/i;
const INTERNAL_CERT = '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5';
const requiredString = (object, key) => { if (typeof object?.[key] !== 'string' || !object[key]) throw new Error(`invalid ${key}`); };
const validIso = value => {
  if (!ISO.test(value) || Number.isNaN(Date.parse(value))) return false;
  const canonical = new Date(value).toISOString();
  return canonical === value || canonical.replace('.000Z','Z') === value;
};
export const MAX_INDEX_BYTES = 1024 * 1024;
export const MAX_VERSIONS = 100;
export const MAX_RELEASE_NOTES = 20;
export const MAX_RELEASE_NOTE_LENGTH = 500;

export function validateVersion(value) {
  for (const key of ['versionName','applicationId','channel','publishedAt','fileName','downloadUrl','sha256','certificateSha256','sourceCommit']) requiredString(value,key);
  if (!SEMVER.test(value.versionName)) throw new Error('invalid versionName');
  if (!Number.isSafeInteger(value.versionCode) || value.versionCode < 1) throw new Error('invalid versionCode');
  if (!Number.isSafeInteger(value.sizeBytes) || value.sizeBytes < 1) throw new Error('invalid sizeBytes');
  if (!Number.isSafeInteger(value.minSdk) || value.minSdk < 1) throw new Error('invalid minSdk');
  if (!Array.isArray(value.releaseNotes) || value.releaseNotes.length > MAX_RELEASE_NOTES || value.releaseNotes.some(note => typeof note !== 'string' || !note.trim() || note.length > MAX_RELEASE_NOTE_LENGTH || /[\u0000-\u001f\u007f]/.test(note))) throw new Error('invalid releaseNotes');
  if (!HEX64.test(value.sha256) || !HEX64.test(value.certificateSha256)) throw new Error('invalid digest');
  if (!COMMIT.test(value.sourceCommit)) throw new Error('invalid sourceCommit');
  if (value.applicationId !== 'com.hermes.remote' || value.channel !== 'internal' || value.certificateSha256.toLowerCase() !== INTERNAL_CERT) throw new Error('incompatible release identity');
  if (!validIso(value.publishedAt)) throw new Error('invalid publishedAt');
  const expectedFile = `Hermes-Remote-${value.versionName}-debug.apk`;
  if (value.fileName !== expectedFile || /[\u0000-\u001f\u007f"'\\/]/.test(value.fileName)) throw new Error('invalid fileName');
  let url; try { url = new URL(value.downloadUrl); } catch { throw new Error('invalid downloadUrl'); }
  if (url.protocol !== 'https:' || url.hostname !== 'mrlgs.net' || (url.port && url.port !== '443') || url.username || url.password || url.search || url.hash || url.pathname !== `/releases/${encodeURIComponent(value.fileName)}`) throw new Error('invalid downloadUrl');
  return value;
}

export function validateIndex(value) {
  if (value?.schemaVersion !== 1 || value.channel !== 'internal' || !validIso(value.generatedAt) || !Number.isSafeInteger(value.latestVersionCode) || !Array.isArray(value.versions) || value.versions.length > MAX_VERSIONS) throw new Error('invalid index');
  value.versions.forEach(validateVersion);
  const codes=new Set(), names=new Set();
  value.versions.forEach((version,index)=>{if(codes.has(version.versionCode)||names.has(version.versionName)) throw new Error('duplicate version');if(index&&value.versions[index-1].versionCode<=version.versionCode) throw new Error('versions not descending');codes.add(version.versionCode);names.add(version.versionName);});
  if (value.latestVersionCode !== (value.versions[0]?.versionCode ?? 0)) throw new Error('invalid latestVersionCode');
  return value;
}
