import { Fragment } from "preact";
import { BackClose } from "../app/useBackClose";
import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from "preact/hooks";
import { botNoticeSeen, botOriginLabel, botSendNoticeBody, botSendNoticeTitle, markBotNoticeSeen } from "../app/bots";
import { draftKey } from "../app/drafts";
import { rememberDefaultProject } from "../app/localPrefs";
import { basename } from "../app/projects";
import { explicitProfile } from "../app/profile";
import { isBotSession } from "../app/sources";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import type { PendingAttachment } from "../chat/attachments";
import { hasOpenQuestion, initialChatState, reduceChat, type ChatItem } from "../chat/model";
import { ChatSession, type BackgroundProcess } from "../chat/session";
import { followAfterScroll } from "../chat/followBottom";
import { pillGroup, turnGroups, TURN_PILL_IDLE_HIDE_MS, TURN_PILL_LIST_MIN_GROUPS, type TurnGroup } from "../chat/turns";
import { formatTimeSeparator, greetingForHour, showsTimeSeparator } from "../chat/transcript";
import { appError } from "../errors";
import type { AnswerPlan } from "../hermes/requests";
import { Composer } from "./Composer";
import { ErrorNotice } from "./ErrorNotice";
import { Sheet, SheetAction } from "./Sheet";
import { SessionActionSheet } from "./SessionActions";
import { ModelSheet, modelChipLabel } from "./ModelSheet";
import { copyWithFeedback } from "./Markdown";
import { speechSupported, toggleSpeak } from "../chat/speech";
import { readableText } from "../markdown/render";
import { ChatSearchBar, PromptsSheet, searchHits, ShareSheet, SourceDialog, useSearchHighlights, useShareTranscript, UserMenuSheet } from "./ChatSheets";
import {
  ArchiveIcon,
  ArrowDownIcon,
  ArrowUpIcon,
  BackIcon,
  ChevronDownIcon,
  ChevronIcon,
  TerminalIcon,
  BranchIcon,
  FolderIcon,
  ListIcon,
  MoreIcon,
  PinOutlineIcon,
  PlusIcon,
  RefreshIcon,
  SearchIcon,
  ShareIcon,
} from "./icons";
import { ImageEditor } from "./ImageEditor";
import { ImageViewer, type ViewerImage } from "./ImageViewer";
import { MessageView, type MessageActions } from "./Message";
import { QuestionSheet } from "./QuestionSheet";
import { loadSessions } from "./SessionList";

// Chat page for /app/s/<storedSessionId> and /app/new. A new chat keeps the same page (and
// socket) when its first send gives it a durable id and the URL is replaced.

let localSeq = 0;

/** Within this distance of the top the next older history page is fetched (HG-104). */
const OLDER_TRIGGER_PX = 320;

/** The first turn on screen and where it sits: kept in place when content above it changes. */
interface ScrollAnchor {
  key: string;
  offset: number;
}

function readAnchor(el: HTMLElement): ScrollAnchor | null {
  const top = el.getBoundingClientRect().top;
  for (const node of el.querySelectorAll<HTMLElement>(".turn[data-key]")) {
    const box = node.getBoundingClientRect();
    if (box.bottom > top) return { key: node.dataset.key ?? "", offset: box.top - top };
  }
  return null;
}

function restoreAnchor(el: HTMLElement, anchor: ScrollAnchor | null) {
  if (!anchor?.key) return;
  const node = el.querySelector<HTMLElement>(`.turn[data-key="${CSS.escape(anchor.key)}"]`);
  if (!node) return;
  const delta = node.getBoundingClientRect().top - el.getBoundingClientRect().top - anchor.offset;
  if (Math.abs(delta) >= 1) el.scrollTop += delta;
}

export function ChatPage({ sessionId }: { sessionId: string | null }) {
  const app = useApp();
  const { t, language, device, client } = app;
  const [state, dispatch] = useReducer(reduceChat, initialChatState);
  const stateRef = useRef(state);
  stateRef.current = state;
  const anchor = useRef<ScrollAnchor | null>(null);
  const sessionRef = useRef<ChatSession | null>(null);
  const [storedId, setStoredId] = useState<string | null>(sessionId);
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const pendingFiles = useRef(new Map<string, PendingAttachment[]>());
  const [viewer, setViewer] = useState<{ images: ViewerImage[]; index: number } | null>(null);
  // A pending image opened from the composer strip: preview first, 编辑 leads to the editor.
  const [pendingImage, setPendingImage] = useState<{ attachment: PendingAttachment; replace: (next: PendingAttachment) => void; remove: () => void; editing: boolean } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchFocus, setSearchFocus] = useState(0);
  const [promptsOpen, setPromptsOpen] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [userMenu, setUserMenu] = useState<ChatItem | null>(null);
  const [sourceItem, setSourceItem] = useState<ChatItem | null>(null);
  const [seed, setSeed] = useState<{ text: string; nonce: number } | null>(null);
  const [botNotice, setBotNotice] = useState<{ text: string; attachments: PendingAttachment[] } | null>(null);
  // Web batch 4
  const [manage, setManage] = useState<"archive" | "move" | null>(null);
  const [modelOpen, setModelOpen] = useState(false);
  const [regenerateAfterSwitch, setRegenerateAfterSwitch] = useState(false);
  const [chosenModel, setChosenModel] = useState<{ model: string; provider: string } | null>(null);
  const [explicitModelOverride, setExplicitModelOverride] = useState(false);
  const [reasoning, setReasoning] = useState<string | null>(null);
  const [answerMenu, setAnswerMenu] = useState<ChatItem | null>(null);
  const [processes, setProcesses] = useState<BackgroundProcess[]>([]);
  const [processesOpen, setProcessesOpen] = useState(false);
  const [ownedElsewhere, setOwnedElsewhere] = useState(false);

  // Opened from a message-search hit: in-chat search starts pre-filled (Android initialQuery).
  useEffect(() => {
    if (!app.chatSearchSeed) return;
    setSearchQuery(app.chatSearchSeed);
    setSearchOpen(true);
    app.setChatSearchSeed(null);
  }, []);
  // A new chat opened from a project-filtered list is created in that project's folder; captured
  // once so changing the filter later cannot move a chat that is being created.
  const newChatProject = useRef(sessionId === null ? app.projectFilter : null);

  // Opened straight from a URL before the list loaded: fetch it first, because the session's
  // profile decides where resume and history look.
  const [listReady, setListReady] = useState(sessionId === null || app.sessions.length > 0);
  useEffect(() => {
    if (listReady || !device) return;
    let live = true;
    loadSessions(client, device.deviceId)
      .then((rows) => live && app.setSessions(rows), () => undefined)
      .finally(() => live && setListReady(true));
    return () => {
      live = false;
    };
  }, [listReady, device?.deviceId]);

  // One ChatSession per conversation; adopting the id a new chat just got keeps the socket.
  useEffect(() => {
    if (!device || !listReady) return;
    const current = sessionRef.current;
    if (current && sessionId !== null && current.storedSessionId === sessionId) return;
    current?.dispose();
    dispatch({ type: "reset" });
    setChosenModel(null);
    setExplicitModelOverride(false);
    setReasoning(null);
    newChatProject.current = sessionId === null ? app.projectFilter : null;
    const session = new ChatSession({
      client,
      deviceId: device.deviceId,
      storedSessionId: sessionId,
      cwd: sessionId === null ? newChatProject.current?.path : null,
      profile: sessionId === null ? null : explicitProfile(app.sessions.find((s) => s.id === sessionId)),
      dispatch,
      onStored: (id) => {
        setStoredId(id);
        navigate({ name: "chat", sessionId: id }, { replace: true });
      },
      onAuthLost: app.authLost,
      // A top-level create lands in Hermes' launch folder: that is the default project (Android
      // saves the same `info.cwd` as defaultProjectPath).
      onCreated: ({ cwd, requestedCwd }) => {
        if (!requestedCwd) rememberDefaultProject(device.deviceId, cwd);
      },
    });
    sessionRef.current = session;
    setStoredId(sessionId);
    session.start();
  }, [sessionId, device?.deviceId, listReady]);

  useEffect(() => () => {
    sessionRef.current?.dispose();
    sessionRef.current = null;
  }, []);

  useEffect(() => {
    if (storedId) app.markSeen(storedId);
  }, [storedId, app.inbox.unseen]);

  // Opened straight from a URL (or a just-created chat): fetch the list once for the title.
  const titleFetched = useRef<string | null>(null);
  useEffect(() => {
    if (!device || !storedId || titleFetched.current === storedId) return;
    if (app.sessions.some((s) => s.id === storedId)) return;
    titleFetched.current = storedId;
    const timer = setTimeout(() => {
      loadSessions(client, device.deviceId).then(app.setSessions, () => undefined);
    }, state.generating ? 4000 : 0);
    return () => clearTimeout(timer);
  }, [storedId, device?.deviceId]);

  // Only a card this page saw close counts as settled; a page that opens with no card yet (before
  // the resume replays open requests) or that is left with one open reports nothing of the kind.
  const open = hasOpenQuestion(state);
  const wasOpen = useRef(false);
  useEffect(() => {
    if (!storedId) return;
    if (open) app.reportLiveQuestion(storedId, "open");
    else if (wasOpen.current) app.reportLiveQuestion(storedId, "settled");
    wasOpen.current = open;
  }, [storedId, open]);
  useEffect(() => () => {
    if (storedId) app.reportLiveQuestion(storedId, "left");
    wasOpen.current = false;
  }, [storedId]);

  // Follow the stream while the reader is at the bottom; otherwise keep the turn they are reading
  // where it was when an older page (or its loader) appears above it.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    if (stick.current) pinToBottom(el);
    else restoreAnchor(el, anchor.current);
    anchor.current = readAnchor(el);
  }, [state.items, open, state.older.loading, state.older.error]);

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    const decision = followAfterScroll({
      distanceFromBottom: el.scrollHeight - el.scrollTop - el.clientHeight,
      following: stick.current,
      now: performance.now(),
      userAt: userAt.current,
      searching: searchOpenRef.current,
      contentGrew: el.scrollHeight !== pinnedHeight.current,
    });
    if (decision === "follow") stick.current = true;
    else if (decision === "release") stick.current = false;
    // Nobody touched the list: content grew under a scroll we set ourselves. Stay pinned.
    else if (decision === "repin") pinToBottom(el);
    if (stick.current !== atBottom) setAtBottom(stick.current);
    anchor.current = readAnchor(el);
    updatePill(el);
    maybeLoadOlder(el);
  }

  /** The last time the reader touched, wheeled or keyed the message list. */
  // -Infinity, not 0: performance.now() counts from page load, so 0 reads as "just now" for the
  // first second after a load — exactly when the conversation is being pinned to its bottom.
  const userAt = useRef(Number.NEGATIVE_INFINITY);
  const markUser = () => {
    userAt.current = performance.now();
  };
  /** scrollHeight at our last pin to the bottom: a different height means content settled since. */
  const pinnedHeight = useRef(-1);
  const pinToBottom = (el: HTMLElement) => {
    el.scrollTop = el.scrollHeight;
    pinnedHeight.current = el.scrollHeight;
  };
  const searchOpenRef = useRef(false);
  searchOpenRef.current = searchOpen;

  // Keep the newest turn in view while its content settles: Markdown, code highlighting, tables and
  // images change a turn's height after the render that scrolled to the bottom. Only while
  // following the bottom, and never during in-chat search (its hits scroll the list themselves).
  useEffect(() => {
    const el = scroller.current;
    const inner = el?.querySelector(".messages-inner");
    if (!el || !inner || typeof ResizeObserver !== "function") return;
    const observer = new ResizeObserver(() => {
      if (stick.current && !searchOpenRef.current) pinToBottom(el);
    });
    observer.observe(inner);
    return () => observer.disconnect();
  }, []);

  /** Earlier messages, fetched page by page as the reader reaches the top (HG-104). */
  function loadOlder() {
    const s = stateRef.current;
    void sessionRef.current?.loadOlder({ rows: s.historyRows, epoch: s.historyEpoch });
  }

  function maybeLoadOlder(el: HTMLElement) {
    const s = stateRef.current;
    if (!s.historyLoaded || !s.older.hasMore || s.older.loading || s.older.error) return;
    if (el.scrollTop > OLDER_TRIGGER_PX) return;
    loadOlder();
  }

  // A page too short to scroll (or still near the top after a prepend) keeps reaching back.
  useEffect(() => {
    const el = scroller.current;
    if (el) maybeLoadOlder(el);
  }, [state.historyRows, state.older.hasMore, state.older.loading]);

  // "Back to this prompt" pill (DESIGN §5.4, Android TurnJump): shown while the list moves, gone
  // 1.5 s after it stops; hidden when the group's own prompt is on screen or the reader is at the
  // bottom following the stream.
  const [pill, setPill] = useState<TurnGroup | null>(null);
  const pillTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  function updatePill(el: HTMLDivElement) {
    const groups = turnGroups(state.items);
    const tops = new Map<string, { top: number; bottom: number }>();
    const base = el.getBoundingClientRect().top - el.scrollTop;
    for (const g of groups) {
      if (!g.key) continue;
      const node = el.querySelector<HTMLElement>(`.turn[data-key="${CSS.escape(g.key)}"]`);
      if (!node) continue;
      const box = node.getBoundingClientRect();
      tops.set(g.key, { top: box.top - base, bottom: box.bottom - base });
    }
    const group = stick.current || searchOpen ? null : pillGroup(groups, tops, el.scrollTop);
    setPill(group);
    if (pillTimer.current) clearTimeout(pillTimer.current);
    if (group) pillTimer.current = setTimeout(() => setPill(null), TURN_PILL_IDLE_HIDE_MS);
  }
  useEffect(() => () => {
    if (pillTimer.current) clearTimeout(pillTimer.current);
  }, []);
  // Rotation, a foldable opening or the keyboard re-flow the list without a scroll event: a pill
  // that is showing is re-measured so it names the group now at the top (it never pops up anew).
  const pillShown = useRef(false);
  pillShown.current = pill !== null;
  const remeasure = useRef(() => {});
  remeasure.current = () => {
    if (pillShown.current && scroller.current) updatePill(scroller.current);
  };
  useEffect(() => {
    const el = scroller.current;
    if (!el || typeof ResizeObserver !== "function") return;
    const observer = new ResizeObserver(() => remeasure.current());
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  function send(text: string, attachments: PendingAttachment[], confirmed = false) {
    const session = sessionRef.current;
    if (!session) return;
    // First message into a bot conversation: say once, per channel, that it stays in Hermes (§5.16).
    const bot = storedId ? app.sessions.find((s) => s.id === storedId) : undefined;
    if (!confirmed && bot && isBotSession(bot) && !botNoticeSeen(bot.source!)) {
      setBotNotice({ text, attachments });
      return;
    }
    const key = `l-${++localSeq}`;
    pendingFiles.current.set(key, attachments);
    stick.current = true;
    dispatch({
      type: "user-sent",
      key,
      text,
      localImages: attachments.flatMap((a) => (a.previewUrl ? [a.previewUrl] : [])),
      localFiles: attachments.filter((a) => a.kind === "file").map((a) => a.name),
      nowMs: Date.now(),
    });
    void session.send(key, text, attachments);
  }

  function retry(item: ChatItem) {
    const session = sessionRef.current;
    if (!session) return;
    dispatch({ type: "user-retry", key: item.key });
    void session.send(item.key, item.text, pendingFiles.current.get(item.key) ?? []);
  }

  function answer(plan: AnswerPlan) {
    void sessionRef.current?.answer(plan);
  }

  /** Cancelled: the typed text goes back into the composer instead of being lost. */
  function cancelBotNotice() {
    if (botNotice?.text) setSeed({ text: botNotice.text, nonce: Date.now() });
    setBotNotice(null);
  }

  function regenerate() {
    const last = [...state.items].reverse().find((i) => i.role === "user" && !i.note && i.text.trim());
    if (last) send(last.text, []);
  }

  async function refresh() {
    const session = sessionRef.current;
    if (!session || refreshing) return;
    setRefreshing(true);
    session.reconnectNow();
    const ok = storedId ? await session.loadHistory({ quiet: true }) : true;
    setRefreshing(false);
    app.flash(ok ? t("当前对话已刷新", "Conversation refreshed") : appError("HR-SYNC-001", "chat refresh: history reload failed; the transcript on screen was kept"));
  }

  function jumpTo(key: string) {
    const el = scroller.current?.querySelector<HTMLElement>(`.turn[data-key="${CSS.escape(key)}"]`);
    if (!el) return;
    stick.current = false;
    el.scrollIntoView({ block: "start" });
    // The outline goes on the prompt bubble itself (1.5px brand colour, then a fade).
    const target = el.querySelector<HTMLElement>(".bubble") ?? el;
    target.classList.remove("landing");
    void target.offsetWidth;
    target.classList.add("landing");
  }

  function toLatest() {
    const el = scroller.current;
    if (!el) return;
    stick.current = true;
    pinToBottom(el);
    setAtBottom(true);
  }

  // Who owns this conversation (session.access, managed patch 030): checked on every (re)connect.
  // Owned elsewhere replaces the composer (HR-SESS-013); an older Hermes fails open (Android HG-66).
  useEffect(() => {
    if (!app.features.has("session-access") || !storedId || state.connection !== "ready") return;
    let live = true;
    void sessionRef.current?.access().then((a) => live && setOwnedElsewhere(a === "owned_elsewhere"));
    return () => {
      live = false;
    };
  }, [state.connection, storedId, app.features]);

  // Background processes (process.list): polled while a run is live or a process still runs.
  useEffect(() => {
    if (!app.features.has("process-list") || state.connection !== "ready") return;
    if (!state.generating && !processes.some((p) => p.running)) return;
    let live = true;
    const poll = () =>
      sessionRef.current?.processes().then(
        (list) => live && setProcesses(list),
        () => undefined,
      );
    void poll();
    const timer = setInterval(() => void poll(), 4000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [state.generating, state.connection, processes.some((p) => p.running), app.features]);

  // Reasoning effort, read once per conversation for the composer chip.
  useEffect(() => {
    if (!app.features.has("model-select") || !storedId || state.connection !== "ready") return;
    let live = true;
    sessionRef.current?.reasoning().then((v) => live && setReasoning(v), () => undefined);
    return () => {
      live = false;
    };
  }, [storedId, state.connection === "ready", app.features]);

  const row = storedId ? app.sessions.find((s) => s.id === storedId) : undefined;
  const title = row?.title || (storedId && app.inbox.latest[storedId]?.title) || (storedId ? t("会话", "Conversation") : t("新会话", "New chat"));
  const listRow = storedId ? (row ?? { id: storedId }) : null;
  const pinned = listRow ? app.isPinned(listRow) : false;
  const emptyNew = sessionId === null && state.items.length === 0;

  // `[folder] project · [branch] branch` (DESIGN §5.4): the live workspace from session.info, else
  // the list row. Display only on the Web — moving a chat is not in the browser allowlist.
  const workspacePath = state.workspace?.cwd ?? row?.git_repo_root ?? row?.cwd ?? null;
  const workspaceBranch = state.workspace ? state.workspace.branch : (row?.git_branch ?? null);
  const workspaceLabel = workspacePath ? basename(workspacePath.replace(/[/\\]+$/, "")) : null;
  const botRow = row && isBotSession(row) ? row : null;
  const canMove = app.features.has("workspace-move") && !botRow && Boolean(storedId);
  const currentModel = chosenModel ?? { model: state.liveModel ?? row?.model ?? null, provider: row?.provider ?? null };
  const runningProcesses = processes.filter((p) => p.running);

  const connectionLine =
    state.connection === "reconnecting"
      ? t("连接中断，正在恢复…", "Reconnecting…")
      : state.connection === "offline"
        ? t("网络不可用", "Offline")
        : state.connection === "connecting" && state.historyLoaded
          ? t("正在连接…", "Connecting…")
          : "";

  const shareTranscript = useShareTranscript(shareOpen, state, storedId ? () => sessionRef.current?.loadFullHistory() ?? Promise.resolve([]) : null);

  const hits = useMemo(() => (searchOpen ? searchHits(state.items, searchQuery) : []), [searchOpen, searchQuery, state.items]);
  useEffect(() => setSearchFocus(hits.length ? hits.length - 1 : 0), [searchQuery]);
  useSearchHighlights(searchOpen ? scroller.current : null, searchQuery, hits, Math.min(searchFocus, Math.max(0, hits.length - 1)), state.items);

  const lastAssistantKey = [...state.items].reverse().find((i) => i.role === "assistant" && !i.note)?.key ?? null;
  const actionsFor = (item: ChatItem): MessageActions => ({
    onRetry: retry,
    onOpenImage: (images, index) => setViewer({ images, index }),
    onUserMenu: (it) => setUserMenu(it),
    onViewSource: (it) => setAnswerMenu(it),
    ...(item.key === lastAssistantKey && !state.generating && !state.terminal ? { onRegenerate: regenerate } : {}),
  });

  let previousMs: number | null = null;

  return (
    <div class="page chat-page">
      <header class="topbar">
        {searchOpen ? <BackClose onClose={() => { setSearchOpen(false); setSearchQuery(""); }} /> : null}
        {searchOpen ? (
          <ChatSearchBar
            query={searchQuery}
            onQuery={setSearchQuery}
            hits={hits.length}
            focus={Math.min(searchFocus, Math.max(0, hits.length - 1))}
            onFocus={setSearchFocus}
            onClose={() => {
              setSearchOpen(false);
              setSearchQuery("");
            }}
            onSearchAll={() => {
              app.setListSearchSeed(searchQuery.trim());
              navigate({ name: "list" });
            }}
          />
        ) : (
          <div class="topbar-row">
            <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={() => navigate({ name: "list" })}>
              <BackIcon />
            </button>
            <div class="chat-title">
              <h1 class={`topbar-title left${title.length > 24 ? " long" : ""}`}>{emptyNew ? t("新会话", "New chat") : title}</h1>
              {connectionLine ? (
                <span class="chat-status">{connectionLine}</span>
              ) : botRow ? (
                <span class="chat-workspace">{botOriginLabel(botRow, language)}</span>
              ) : workspaceLabel && !emptyNew ? (
                // The subtitle is also the way to move the chat (DESIGN §5.4): muted, without the
                // chevron, while a run is live (Hermes refuses a move then, 4009).
                <button
                  type="button"
                  class={`chat-workspace mono${canMove ? " movable" : ""}`}
                  disabled={!canMove || state.generating}
                  aria-label={canMove ? t("所属项目，点按移动", "Project — tap to move") : undefined}
                  onClick={() => setManage("move")}
                >
                  <FolderIcon size={12} />
                  <span class="chat-workspace-name">{workspaceLabel}</span>
                  {workspaceBranch ? (
                    <>
                      <span aria-hidden="true">·</span>
                      <BranchIcon size={12} />
                      <span class="chat-workspace-name">{workspaceBranch}</span>
                    </>
                  ) : null}
                  {canMove && !state.generating ? <ChevronDownIcon size={12} /> : null}
                </button>
              ) : null}
            </div>
            {emptyNew ? (
              <span class="topbar-spacer" />
            ) : (
              <>
                <button type="button" class="icon-button" aria-label={t("新会话", "New chat")} onClick={() => navigate({ name: "new" })}>
                  <PlusIcon />
                </button>
                <button type="button" class="icon-button" aria-label={t("更多", "More")} aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
                  <MoreIcon />
                </button>
              </>
            )}
          </div>
        )}
        {menuOpen && !searchOpen ? (
          <>
            <BackClose onClose={() => setMenuOpen(false)} />
            <div class="menu-scrim" onClick={() => setMenuOpen(false)} />
            <div class="menu" role="menu">
              <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); setSearchOpen(true); }}>
                <SearchIcon size={18} />
                {t("搜索对话", "Search this chat")}
              </button>
              <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); setPromptsOpen(true); }}>
                <ListIcon size={18} />
                {t("我的提问", "Your prompts")}
              </button>
              <button type="button" role="menuitem" class="menu-item with-icon" disabled={refreshing} onClick={() => { setMenuOpen(false); void refresh(); }}>
                <RefreshIcon size={18} />
                {t("刷新对话", "Refresh conversation")}
              </button>
              <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); setShareOpen(true); }}>
                <ShareIcon size={18} />
                {t("分享对话", "Share transcript")}
              </button>
              {listRow ? (
                <button
                  type="button"
                  role="menuitem"
                  class="menu-item with-icon"
                  onClick={() => {
                    setMenuOpen(false);
                    app.togglePin(listRow);
                    app.flash(pinned ? t("已取消置顶", "Unpinned") : t("已置顶（仅此设备）", "Pinned on this device"));
                  }}
                >
                  <PinOutlineIcon size={18} />
                  {pinned ? t("取消置顶", "Unpin") : t("置顶", "Pin")}
                </button>
              ) : null}
              {listRow && app.features.has("session-manage") ? (
                <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); setManage("archive"); }}>
                  <ArchiveIcon size={18} />
                  {t("归档对话", "Archive conversation")}
                </button>
              ) : null}
            </div>
          </>
        ) : null}
        {refreshing ? <div class="top-progress" aria-hidden="true" /> : null}
      </header>
      <div class="messages" ref={scroller} onScroll={onScroll} onPointerDown={markUser} onTouchStart={markUser} onWheel={markUser} onKeyDown={markUser}>
        {pill ? (
          <div class="turn-pill-slot">
            <div class="turn-pill">
              <button
                type="button"
                class="turn-pill-main"
                aria-label={t(`回到这条提问：${pill.summary.zh}`, `Back to this prompt: ${pill.summary.en}`)}
                onClick={() => {
                  setPill(null);
                  if (pill.key) jumpTo(pill.key);
                  else if (scroller.current) {
                    stick.current = false;
                    scroller.current.scrollTop = 0;
                  }
                }}
                onContextMenu={(e) => {
                  e.preventDefault();
                  setPill(null);
                  setPromptsOpen(true);
                }}
              >
                <span class="turn-pill-chip" aria-hidden="true">
                  <ArrowUpIcon size={14} />
                </span>
                <span class="turn-pill-label">{language === "en" ? pill.summary.en : pill.summary.zh}</span>
              </button>
              {turnGroups(state.items).length >= TURN_PILL_LIST_MIN_GROUPS ? (
                <>
                  <span class="turn-pill-divider" aria-hidden="true" />
                  <button type="button" class="turn-pill-list" aria-label={t("我的提问", "Your prompts")} onClick={() => { setPill(null); setPromptsOpen(true); }}>
                    <ListIcon size={16} />
                  </button>
                </>
              ) : null}
            </div>
          </div>
        ) : null}
        <div class="messages-inner">
          {!state.historyLoaded ? <div class="center-spinner"><span class="spinner" /></div> : null}
          {state.older.loading ? (
            <div class="older-history" role="status">
              <span class="spinner tiny" aria-hidden="true" />
              {t("正在加载更早的消息…", "Loading earlier messages…")}
            </div>
          ) : state.older.error ? (
            <ErrorNotice error={state.older.error} language={language} onRetry={loadOlder} variant="inline" />
          ) : null}
          {state.historyLoaded && state.items.length === 0 ? (
            sessionId === null ? (
              <div class="greeting">
                <p class="greeting-title">{greetingForHour(new Date().getHours(), language)}</p>
                <p class="greeting-sub">
                  {state.connection === "ready" || state.connection === "connecting"
                    ? t("有什么要做的，直接说。", "Whatever you need — just say it.")
                    : t("连接恢复后就能开始。", "Ready as soon as we reconnect.")}
                </p>
                {newChatProject.current?.path ? (
                  <p class="greeting-pill mono">
                    <FolderIcon size={14} />
                    {newChatProject.current.label}
                  </p>
                ) : null}
              </div>
            ) : (
              <p class="empty-chat">{t("发条消息，开始和 Hermes 对话。", "Send a message to start chatting with Hermes.")}</p>
            )
          ) : null}
          {state.items.map((item) => {
            const separator = !item.note && showsTimeSeparator(previousMs, item.timestampMs);
            if (!item.note && item.timestampMs !== null) previousMs = item.timestampMs;
            return (
              <Fragment key={item.key}>
                {separator ? <div class="time-separator">{formatTimeSeparator(item.timestampMs!, language)}</div> : null}
                <MessageView item={item} actions={actionsFor(item)} />
              </Fragment>
            );
          })}
          {state.notice ? (
            <ErrorNotice
              error={state.notice}
              language={language}
              onRetry={() => {
                dispatch({ type: "notice", error: null });
                sessionRef.current?.reconnectNow();
                if (storedId) void sessionRef.current?.loadHistory();
              }}
              onDismiss={state.terminal ? undefined : () => dispatch({ type: "notice", error: null })}
            />
          ) : null}
        </div>
      </div>
      {!atBottom && !searchOpen && state.items.length ? (
        <button type="button" class="jump-latest" aria-label={t("回到最新消息", "Jump to latest message")} onClick={toLatest}>
          <ArrowDownIcon size={20} />
        </button>
      ) : null}
      <footer class={`chat-bottom${searchOpen ? " hidden" : ""}`}>
        {open ? (
          <QuestionSheet questions={state.questions} sessionId={sessionRef.current?.answerSessionId() ?? ""} t={t} onAnswer={answer} />
        ) : null}
        {runningProcesses.length ? (
          <div class="process-card">
            <button type="button" class="process-head" aria-expanded={processesOpen} onClick={() => setProcessesOpen(!processesOpen)}>
              <TerminalIcon size={16} />
              <span>{t(`后台任务运行中 · ${runningProcesses.length}`, `${runningProcesses.length} background task${runningProcesses.length === 1 ? "" : "s"} running`)}</span>
              <ChevronIcon open={processesOpen} />
            </button>
            {processesOpen
              ? runningProcesses.map((p) => (
                  <div class="process-item" key={p.id}>
                    <code class="process-command mono">$ {p.command || p.id}</code>
                    {p.outputTail.trim() ? <pre class="process-output mono">{p.outputTail.trimEnd()}</pre> : null}
                  </div>
                ))
              : null}
          </div>
        ) : null}
        <Composer
          t={t}
          language={language}
          generating={state.generating}
          disabled={state.terminal || !device}
          onSend={send}
          onInterrupt={() => void sessionRef.current?.interrupt()}
          draftKey={device ? draftKey(device.deviceId, storedId) : null}
          sessionId={storedId}
          onOpenAttachment={(attachment, replace, remove) => setPendingImage({ attachment, replace, remove, editing: false })}
          seed={seed}
          chip={app.features.has("model-select") && !botRow ? { label: modelChipLabel(currentModel.model, reasoning, language), onClick: () => setModelOpen(true) } : null}
          blocked={
            ownedElsewhere ? (
              <div class="owned-elsewhere">
                <ErrorNotice
                  error={appError("HR-SESS-013", "session.access: owned_elsewhere")}
                  language={language}
                  onRetry={() => void sessionRef.current?.access().then((a) => setOwnedElsewhere(a === "owned_elsewhere"))}
                  variant="inline"
                />
              </div>
            ) : null
          }
        />
      </footer>
      {viewer ? <ImageViewer images={viewer.images} index={viewer.index} onClose={() => setViewer(null)} /> : null}
      {/* The preview stays mounted under the editor: 取消 / back from the editor returns to it. */}
      {pendingImage && pendingImage.attachment.previewUrl ? (
        <ImageViewer
          images={[{ kind: "local", url: pendingImage.attachment.previewUrl, name: pendingImage.attachment.name }]}
          index={0}
          onClose={() => setPendingImage(null)}
          pending={{
            onEdit: () => setPendingImage({ ...pendingImage, editing: true }),
            onRemove: () => {
              pendingImage.remove();
              setPendingImage(null);
            },
          }}
        />
      ) : null}
      {pendingImage?.editing ? (
        <ImageEditor
          attachment={pendingImage.attachment}
          onClose={() => setPendingImage({ ...pendingImage, editing: false })}
          onDone={(next) => {
            pendingImage.replace(next);
            setPendingImage(null);
          }}
        />
      ) : null}
      {promptsOpen ? (
        <PromptsSheet
          items={state.items}
          onJump={(key) => {
            setPromptsOpen(false);
            jumpTo(key);
          }}
          onLatest={() => {
            setPromptsOpen(false);
            toLatest();
          }}
          onClose={() => setPromptsOpen(false)}
        />
      ) : null}
      {shareOpen ? <ShareSheet title={storedId ? title : null} transcript={shareTranscript} onClose={() => setShareOpen(false)} /> : null}
      {userMenu ? (
        <UserMenuSheet
          item={userMenu}
          onEdit={() => setSeed({ text: userMenu.text, nonce: Date.now() })}
          onViewSource={() => setSourceItem(userMenu)}
          onClose={() => setUserMenu(null)}
        />
      ) : null}
      {sourceItem ? <SourceDialog item={sourceItem} onClose={() => setSourceItem(null)} /> : null}
      {manage && listRow ? (
        <SessionActionSheet
          session={row ?? listRow}
          start={manage}
          busy={state.generating}
          onClose={() => setManage(null)}
          move={async (cwd) => {
            const moved = await sessionRef.current!.moveWorkspace(cwd);
            dispatch({ type: "event", event: { type: "session.info", sessionId: sessionRef.current!.liveSessionId ?? "", payload: { cwd: moved.cwd ?? cwd, ...(moved.branch ? { branch: moved.branch } : {}) } } });
          }}
          onChanged={(change) => {
            if (change === "archived") navigate({ name: "list" });
          }}
        />
      ) : null}
      {modelOpen && sessionRef.current ? (
        <ModelSheet
          current={currentModel}
          profile={explicitProfile(row)}
          explicitOverride={explicitModelOverride}
          actions={{
            switchModel: (provider, model) => sessionRef.current!.switchModel(provider, model),
            reasoning: () => sessionRef.current!.reasoning(),
            setReasoning: (value) => sessionRef.current!.setReasoning(value),
          }}
          onSwitched={(provider, model, restored) => {
            setChosenModel({ provider, model });
            setExplicitModelOverride(!restored);
            app.flash(restored ? t(`已恢复默认模型 ${model}`, `Restored default model ${model}`) : t(`已切换到 ${model}`, `Switched to ${model}`));
            if (regenerateAfterSwitch) regenerate();
            setRegenerateAfterSwitch(false);
          }}
          onReasoning={setReasoning}
          onClose={() => {
            setModelOpen(false);
            setRegenerateAfterSwitch(false);
          }}
        />
      ) : null}
      {answerMenu ? (
        <Sheet closeLabel={t("关闭", "Close")} onClose={() => setAnswerMenu(null)}>
          <SheetAction
            label={t("复制", "Copy")}
            onClick={() => {
              setAnswerMenu(null);
              void copyWithFeedback(answerMenu.text, app.flash, t("已复制", "Copied"));
            }}
          />
          {answerMenu.key === lastAssistantKey && !state.generating && !state.terminal ? (
            <>
              <SheetAction
                label={t("重新生成", "Regenerate")}
                onClick={() => {
                  setAnswerMenu(null);
                  regenerate();
                }}
              />
              {app.features.has("model-select") && !botRow ? (
                <SheetAction
                  label={t("换个模型重试", "Retry with another model")}
                  onClick={() => {
                    setAnswerMenu(null);
                    setRegenerateAfterSwitch(true);
                    setModelOpen(true);
                  }}
                />
              ) : null}
            </>
          ) : null}
          {speechSupported() ? (
            <SheetAction
              label={t("朗读", "Read aloud")}
              onClick={() => {
                setAnswerMenu(null);
                toggleSpeak(answerMenu.key, readableText(answerMenu.text));
              }}
            />
          ) : null}
          <SheetAction
            label={t("查看原文 / 选择", "View source / Select")}
            onClick={() => {
              setSourceItem(answerMenu);
              setAnswerMenu(null);
            }}
          />
        </Sheet>
      ) : null}
      {botNotice && botRow ? (
        <Sheet title={botSendNoticeTitle(botRow.source, language)} closeLabel={t("取消", "Cancel")} onClose={cancelBotNotice}>
          <p class="sheet-body">{botSendNoticeBody(botRow.source, language)}</p>
          <div class="sheet-buttons">
            <button type="button" class="text-button subtle" onClick={cancelBotNotice}>
              {t("取消", "Cancel")}
            </button>
            <button
              type="button"
              class="primary-button inline"
              onClick={() => {
                markBotNoticeSeen(botRow.source!);
                const pending = botNotice;
                setBotNotice(null);
                send(pending.text, pending.attachments, true);
              }}
            >
              {t("知道了，发送", "Got it, send")}
            </button>
          </div>
        </Sheet>
      ) : null}
    </div>
  );
}
