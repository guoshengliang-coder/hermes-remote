import { createContext } from "preact";
import { useContext } from "preact/hooks";
import type { AccountDevice, GatewayClient, PublicAccount } from "../api/gateway";
import type { Language } from "../errors";
import type { SessionListItem } from "../hermes/types";
import type { Translate } from "./i18n";
import type { InboxState } from "./inbox";

// App-wide state shared through context: the Gateway client, language, the signed-in account,
// the chosen Mac, the inbox view and the live "needs you" knowledge of open chats.

export const DEVICE_STORAGE_KEY = "hermes-go.deviceId";

export interface AppContextValue {
  client: GatewayClient;
  language: Language;
  t: Translate;
  account: PublicAccount | null;
  devices: AccountDevice[];
  device: AccountDevice | null;
  chooseDevice: () => void;
  inbox: InboxState;
  needsYou: ReadonlySet<string>;
  /** A chat page reports whether its session has an open approval/clarify card. */
  reportLiveQuestion: (storedSessionId: string, report: import("./inbox").LiveQuestionReport) => void;
  markSeen: (storedSessionId: string) => void;
  /** Last loaded list rows, for titles in the chat top bar. */
  sessions: SessionListItem[];
  setSessions: (rows: SessionListItem[]) => void;
  signOut: () => Promise<void>;
  /** The socket or a request found the session revoked: go through a refresh, else sign-in. */
  authLost: () => void;
}

export const AppContext = createContext<AppContextValue | null>(null);

export function useApp(): AppContextValue {
  const value = useContext(AppContext);
  if (!value) throw new Error("AppContext missing");
  return value;
}

export function readStoredDevice(): string | null {
  try {
    return localStorage.getItem(DEVICE_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function writeStoredDevice(deviceId: string | null): void {
  try {
    if (deviceId) localStorage.setItem(DEVICE_STORAGE_KEY, deviceId);
    else localStorage.removeItem(DEVICE_STORAGE_KEY);
  } catch {
    /* private mode: remember for this page only */
  }
}

/**
 * Which Mac to use without asking: the remembered one while it is still listed, else the only
 * owned device that is online. Otherwise null (show the picker).
 */
export function autoSelectDevice(devices: readonly AccountDevice[], remembered: string | null): AccountDevice | null {
  if (remembered) {
    const kept = devices.find((d) => d.deviceId === remembered);
    if (kept) return kept;
  }
  const ownedOnline = devices.filter((d) => d.access === "owner" && d.connector?.online);
  return ownedOnline.length === 1 ? ownedOnline[0]! : null;
}
