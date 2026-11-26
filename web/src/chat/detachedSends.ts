import type { ChatSession } from "./session";

interface DetachedSend {
  session: ChatSession;
  account: string;
  device: string;
  profile: string | null;
  wasUnbound: boolean;
  settled: Promise<void>;
}

const sends = new Set<DetachedSend>();
const mounted = new Map<ChatSession, { account: string; device: string }>();

export function trackChatPage(session: ChatSession, account: string | undefined, device: string): void {
  if (account) mounted.set(session, { account, device });
}

export function closeChatPage(session: ChatSession): void {
  mounted.delete(session);
  session.dispose();
}

export function detachChatPage(session: ChatSession, account: string | undefined, device: string | undefined, profile: string | null, wasUnbound: boolean): void {
  mounted.delete(session);
  const settled = session.pendingSendCompletion;
  if (!settled || !account || !device) {
    session.dispose();
    return;
  }
  const entry = { session, account, device, profile, settled, wasUnbound };
  sends.add(entry);
  session.detachPage();
  void settled.then(() => sends.delete(entry));
}

export function waitForDetachedSend(account: string | undefined, device: string, profile: string | null, id: string | null): Promise<void> | undefined {
  return [...sends].find(entry => entry.account === account && entry.device === device &&
    (id === null ? entry.wasUnbound : entry.profile === profile && entry.session.storedSessionId === id))?.settled;
}

export function discardChatSends(account?: string, device?: string): void {
  for (const [session, owner] of mounted) {
    if (owner.account !== account || owner.device !== device) closeChatPage(session);
  }
  for (const entry of sends) {
    if (entry.account === account && entry.device === device) continue;
    sends.delete(entry);
    entry.session.dispose();
  }
}
