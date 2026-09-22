import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "preact/hooks";
import {
  GatewayClient,
  GatewayHttpError,
  supportsWebDeviceAccess,
  type AccountDevice,
  type PublicAccount,
  type WebSignInResponse,
} from "./api/gateway";
import { toAppError } from "./app/failures";
import type { GroupId } from "./app/grouping";
import { detectLanguage, translator } from "./app/i18n";
import {
  applyLiveReport,
  deriveNeedsYou,
  initialInbox,
  noticeCount,
  reduceInbox,
  settledMark,
  undeliveredIds,
  type InboxAction,
  type LiveQuestionReport,
  type LiveSettled,
} from "./app/inbox";
import { clearAllDrafts } from "./app/drafts";
import { clearAllPins, loadPins, pinToken, savePins, togglePin } from "./app/pins";
import { currentRoute, navigate, useRoute, type Route } from "./app/router";
import {
  AppContext,
  autoSelectDevice,
  readStoredDevice,
  writeStoredDevice,
  type AppContextValue,
  type ProjectFilter,
} from "./app/store";
import { appError, display, type AppError } from "./errors";
import type { LifecycleEvent, SessionListItem } from "./hermes/types";
import { ChatPage } from "./ui/ChatPage";
import { DevicePicker } from "./ui/DevicePicker";
import { ErrorNotice } from "./ui/ErrorNotice";
import { Login } from "./ui/Login";
import { SessionList } from "./ui/SessionList";

// App shell: capability gate → web session → sign-in → Mac → routes.

type Phase =
  | { name: "boot" }
  | { name: "disabled" }
  | { name: "failed"; error: AppError }
  | { name: "signed-out"; reason: AppError | null }
  | { name: "pick-device"; error: AppError | null }
  | { name: "ready" };

const client = new GatewayClient();
const language = detectLanguage();
const t = translator(language);
document.documentElement.lang = language === "en" ? "en" : "zh-CN";

const INBOX_POLL_MS = 5000;
const BASE_TITLE = "Hermes GO";

/** Drop every Cache Storage entry and tell the service worker to do the same. */
async function clearCaches(): Promise<void> {
  try {
    if (typeof caches !== "undefined") {
      for (const key of await caches.keys()) await caches.delete(key);
    }
  } catch {
    /* no Cache Storage */
  }
  try {
    navigator.serviceWorker?.controller?.postMessage({ type: "clear" });
  } catch {
    /* no service worker */
  }
}

export function App() {
  const route = useRoute();
  const [phase, setPhase] = useState<Phase>({ name: "boot" });
  const [account, setAccount] = useState<PublicAccount | null>(null);
  const [devices, setDevices] = useState<AccountDevice[]>([]);
  const [device, setDevice] = useState<AccountDevice | null>(null);
  const [sessions, setSessions] = useState<SessionListItem[]>([]);
  const [inbox, dispatchInbox] = useReducer(reduceInbox, initialInbox);
  const [live, setLive] = useState<{ open: ReadonlySet<string>; settled: LiveSettled }>({
    open: new Set(),
    settled: new Map(),
  });
  const [toast, setToast] = useState<{ id: string; title: string; waiting: boolean } | null>(null);
  const [pinVersion, setPinVersion] = useState(0);
  const [collapsed, setCollapsed] = useState<ReadonlySet<GroupId>>(new Set());
  const [projectFilter, setProjectFilter] = useState<ProjectFilter | null>(null);
  const [listSearchSeed, setListSearchSeed] = useState<string | null>(null);
  const [flashMessage, setFlashMessage] = useState<{ id: number; text: string; error: boolean } | null>(null);
  /** Where the user was headed before sign-in / device choice (select-only, never an action). */
  const intended = useRef<Route>(currentRoute());
  const signingOut = useRef(false);

  const currentSessionId = route.name === "chat" ? route.sessionId : null;

  // ---- boot ----

  const loadDevices = useCallback(async (preferPicker = false) => {
    try {
      const { items } = await client.devices();
      setDevices(items);
      const chosen = preferPicker ? null : autoSelectDevice(items, readStoredDevice());
      if (chosen) {
        setDevice(chosen);
        writeStoredDevice(chosen.deviceId);
        setPhase({ name: "ready" });
      } else {
        setPhase({ name: "pick-device", error: null });
      }
    } catch (e) {
      setPhase({ name: "pick-device", error: toAppError(e, "account") });
    }
  }, []);

  const boot = useCallback(async () => {
    setPhase({ name: "boot" });
    try {
      const caps = await client.capabilities();
      if (!supportsWebDeviceAccess(caps)) {
        setPhase({ name: "disabled" });
        return;
      }
      const web = await client.webSession();
      if (!web.session.authenticated) {
        setPhase({ name: "signed-out", reason: null });
        return;
      }
      setAccount(web.session.account);
      await loadDevices();
    } catch (e) {
      setPhase({ name: "failed", error: toAppError(e, "account") });
    }
  }, [loadDevices]);

  useEffect(() => {
    void boot();
    return client.onSignedOut((error) => {
      if (signingOut.current) return;
      setAccount(null);
      setDevice(null);
      setSessions([]);
      setPhase({ name: "signed-out", reason: reasonFor(error) });
    });
  }, []);

  // Route guard: /app/login is only meaningful while signed out; once ready, go where intended.
  useEffect(() => {
    if (phase.name === "signed-out" && route.name !== "login") {
      intended.current = route;
      navigate({ name: "login" }, { replace: true });
    } else if (phase.name === "ready" && route.name === "login") {
      const target = intended.current.name === "login" ? { name: "list" as const } : intended.current;
      navigate(target, { replace: true });
    }
  }, [phase.name, route]);

  // ---- inbox polling (only while visible and signed in with a Mac) ----

  const inboxRef = useRef(inbox);
  inboxRef.current = inbox;
  const currentRef = useRef(currentSessionId);
  currentRef.current = currentSessionId;

  useEffect(() => {
    if (phase.name !== "ready" || !device) return;
    const deviceId = device.deviceId;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const apply = (action: InboxAction) => {
      inboxRef.current = reduceInbox(inboxRef.current, action);
      dispatchInbox(action);
    };
    const poll = async () => {
      timer = null;
      if (stopped || document.visibilityState !== "visible") return;
      try {
        for (let guard = 0; guard < 10 && !stopped; guard++) {
          const before = inboxRef.current;
          const page = await client.lifecycleEvents(before.cursor, 200);
          apply({ type: "page", events: page.events, nextCursor: page.nextCursor, deviceId, currentSessionId: currentRef.current });
          if (before.primed) announce(page.events.map((e) => e.event).filter((e) => e?.deviceId === deviceId));
          const ids = undeliveredIds(page.events);
          if (ids.length) void client.ackEvents(ids).catch(() => undefined);
          if (!page.hasMore || page.nextCursor <= before.cursor) break;
        }
        if (!inboxRef.current.primed) apply({ type: "primed" });
      } catch {
        /* the inbox is best-effort; the next tick retries */
      }
      if (!stopped) timer = setTimeout(poll, INBOX_POLL_MS);
    };
    const onVisible = () => {
      if (document.visibilityState === "visible" && !timer) void poll();
    };
    void poll();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [phase.name, device?.deviceId]);

  function announce(events: LifecycleEvent[]) {
    for (const e of events) {
      if (e.storedSessionId === currentRef.current) continue;
      if (e.event !== "run.waiting" && e.event !== "run.completed") continue;
      setToast({ id: e.storedSessionId, title: e.title || t("一个会话", "A conversation"), waiting: e.event === "run.waiting" });
    }
  }

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 8000);
    return () => clearTimeout(timer);
  }, [toast]);

  // ---- foreground notices: title + app badge ----

  const needsYou = useMemo(() => deriveNeedsYou(inbox, live.open, live.settled), [inbox, live]);
  const count = noticeCount(inbox, currentSessionId);
  useEffect(() => {
    document.title = count > 0 ? `(${count}) ${BASE_TITLE}` : BASE_TITLE;
    const nav = navigator as Navigator & { setAppBadge?: (n?: number) => Promise<void>; clearAppBadge?: () => Promise<void> };
    try {
      if (count > 0) void nav.setAppBadge?.(count)?.catch(() => undefined);
      else void nav.clearAppBadge?.()?.catch(() => undefined);
    } catch {
      /* unsupported */
    }
  }, [count]);

  // ---- actions ----

  const signOut = useCallback(async () => {
    signingOut.current = true;
    try {
      // Sign-out is a CSRF-checked mutation: make sure the access cookie is fresh first.
      const expires = client.accessExpiresAt;
      if (expires === null || expires - Date.now() < 60_000) {
        await client.refresh().catch(() => undefined);
      }
      await client.signOut().catch(() => undefined);
    } finally {
      await clearCaches();
      clearAllPins();
      clearAllDrafts();
      setCollapsed(new Set());
      setProjectFilter(null);
      writeStoredDevice(null);
      setAccount(null);
      setDevice(null);
      setDevices([]);
      setSessions([]);
      intended.current = { name: "list" };
      setPhase({ name: "signed-out", reason: null });
      signingOut.current = false;
    }
  }, []);

  const authLost = useCallback(() => {
    // The signed-out listener moves to the login page when the refresh is refused.
    client.refresh().catch(() => undefined);
  }, []);

  // A chat that saw its question settle knows better than the inbox entry it has already outlived.
  const reportLiveQuestion = useCallback((id: string, report: LiveQuestionReport) => {
    setLive((prev) => applyLiveReport(prev, id, report, settledMark(inboxRef.current, id)));
  }, []);

  const markSeen = useCallback((id: string) => dispatchInbox({ type: "seen", storedSessionId: id }), []);

  function onSignedIn(result: WebSignInResponse) {
    setAccount(result.account);
    void loadDevices();
  }

  function selectDevice(chosen: AccountDevice) {
    writeStoredDevice(chosen.deviceId);
    setDevice(chosen);
    setSessions([]);
    setProjectFilter(null);
    setPhase({ name: "ready" });
  }

  // Pins are read synchronously for the chosen Mac, so the list never paints a "no pins" frame first
  // and then jumps (DESIGN §5.2, HG-11).
  const deviceId = device?.deviceId ?? null;
  const pins = useMemo(() => (deviceId ? loadPins(deviceId) : new Set<string>()), [deviceId, pinVersion]);
  const isPinned = useCallback((session: SessionListItem) => pins.has(pinToken(session)), [pins]);
  const togglePinFor = useCallback(
    (session: SessionListItem) => {
      if (!deviceId) return;
      savePins(deviceId, togglePin(loadPins(deviceId), pinToken(session)));
      setPinVersion((v) => v + 1);
    },
    [deviceId],
  );
  const toggleGroup = useCallback((id: GroupId) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);
  const flashSeq = useRef(0);
  const flash = useCallback((message: string | AppError) => {
    const id = ++flashSeq.current;
    setFlashMessage(typeof message === "string" ? { id, text: message, error: false } : { id, text: display(message, language), error: true });
  }, []);
  useEffect(() => {
    if (!flashMessage) return;
    const timer = setTimeout(() => setFlashMessage((m) => (m?.id === flashMessage.id ? null : m)), flashMessage.error ? 4000 : 1600);
    return () => clearTimeout(timer);
  }, [flashMessage]);

  const value: AppContextValue = {
    client,
    language,
    t,
    account,
    devices,
    device,
    chooseDevice: () => void loadDevices(true),
    inbox,
    needsYou,
    reportLiveQuestion,
    markSeen,
    sessions,
    setSessions,
    isPinned,
    togglePin: togglePinFor,
    collapsed,
    toggleGroup,
    projectFilter,
    setProjectFilter,
    listSearchSeed,
    setListSearchSeed,
    flash,
    signOut,
    authLost,
  };

  return <AppContext.Provider value={value}>{renderPhase()}</AppContext.Provider>;

  function renderPhase() {
    switch (phase.name) {
      case "boot":
        return (
          <div class="full-center">
            <span class="spinner" aria-label={t("正在加载", "Loading")} />
          </div>
        );
      case "disabled":
        return (
          <div class="full-center notice-page">
            <h1 class="notice-title">Hermes GO</h1>
            <p class="notice-body">
              {t("这个 Relay 还没有开启网页版。请使用 Hermes GO Android 应用。", "The web app is not enabled on this Relay yet. Use the Hermes GO Android app.")}
            </p>
            <ErrorNotice error={appError("HR-WEB-001", "capabilities: accountAuth.webDeviceAccess is not enabled")} language={language} />
          </div>
        );
      case "failed":
        return (
          <div class="full-center notice-page">
            <h1 class="notice-title">Hermes GO</h1>
            <ErrorNotice error={phase.error} language={language} onRetry={() => void boot()} />
          </div>
        );
      case "signed-out":
        return <Login client={client} language={language} t={t} reason={phase.reason} onSignedIn={onSignedIn} />;
      case "pick-device":
        return (
          <DevicePicker
            devices={devices}
            selectedId={device?.deviceId ?? readStoredDevice()}
            language={language}
            t={t}
            error={phase.error}
            onRetry={() => void loadDevices(true)}
            onSelect={selectDevice}
            onSignOut={() => void signOut()}
          />
        );
      case "ready":
        return (
          <>
            {toast ? (
              <button
                type="button"
                class="toast"
                onClick={() => {
                  setToast(null);
                  navigate({ name: "chat", sessionId: toast.id });
                }}
              >
                <span class={`dot ${toast.waiting ? "dot-warn" : "dot-good"}`} aria-hidden="true" />
                <span class="toast-text">
                  {toast.waiting ? t(`「${toast.title}」需要你处理`, `"${toast.title}" needs you`) : t(`「${toast.title}」已完成`, `"${toast.title}" finished`)}
                </span>
              </button>
            ) : null}
            {flashMessage ? (
              <div class={`flash${flashMessage.error ? " error" : ""}`} role="status" key={flashMessage.id}>
                {flashMessage.text}
              </div>
            ) : null}
            {route.name === "chat" || route.name === "new" ? (
              <ChatPage sessionId={route.name === "chat" ? route.sessionId : null} />
            ) : (
              <SessionList />
            )}
          </>
        );
    }
  }
}

/** Why the session ended, for the sign-in banner (DESIGN §5.19). */
function reasonFor(error: GatewayHttpError): AppError {
  const code = error.code;
  if (code === "HR-AUTH-004" || code === "HR-AUTH-005") return appError(code, `refresh ${error.status}`);
  return appError("HR-AUTH-003", `refresh ${error.status}${code ? ` ${code}` : ""}`);
}
