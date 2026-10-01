import type { GatewayClient } from "../api/gateway";

export const PUSH_PATH = "/v2/web/push-subscription";
export interface PushConfig {
  publicKey: string;
  registration: { channelId: string } | null;
}
export interface PushTarget {
  channelId: string;
  accountId: string;
  deviceId: string;
  sessionId: string;
  profile: string | null;
}
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;
const UUID = /^[a-f0-9-]{36}$/i;
const PENDING = "hermes-go.push-target";

export function pushEnvironment(): "supported" | "install" | "unsupported" {
  const ios =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const standalone =
    matchMedia("(display-mode: standalone)").matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone;
  if (ios && !standalone) return "install";
  return window.isSecureContext &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window
    ? "supported"
    : "unsupported";
}

export function readPushTarget(search: string): PushTarget | null {
  const q = new URLSearchParams(search);
  const channelId = q.get("push");
  const accountId = q.get("account");
  const deviceId = q.get("device");
  const sessionId = q.get("session");
  const profile = q.get("profile");
  if (
    !accountId ||
    !ID.test(accountId) ||
    !channelId ||
    !UUID.test(channelId) ||
    !deviceId ||
    !ID.test(deviceId) ||
    !sessionId ||
    !ID.test(sessionId) ||
    (profile !== null && !/^[\p{L}\p{N}_. -]{1,64}$/u.test(profile))
  )
    return null;
  return { channelId, accountId, deviceId, sessionId, profile };
}
export function rememberPushTarget(): void {
  const target = readPushTarget(location.search);
  try {
    if (target) sessionStorage.setItem(PENDING, JSON.stringify(target));
  } catch {
    /* private storage unavailable */
  }
}
export function pendingPushTarget(): PushTarget | null {
  try {
    const raw = JSON.parse(
      sessionStorage.getItem(PENDING) ?? "null",
    ) as PushTarget | null;
    if (!raw) return null;
    return readPushTarget(
      new URLSearchParams({
        push: raw.channelId,
        account: raw.accountId,
        device: raw.deviceId,
        session: raw.sessionId,
        ...(raw.profile ? { profile: raw.profile } : {}),
      }).toString(),
    );
  } catch {
    return null;
  }
}
export function clearPushTarget(): void {
  try {
    sessionStorage.removeItem(PENDING);
  } catch {
    /* private storage */
  }
}

export async function pushWorker(): Promise<ServiceWorkerRegistration> {
  const existing = await navigator.serviceWorker.getRegistration("/app/");
  const registration =
    existing ??
    (await navigator.serviceWorker.register("/app/sw.js", { scope: "/app/" }));
  if (registration.active) return registration;
  return Promise.race([
    navigator.serviceWorker.ready,
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error("worker not ready")), 5000),
    ),
  ]);
}
let pushEpoch = 0;
export function announcePushState(enabled: boolean): void {
  window.dispatchEvent(
    new CustomEvent("hermes-push-state", { detail: enabled }),
  );
}
export const currentPushEpoch = () => pushEpoch;
export async function resetPushWorker(): Promise<void> {
  pushEpoch++;
  announcePushState(false);
  await messagePushWorker({ type: "push-bind", channelId: null });
}
export async function messagePushWorker(
  message: unknown,
  epoch = pushEpoch,
): Promise<{ eventIds?: string[] }> {
  if (!navigator.serviceWorker) return {};
  const registration = await pushWorker();
  return new Promise<{ eventIds?: string[] }>((resolve, reject) => {
    const channel = new MessageChannel();
    const timeout = setTimeout(() => {
      channel.port1.close();
      reject(new Error("worker message timeout"));
    }, 5000);
    channel.port1.onmessage = (event) => {
      clearTimeout(timeout);
      channel.port1.close();
      if (event.data?.ok) resolve(event.data);
      else reject(new Error("push state write failed"));
    };
    if (epoch !== pushEpoch) {
      clearTimeout(timeout);
      channel.port1.close();
      resolve({});
      return;
    }
    registration.active?.postMessage(message, [channel.port2]);
  });
}
export async function bindPushWorker(
  client: GatewayClient,
): Promise<PushConfig> {
  const epoch = ++pushEpoch;
  const config = await client.request<PushConfig>("GET", PUSH_PATH);
  await messagePushWorker(
    { type: "push-bind", channelId: config.registration?.channelId ?? null },
    epoch,
  );
  if (epoch !== pushEpoch) return { ...config, registration: null };
  announcePushState(Boolean(config.registration));
  return config;
}
export async function disablePush(client: GatewayClient): Promise<void> {
  // Clear local channel first: queued notifications cannot leak even if DELETE fails offline.
  await resetPushWorker();
  const registration = await pushWorker();
  await (await registration.pushManager.getSubscription())?.unsubscribe();
  await client.request("DELETE", PUSH_PATH);
}
