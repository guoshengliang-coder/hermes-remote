import { createContext } from "preact";
import { useContext } from "preact/hooks";
import type { AccountDevice, GatewayClient, PublicAccount } from "../api/gateway";
import type { AppError, Language } from "../errors";
import type { SessionListItem } from "../hermes/types";
import type { GroupId } from "./grouping";
import type { Translate } from "./i18n";
import type { InboxState } from "./inbox";

// App-wide state shared through context: the Gateway client, language, the signed-in account,
// the chosen Mac, the inbox view and the live "needs you" knowledge of open chats.

export const DEVICE_STORAGE_KEY = "hermes-go.deviceId";

/** The list narrowed to one derived project (app/projects.ts); `path: null` = sessions without a folder. */
export interface ProjectFilter {
  path: string | null;
  label: string;
}

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
  /** This browser's pins for the chosen Mac (app/pins.ts). */
  isPinned: (session: SessionListItem) => boolean;
  togglePin: (session: SessionListItem) => void;
  /** Collapsed list groups; kept while the app runs, never persisted (DESIGN §5.2). */
  collapsed: ReadonlySet<GroupId>;
  toggleGroup: (id: GroupId) => void;
  projectFilter: ProjectFilter | null;
  setProjectFilter: (filter: ProjectFilter | null) => void;
  /** "Search all chats" from inside a chat: the list opens its search with this query. */
  listSearchSeed: string | null;
  setListSearchSeed: (query: string | null) => void;
  /** A message-search hit opens its chat with in-chat search pre-filled (Android initialQuery). */
  chatSearchSeed: string | null;
  setChatSearchSeed: (query: string | null) => void;
  /** 会话 / 机器人 segment of the list, kept while the app runs. */
  listSegment: "chats" | "bots";
  setListSegment: (segment: "chats" | "bots") => void;
  /** A short confirmation ("已复制") or a failure, shown briefly at the bottom of the screen. */
  flash: (message: string | AppError) => void;
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
