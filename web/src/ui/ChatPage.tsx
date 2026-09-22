import { Fragment } from "preact";
import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from "preact/hooks";
import { draftKey } from "../app/drafts";
import { basename } from "../app/projects";
import { explicitProfile } from "../app/profile";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import type { PendingAttachment } from "../chat/attachments";
import { hasOpenQuestion, initialChatState, reduceChat, type ChatItem } from "../chat/model";
import { ChatSession } from "../chat/session";
import { formatTimeSeparator, greetingForHour, showsTimeSeparator } from "../chat/transcript";
import { appError } from "../errors";
import type { AnswerPlan } from "../hermes/requests";
import { Composer } from "./Composer";
import { ErrorNotice } from "./ErrorNotice";
import { ChatSearchBar, PromptsSheet, searchHits, ShareSheet, SourceDialog, useSearchHighlights, UserMenuSheet } from "./ChatSheets";
import {
  ArrowDownIcon,
  BackIcon,
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
import { ImageViewer, type ViewerImage } from "./ImageViewer";
import { MessageView, type MessageActions } from "./Message";
import { QuestionSheet } from "./QuestionSheet";
import { loadSessions } from "./SessionList";

// Chat page for /app/s/<storedSessionId> and /app/new. A new chat keeps the same page (and
// socket) when its first send gives it a durable id and the URL is replaced.

let localSeq = 0;

export function ChatPage({ sessionId }: { sessionId: string | null }) {
  const app = useApp();
  const { t, language, device, client } = app;
  const [state, dispatch] = useReducer(reduceChat, initialChatState);
  const sessionRef = useRef<ChatSession | null>(null);
  const [storedId, setStoredId] = useState<string | null>(sessionId);
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const pendingFiles = useRef(new Map<string, PendingAttachment[]>());
  const [viewer, setViewer] = useState<{ images: ViewerImage[]; index: number } | null>(null);
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

  // Follow the stream while the reader is at the bottom.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [state.items, open]);

  function onScroll() {
    const el = scroller.current;
    if (!el) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (stick.current !== atBottom) setAtBottom(stick.current);
  }

  function send(text: string, attachments: PendingAttachment[]) {
    const session = sessionRef.current;
    if (!session) return;
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
    el.classList.remove("landing");
    void el.offsetWidth;
    el.classList.add("landing");
  }

  function toLatest() {
    const el = scroller.current;
    if (!el) return;
    stick.current = true;
    el.scrollTop = el.scrollHeight;
    setAtBottom(true);
  }

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

  const connectionLine =
    state.connection === "reconnecting"
      ? t("连接中断，正在恢复…", "Reconnecting…")
      : state.connection === "offline"
        ? t("网络不可用", "Offline")
        : state.connection === "connecting" && state.historyLoaded
          ? t("正在连接…", "Connecting…")
          : "";

  const hits = useMemo(() => (searchOpen ? searchHits(state.items, searchQuery) : []), [searchOpen, searchQuery, state.items]);
  useEffect(() => setSearchFocus(hits.length ? hits.length - 1 : 0), [searchQuery]);
  useSearchHighlights(searchOpen ? scroller.current : null, searchQuery, hits, Math.min(searchFocus, Math.max(0, hits.length - 1)), state.items);

  const lastAssistantKey = [...state.items].reverse().find((i) => i.role === "assistant" && !i.note)?.key ?? null;
  const actionsFor = (item: ChatItem): MessageActions => ({
    onRetry: retry,
    onOpenImage: (images, index) => setViewer({ images, index }),
    onUserMenu: (it) => setUserMenu(it),
    onViewSource: (it) => setSourceItem(it),
    ...(item.key === lastAssistantKey && !state.generating && !state.terminal ? { onRegenerate: regenerate } : {}),
  });

  let previousMs: number | null = null;

  return (
    <div class="page chat-page">
      <header class="topbar">
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
              ) : workspaceLabel && !emptyNew ? (
                <span class="chat-workspace mono">
                  <FolderIcon size={12} />
                  <span class="chat-workspace-name">{workspaceLabel}</span>
                  {workspaceBranch ? (
                    <>
                      <span aria-hidden="true">·</span>
                      <BranchIcon size={12} />
                      <span class="chat-workspace-name">{workspaceBranch}</span>
                    </>
                  ) : null}
                </span>
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
            </div>
          </>
        ) : null}
        {refreshing ? <div class="top-progress" aria-hidden="true" /> : null}
      </header>
      <div class="messages" ref={scroller} onScroll={onScroll}>
        <div class="messages-inner">
          {!state.historyLoaded ? <div class="center-spinner"><span class="spinner" /></div> : null}
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
        <Composer
          t={t}
          language={language}
          generating={state.generating}
          disabled={state.terminal || !device}
          onSend={send}
          onInterrupt={() => void sessionRef.current?.interrupt()}
          draftKey={device ? draftKey(device.deviceId, storedId) : null}
          seed={seed}
        />
      </footer>
      {viewer ? <ImageViewer images={viewer.images} index={viewer.index} onClose={() => setViewer(null)} /> : null}
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
      {shareOpen ? <ShareSheet title={storedId ? title : null} items={state.items} onClose={() => setShareOpen(false)} /> : null}
      {userMenu ? (
        <UserMenuSheet
          item={userMenu}
          onEdit={() => setSeed({ text: userMenu.text, nonce: Date.now() })}
          onViewSource={() => setSourceItem(userMenu)}
          onClose={() => setUserMenu(null)}
        />
      ) : null}
      {sourceItem ? <SourceDialog item={sourceItem} onClose={() => setSourceItem(null)} /> : null}
    </div>
  );
}
