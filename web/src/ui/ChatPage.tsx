import { useEffect, useLayoutEffect, useReducer, useRef, useState } from "preact/hooks";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import type { PendingAttachment } from "../chat/attachments";
import { hasOpenQuestion, initialChatState, reduceChat, type ChatItem } from "../chat/model";
import { ChatSession } from "../chat/session";
import type { AnswerPlan } from "../hermes/requests";
import { Composer } from "./Composer";
import { ErrorNotice } from "./ErrorNotice";
import { BackIcon } from "./icons";
import { MessageView } from "./Message";
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

  // One ChatSession per conversation; adopting the id a new chat just got keeps the socket.
  useEffect(() => {
    if (!device) return;
    const current = sessionRef.current;
    if (current && sessionId !== null && current.storedSessionId === sessionId) return;
    current?.dispose();
    dispatch({ type: "reset" });
    const session = new ChatSession({
      client,
      deviceId: device.deviceId,
      storedSessionId: sessionId,
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
  }, [sessionId, device?.deviceId]);

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

  const open = hasOpenQuestion(state);
  useEffect(() => {
    if (storedId) app.reportLiveQuestion(storedId, open);
  }, [storedId, open]);
  useEffect(() => () => {
    if (storedId) app.reportLiveQuestion(storedId, false);
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

  const title =
    (storedId && app.sessions.find((s) => s.id === storedId)?.title) ||
    (storedId && app.inbox.latest[storedId]?.title) ||
    (storedId ? t("会话", "Conversation") : t("新会话", "New chat"));

  const connectionLine =
    state.connection === "reconnecting"
      ? t("连接中断，正在恢复…", "Reconnecting…")
      : state.connection === "offline"
        ? t("网络不可用", "Offline")
        : state.connection === "connecting"
          ? t("正在连接…", "Connecting…")
          : state.generating
            ? t("正在回复…", "Replying…")
            : "";

  return (
    <div class="page chat-page">
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={() => navigate({ name: "list" })}>
            <BackIcon />
          </button>
          <div class="chat-title">
            <h1 class="topbar-title left">{title}</h1>
            {connectionLine ? <span class={`chat-status${state.generating && state.connection === "ready" ? " running" : ""}`}>{connectionLine}</span> : null}
          </div>
          <span class="topbar-spacer" />
        </div>
      </header>
      <div class="messages" ref={scroller} onScroll={onScroll}>
        <div class="messages-inner">
          {!state.historyLoaded ? <div class="center-spinner"><span class="spinner" /></div> : null}
          {state.historyLoaded && state.items.length === 0 ? (
            <p class="empty-chat">{t("有什么可以帮你？", "What can I help with?")}</p>
          ) : null}
          {state.items.map((item) => (
            <MessageView key={item.key} item={item} onRetry={retry} />
          ))}
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
      <footer class="chat-bottom">
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
        />
      </footer>
    </div>
  );
}
