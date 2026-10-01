// Account-bound delivery metadata lives in its own IndexedDB, never in the shell HTTP cache.
let pushQueue = Promise.resolve();
function pushDatabase() {
  return new Promise((resolve,reject) => {
    const request = indexedDB.open("hermes-go-push",1);
    request.onupgradeneeded = () => request.result.createObjectStore("state");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function readPushState(db) {
  return new Promise((resolve,reject) => {
    const tx = db.transaction("state","readonly");
    const request = tx.objectStore("state").get("current");
    request.onsuccess = () => resolve(request.result || {channelId:null,seen:[],unread:{}});
    request.onerror = () => reject(request.error);
  });
}
async function savePushState(db,state) {
  return new Promise((resolve,reject) => {
    const tx = db.transaction("state","readwrite");
    tx.objectStore("state").put(state,"current");
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}
function withPushState(work) {
  const result = pushQueue.then(async () => {
    const db = await pushDatabase();
    try { const state = await readPushState(db);await work(state);await savePushState(db,state); }
    finally { db.close(); }
  });
  pushQueue = result.catch(() => {});
  return result;
}
const PUSH_EVENTS = ["run.waiting","run.completed","run.interrupted","run.unknown"];
const PUSH_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;
function validPush(value) {
  return value && typeof value.channelId === "string" && /^[a-f0-9-]{36}$/i.test(value.channelId)
    && Number.isFinite(Date.parse(value.occurredAt))
    && typeof value.eventId === "string" && value.eventId.length <= 256
    && PUSH_EVENTS.includes(value.event) && typeof value.deviceId === "string" && PUSH_ID.test(value.deviceId)
    && typeof value.storedSessionId === "string" && PUSH_ID.test(value.storedSessionId)
    && typeof value.accountId === "string" && PUSH_ID.test(value.accountId) && (!value.profile || /^[\p{L}\p{N}_. -]{1,64}$/u.test(value.profile));
}
function pushKey(value) { return `${value.deviceId}:${value.profile || "default"}:${value.storedSessionId}`; }
async function pushBadge(state) {
  const count = Object.keys(state.unread).length;
  try { if (count) await self.navigator.setAppBadge?.(count);else await self.navigator.clearAppBadge?.(); } catch { /* system owns permission */ }
}
self.addEventListener("message",(event) => {
  const value = event.data;
  if (!value || !["push-bind","push-read","push-foreground","clear"].includes(value.type)) return;
  const eventIds = [];
  event.waitUntil(withPushState(async (state) => {
    if (value.type === "clear" || value.type === "push-bind") {
      const channelId = value.type === "clear" ? null : value.channelId;
      if (state.channelId !== channelId || !channelId) {
        state.channelId = channelId;state.seen = [];state.unread = {};
        for (const n of await self.registration.getNotifications()) n.close();
      }
    } else if (value.type === "push-read") {
      for (const key of Object.keys(state.unread)) {
        const row = state.unread[key];
        if (row.deviceId === value.deviceId && row.storedSessionId === value.sessionId && (row.profile || "default") === (value.profile || "default")) delete state.unread[key];
      }
      for (const n of await self.registration.getNotifications()) {
        const row = n.data;
        if (row?.deviceId === value.deviceId && row?.storedSessionId === value.sessionId && (row.profile || "default") === (value.profile || "default")) n.close();
      }
    } else {
      for (const row of value.events || []) {
        if (!validPush({...row,channelId:state.channelId,accountId:value.accountId})) continue;
        if (state.seen.includes(row.eventId)) continue;
        eventIds.push(row.eventId);state.seen = [...state.seen,row.eventId].slice(-512);
        if (row.deviceId === value.currentDeviceId && row.storedSessionId === value.currentSessionId && (row.profile || "default") === (value.currentProfile || "default")) delete state.unread[pushKey(row)];
        else state.unread[pushKey(row)] = row;
      }
    }
    await pushBadge(state);
  }).then(() => event.ports?.[0]?.postMessage({ok:true,eventIds})).catch(() => event.ports?.[0]?.postMessage({ok:false})));
});
self.addEventListener("push",(event) => {
  let value;
  try { value = event.data?.json(); } catch { return; }
  if (!validPush(value)) return;
  event.waitUntil(withPushState(async (state) => {
    if (!state.channelId || state.channelId !== value.channelId || state.seen.includes(value.eventId)) return;
    // Retain per-session event time too: a delayed older event must not roll a notification back.
    const key = pushKey(value);
    const previous = state.unread[key];
    if (previous && Date.parse(previous.occurredAt) > Date.parse(value.occurredAt)) return;
    state.seen = [...state.seen,value.eventId].slice(-512);
    state.unread[key] = value;
    const bodies = value.language === "en"
      ? {"run.waiting":"A conversation needs your confirmation.","run.completed":"An answer is ready.","run.interrupted":"A task was interrupted. Open to check.","run.unknown":"A task's status is unconfirmed. Open to check."}
      : {"run.waiting":"有会话需要你确认。","run.completed":"回答已完成。","run.interrupted":"任务已中断，请打开会话检查。","run.unknown":"任务状态未确认，请打开会话检查。"};
    await self.registration.showNotification("Hermes GO",{body:bodies[value.event],tag:key,renotify:false,
      icon:"/app/icon-192.png",data:value});
    await pushBadge(state);
  }));
});
self.addEventListener("notificationclick",(event) => {
  event.notification.close();
  const value = event.notification.data;
  if (!validPush(value)) return;
  event.waitUntil(withPushState(async (state) => {
    if (state.channelId !== value.channelId) return;
    // Opening only selects a conversation. Reading is acknowledged by the visible chat later.
    const query = new URLSearchParams({push:value.channelId,account:value.accountId,device:value.deviceId,session:value.storedSessionId});
    if (value.profile) query.set("profile",value.profile);
    const url = `/app/?${query}`;
    const clients = await self.clients.matchAll({type:"window",includeUncontrolled:true});
    const client = clients.find((c) => new URL(c.url).origin === self.location.origin && new URL(c.url).pathname.startsWith("/app/"));
    if (client) { await client.navigate(url);await client.focus(); }
    else await self.clients.openWindow(url);
  }));
});
