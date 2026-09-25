import { useEffect, useMemo, useState } from "preact/hooks";
import { useApp } from "../app/store";
import { appError } from "../errors";
import type { HistoryLocator, MessageRow } from "../hermes/types";
import type { ChatItem } from "../chat/model";
import {
  formatElapsed,
  formatToolDuration,
  groupTools,
  organizeAssistant,
  RUN_WAIT_ELAPSED_AFTER_MS,
  runningStatus,
  settledTodoStatus,
  stabilizeStreaming,
  todoProgress,
  toolFailed,
  type TimelineNote,
  type ToolCard,
  type ToolLabelKey,
} from "../chat/organize";
import { onSpeaking, speakingNow, speechSupported, toggleSpeak } from "../chat/speech";
import { isAllowedHref, readableText } from "../markdown/render";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon, CopyIcon, MoreIcon, RefreshIcon, SpeakerIcon, ThumbDownIcon, ThumbUpIcon } from "./icons";
import type { ViewerImage } from "./ImageViewer";
import { copyWithFeedback, Markdown } from "./Markdown";
import { normalizeDisplayPayload } from "../chat/organize";
import { FileCard, MacImage } from "./Media";

// One turn (DESIGN §5.4 / §5.21, Android ChatComponents.kt). User: bubble (surface-variant 78%,
// 22/22/7/22), tap for its menu. Assistant: no bubble — thinking → tools → images → prose → files →
// action row. Tool output is plain text, never HTML. Tapping an image opens the viewer on that
// message's images only. Injected system turns render as one quiet timeline note.

const TOOL_PREVIEW_CHARS = 12_000;
const RUNNING_TOOL_PREVIEW_ROWS = 3;

const LABELS: Record<ToolLabelKey, [string, string]> = {
  terminal: ["终端结果", "Terminal result"],
  tool: ["工具结果", "Tool result"],
  "web-search": ["网页搜索", "Web search"],
  browser: ["浏览器结果", "Browser result"],
  details: ["执行详情", "Details"],
  "file-mutation": ["部分文件操作未完成", "Some file changes didn't complete"],
};

function useToolName() {
  const { t } = useApp();
  return (tool: ToolCard) => (tool.labelKey ? t(...LABELS[tool.labelKey]) : tool.name || t("工具", "Tool"));
}

function StatusDot({ running, failed }: { running: boolean; failed: boolean }) {
  return <span class={`tool-dot${running ? " running" : failed ? " failed" : " done"}`} aria-hidden="true" />;
}

function useFullRow(source: HistoryLocator | undefined): { row: MessageRow | null; loading: boolean; failed: boolean; retry: () => void } {
  const { client, device } = useApp();
  const key = source ? `${source.sessionId}:${source.rowId}:${source.offset}:${source.profile ?? ""}` : "";
  const [resolved, setResolved] = useState<{ key: string; row: MessageRow } | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [loading, setLoading] = useState(Boolean(source));
  useEffect(() => {
    if (!source) return;
    if (!device) { setFailed(true); setLoading(false); return; }
    let active = true;
    setResolved(null);
    setLoading(true);
    setFailed(false);
    void client.fullHistoryRow(device.deviceId, source).then((answer) => {
      if (!active) return;
      const found = answer.messages.find((candidate) => candidate.id === source.rowId);
      if (!found) throw new Error("history row missing");
      setResolved({ key, row: found });
    }).catch(() => { if (active) setFailed(true); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, device?.deviceId, source?.sessionId, source?.profile, source?.rowId, source?.offset, retry]);
  return { row: resolved?.key === key ? resolved.row : null, loading, failed, retry: () => setRetry((value) => value + 1) };
}

function useFullReasoning(sources: readonly HistoryLocator[], open: boolean) {
  const { client, device } = useApp();
  const [rows, setRows] = useState<MessageRow[] | null>(null);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const key = sources.map((s) => `${s.sessionId}:${s.rowId}:${s.offset}:${s.profile ?? ""}`).join("|");
  useEffect(() => {
    if (!open || sources.length === 0 || (rows && loadedKey === key)) return;
    if (!device) { setFailed(true); return; }
    let active = true;
    setRows(null);
    setLoading(true);
    setFailed(false);
    void Promise.all(sources.map(async (source) => {
      const answer = await client.fullHistoryRow(device.deviceId, source);
      const row = answer.messages.find((candidate) => candidate.id === source.rowId);
      if (!row) throw new Error("history row missing");
      return row;
    })).then((found) => { if (active) { setRows(found); setLoadedKey(key); } })
      .catch(() => { if (active) setFailed(true); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, device?.deviceId, key, open, retry]);
  return { rows: loadedKey === key ? rows : null, loading, failed, retry: () => setRetry((value) => value + 1) };
}

function ToolOutput({ tool }: { tool: ToolCard }) {
  const { t, flash, language } = useApp();
  const failed = toolFailed(tool);
  const full = useFullRow(tool.historySource);
  const output = full.row && typeof full.row.content === "string" ? normalizeDisplayPayload(full.row.content) : tool.output;
  return (
    <div class="tool-body">
      <pre class={`tool-output mono${failed ? " failed" : ""}`}>{output.slice(0, TOOL_PREVIEW_CHARS)}</pre>
      {full.loading ? <p class="tool-note">{t("正在读取全文…", "Loading full content…")}</p> : null}
      {full.failed ? <ErrorNotice error={appError("HR-SYNC-005")} language={language} onRetry={full.retry} variant="inline" /> : null}
      <div class="tool-body-actions">
        <button type="button" class="text-button subtle small" disabled={Boolean(tool.historySource) && !full.row}
          onClick={() => void copyWithFeedback(output, flash, t("已复制", "Copied"))}>
          <CopyIcon size={14} />
          {t("复制结果", "Copy result")}
        </button>
      </div>
      {output.length > TOOL_PREVIEW_CHARS ? (
        <p class="tool-note">{t("内容较长，界面仅预览前 12,000 字符；复制可获取完整结果。", "Long content: the app previews 12,000 characters. Copy to get the full result.")}</p>
      ) : null}
    </div>
  );
}

function statusText(tool: ToolCard, t: (zh: string, en: string) => string): string {
  if (!tool.done) return t("运行中…", "Running…");
  if (toolFailed(tool)) return `exit ${tool.exitCode}`;
  if (tool.durationMs != null) return formatToolDuration(tool.durationMs);
  return t("已完成", "Completed");
}

/** One tool call: status dot, name, status; `$ command` under a rail; output on tap. */
function ToolCardView({ tool }: { tool: ToolCard }) {
  const { t } = useApp();
  const name = useToolName();
  const [open, setOpen] = useState(false);
  const hasOutput = tool.output.trim() !== "" || Boolean(tool.historySource);
  const failed = toolFailed(tool);
  return (
    <div class={`tool-card${failed ? " failed" : ""}`}>
      <button type="button" class="tool-card-head" aria-expanded={hasOutput ? open : undefined} disabled={!hasOutput} onClick={() => setOpen(!open)}>
        <StatusDot running={!tool.done} failed={failed} />
        <span class="tool-card-name">{name(tool)}</span>
        <span class={`tool-card-status${!tool.done ? " running" : failed ? " failed" : ""}`}>{statusText(tool, t)}</span>
        {hasOutput ? <ChevronIcon open={open} /> : null}
      </button>
      {tool.command ? (
        <div class="tool-rail">
          <code class={`tool-command mono${open ? " open" : ""}`}>$ {tool.command}</code>
          {open && hasOutput ? <ToolOutput tool={tool} /> : null}
        </div>
      ) : open && hasOutput ? (
        <ToolOutput tool={tool} />
      ) : null}
    </div>
  );
}

/** Two or more consecutive calls: one card, the newest rows while running, a summary when done. */
function ToolTimeline({ tools, completed }: { tools: ToolCard[]; completed: boolean }) {
  const { t } = useApp();
  const name = useToolName();
  const [expanded, setExpanded] = useState(false);
  const [openRow, setOpenRow] = useState<string | null>(null);
  const failed = tools.filter(toolFailed).length;
  const folded = completed && !expanded;
  const summary = `${t(`${tools.length} 次工具调用`, `${tools.length} tool calls`)}${failed ? ` · ${t(`${failed} 次失败`, `${failed} failed`)}` : ""}`;
  const visible = folded ? [] : completed || expanded ? tools : tools.slice(-RUNNING_TOOL_PREVIEW_ROWS);
  return (
    <div class={`tool-timeline${folded ? " folded" : ""}`}>
      {completed || tools.length > RUNNING_TOOL_PREVIEW_ROWS ? (
        <button
          type="button"
          class={`tool-timeline-summary${failed ? " failed" : ""}`}
          aria-expanded={!folded}
          aria-label={!folded && expanded ? t("收起工具时间线", "Collapse tool timeline") : t("展开全部工具调用", "Expand all tool calls")}
          onClick={() => setExpanded(!expanded)}
        >
          <ChevronIcon open={!folded} />
          <span>{summary}</span>
        </button>
      ) : null}
      {visible.map((tool) => {
        const rowFailed = toolFailed(tool);
        const hasOutput = tool.output.trim() !== "" || Boolean(tool.historySource);
        const open = openRow === tool.id;
        return (
          <div class="tool-timeline-row" key={tool.id}>
            <button type="button" class="tool-timeline-head" disabled={!hasOutput} aria-expanded={hasOutput ? open : undefined} onClick={() => setOpenRow(open ? null : tool.id)}>
              <StatusDot running={!tool.done} failed={rowFailed} />
              <span class="tool-timeline-text">
                <span class="tool-card-name">{name(tool)}</span>
                {tool.command ? <span class="tool-timeline-command mono">{tool.command.split("\n")[0]}</span> : null}
              </span>
              <span class={`tool-card-status${!tool.done ? " running" : rowFailed ? " failed" : ""}`}>{statusText(tool, t)}</span>
            </button>
            {open && hasOutput ? <ToolOutput tool={tool} /> : null}
          </div>
        );
      })}
    </div>
  );
}

function TodoCard({ tool, completed }: { tool: ToolCard; completed: boolean }) {
  const { t } = useApp();
  const todos = (tool.todos ?? []).map((todo) => ({ ...todo, status: settledTodoStatus(todo.status, completed) }));
  const [done, total] = todoProgress(todos);
  return (
    <div class="todo-card">
      <div class="todo-head">
        <span class="todo-title">{t("任务清单", "Task list")}</span>
        <span class="todo-count mono">
          {done}/{total}
        </span>
      </div>
      <progress class="todo-progress" max={Math.max(1, total)} value={done} />
      <ul class="todo-list">
        {todos.map((todo, i) => (
          <li class={`todo-item ${todo.status}`} key={i}>
            <span class={`todo-box ${todo.status}`} aria-hidden="true" />
            <span class="todo-text">{todo.content}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Tools({ tools, completed }: { tools: ToolCard[]; completed: boolean }) {
  return (
    <div class="tools">
      {groupTools(tools).map((group) =>
        group.kind === "timeline" ? (
          <ToolTimeline key={group.tools[0]!.id} tools={group.tools} completed={completed} />
        ) : group.tool.todos?.length ? (
          <TodoCard key={group.tool.id} tool={group.tool} completed={completed} />
        ) : (
          <ToolCardView key={group.tool.id} tool={group.tool} />
        ),
      )}
    </div>
  );
}

function Reasoning({ text, streaming, parts = [] }: { text: string; streaming: boolean; parts?: Array<{ text: string; source?: HistoryLocator }> }) {
  const { t, language } = useApp();
  const [open, setOpen] = useState(false);
  const sources = parts.flatMap((part) => part.source ? [part.source] : []);
  const full = useFullReasoning(sources, open);
  const shown = full.rows ? parts.map((part) => {
    const row = full.rows?.find((candidate) => candidate.id === part.source?.rowId);
    return row ? row.reasoning_content || row.reasoning || part.text : part.text;
  }).join("\n\n") : text;
  return (
    <div class="reasoning">
      <button type="button" class="reasoning-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <ChevronIcon open={open} />
        <span>{streaming && !open ? t("思考中…", "Thinking…") : open ? t("收起思考过程", "Hide reasoning") : t("查看思考过程", "View reasoning")}</span>
      </button>
      {open ? <>
        <p class="reasoning-text">{shown}</p>
        {full.loading ? <p class="tool-note">{t("正在读取全文…", "Loading full content…")}</p> : null}
        {full.failed ? <ErrorNotice error={appError("HR-SYNC-005")} language={language} onRetry={full.retry} variant="inline" /> : null}
      </> : null}
    </div>
  );
}

/** What a streaming turn is doing right now, with the run's true elapsed time (HG-56). */
function RunningStatusLine({ item }: { item: { tools: ToolCard[]; text: string; reasoning: string; timestampMs: number | null } }) {
  const { t, language } = useApp();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const zh = language !== "en";
  const hasOutput = item.text.trim() !== "" || item.tools.length > 0 || item.reasoning.trim() !== "";
  const elapsed = item.timestampMs !== null ? Math.max(0, now - item.timestampMs) : null;
  const suffix = elapsed !== null ? ` · ${formatElapsed(elapsed, zh)}` : "";
  let label: string;
  if (!hasOutput) {
    label = elapsed !== null && elapsed >= RUN_WAIT_ELAPSED_AFTER_MS ? `${t("已运行 ", "Running for ")}${formatElapsed(elapsed, zh)}` : t("正在准备…", "Preparing…");
  } else {
    const status = runningStatus(item);
    label =
      status.kind === "tool"
        ? `${t("正在运行 ", "Running ")}${status.label}…${suffix}`
        : status.kind === "thinking"
          ? `${status.preview}${suffix}`
          : `${t("生成中…", "Generating…")}${suffix}`;
  }
  return (
    <div class="running-line" role="status">
      <span class="loading-dots" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      <span class="running-text mono">{label}</span>
    </div>
  );
}

export function TimelineNoteRow({ note, body }: { note: TimelineNote; body: string }) {
  const { t, language } = useApp();
  const [open, setOpen] = useState(false);
  return (
    <div class="timeline-note">
      <button type="button" class={`timeline-pill${note.expandable ? " expandable" : ""}`} disabled={!note.expandable} aria-expanded={note.expandable ? open : undefined} onClick={() => setOpen(!open)}>
        {note.glyph ? <span class="timeline-glyph" aria-hidden="true">{note.glyph}</span> : null}
        <span>{language === "en" ? note.en : note.zh}</span>
        {note.expandable ? <span class="timeline-toggle">{open ? t("收起 ▴", "Hide ▴") : t("展开 ▾", "Show ▾")}</span> : null}
      </button>
      {open ? <pre class="timeline-body mono">{body}</pre> : null}
    </div>
  );
}

export interface MessageActions {
  onRetry?: (item: ChatItem) => void;
  onOpenImage?: (images: ViewerImage[], index: number) => void;
  /** Only on the latest settled answer while nothing is generating (Android canRegenerate). */
  onRegenerate?: () => void;
  onUserMenu?: (item: ChatItem) => void;
  onViewSource?: (item: ChatItem) => void;
}

function useSpeaking(key: string): boolean {
  const [speaking, setSpeaking] = useState(speakingNow() === key);
  useEffect(() => onSpeaking((k) => setSpeaking(k === key)), [key]);
  return speaking;
}

function ActionRow({ item, actions }: { item: ChatItem; actions: MessageActions }) {
  const { t, flash } = useApp();
  const [feedback, setFeedback] = useState<0 | 1 | -1>(0);
  const speaking = useSpeaking(item.key);
  return (
    <div class="message-actions">
      <button type="button" class="icon-button action-button" aria-label={t("复制回复", "Copy response")} onClick={() => void copyWithFeedback(item.text, flash, t("已复制", "Copied"))}>
        <CopyIcon size={18} />
      </button>
      {/* Like Android, the thumbs stay on this page: nothing is sent anywhere. */}
      <button type="button" class={`icon-button action-button${feedback === 1 ? " active" : ""}`} aria-pressed={feedback === 1} aria-label={t("有帮助", "Helpful")} onClick={() => setFeedback(feedback === 1 ? 0 : 1)}>
        <ThumbUpIcon size={18} />
      </button>
      <button type="button" class={`icon-button action-button${feedback === -1 ? " active" : ""}`} aria-pressed={feedback === -1} aria-label={t("需要改进", "Needs improvement")} onClick={() => setFeedback(feedback === -1 ? 0 : -1)}>
        <ThumbDownIcon size={18} />
      </button>
      {speechSupported() ? (
        <button type="button" class={`icon-button action-button${speaking ? " active" : ""}`} aria-pressed={speaking} aria-label={speaking ? t("停止朗读", "Stop reading") : t("朗读", "Read aloud")} onClick={() => toggleSpeak(item.key, readableText(item.text))}>
          <SpeakerIcon size={18} />
        </button>
      ) : null}
      {actions.onRegenerate ? (
        <button type="button" class="icon-button action-button" aria-label={t("重新生成", "Regenerate")} onClick={actions.onRegenerate}>
          <RefreshIcon size={18} />
        </button>
      ) : null}
      {actions.onViewSource ? (
        <button type="button" class="icon-button action-button" aria-label={t("查看原文 / 选择", "View source / Select")} onClick={() => actions.onViewSource!(item)}>
          <MoreIcon size={18} />
        </button>
      ) : null}
    </div>
  );
}

export function MessageView({ item, actions = {} }: { item: ChatItem; actions?: MessageActions }) {
  const { language, t } = useApp();

  // While streaming, mask unfinished payloads and pull finished ones into cards per snapshot, so
  // a payload never first explodes into Markdown and then collapses (Android stabilizedForStreaming).
  const placeholder = t("工具数据接收中…", "Receiving tool data…");
  const display = useMemo(
    () => (item.role === "assistant" && item.streaming ? organizeAssistant(stabilizeStreaming(item.text, placeholder), item.tools) : { text: item.text, tools: item.tools }),
    [item.text, item.tools, item.streaming, item.role, placeholder],
  );

  if (item.note) return <TimelineNoteRow note={item.note} body={item.text} />;

  const images = item.attachments.filter((a) => a.kind === "image");
  const files = item.attachments.filter((a) => a.kind === "download");
  const blockImages = item.images.filter((img) => img.path);
  const blockLinks = item.images.filter((img) => !img.path && isAllowedHref(img.url));
  const gallery: ViewerImage[] = [
    ...(item.role === "user" ? (item.localImages ?? []).map((url): ViewerImage => ({ kind: "local", url, name: "" })) : []),
    ...images.map((a): ViewerImage => ({ kind: "mac", path: a.path, name: a.name })),
    ...blockImages.map((img): ViewerImage => ({ kind: "mac", path: img.path!, name: "" })),
  ];
  const opener = (i: number) => (actions.onOpenImage ? () => actions.onOpenImage!(gallery, i) : undefined);
  const localCount = item.role === "user" ? (item.localImages?.length ?? 0) : 0;
  // One image shows whole; two or more share a two-column grid (Android ImageGridLayout, HG-43).
  const mediaClass = gallery.length > 1 ? " grid" : "";

  if (item.role === "user") {
    const retryable = item.send === "failed" && item.error?.retryable === true && actions.onRetry !== undefined;
    return (
      <div class="turn turn-user" data-key={item.key}>
        {gallery.length ? (
          <div class={`user-media${mediaClass}`}>
            {item.localImages?.map((url, i) => (
              <button type="button" class="media-open" key={url} aria-label={t("查看图片", "View image")} onClick={opener(i)}>
                <img class="media-image" src={url} alt="" />
              </button>
            ))}
            {images.map((a, i) => <MacImage key={a.path} path={a.path} name={a.name} onOpen={opener(localCount + i)} />)}
            {blockImages.map((img, i) => <MacImage key={img.path} path={img.path!} name="" onOpen={opener(localCount + images.length + i)} />)}
          </div>
        ) : null}
        {item.localFiles?.length ? (
          <div class="user-files">
            {item.localFiles.map((name) => (
              <span class="file-chip" key={name}>{name}</span>
            ))}
          </div>
        ) : null}
        {files.map((a) => <FileCard key={a.path} attachment={a} />)}
        {item.text ? (
          <div
            class={`bubble${item.send === "failed" ? " failed" : ""}${item.send === "sending" ? " sending" : ""}`}
            role="button"
            tabIndex={0}
            aria-label={retryable ? t("点按重试", "Tap to retry") : undefined}
            onClick={() => (retryable ? actions.onRetry!(item) : actions.onUserMenu?.(item))}
          >
            {item.text}
          </div>
        ) : null}
        {item.send === "failed" && item.error ? (
          <ErrorNotice error={item.error} language={language} onRetry={actions.onRetry && item.error.retryable ? () => actions.onRetry!(item) : undefined} variant="inline" />
        ) : null}
      </div>
    );
  }

  if (item.role === "system") {
    return item.text ? <div class="turn turn-system">{item.text}</div> : null;
  }

  return (
    <div class="turn turn-assistant" data-key={item.key}>
      {item.reasoning.trim() ? <Reasoning text={item.reasoning} streaming={item.streaming && !display.text} parts={item.reasoningParts} /> : null}
      {display.tools.length ? <Tools tools={display.tools} completed={!item.streaming} /> : null}
      {gallery.length ? (
        <div class={`assistant-media${mediaClass}`}>
          {images.map((a, i) => <MacImage key={a.path} path={a.path} name={a.name} onOpen={opener(i)} />)}
          {blockImages.map((img, i) => <MacImage key={img.path} path={img.path!} name="" onOpen={opener(images.length + i)} />)}
        </div>
      ) : null}
      {display.text ? <Markdown source={display.text} streaming={item.streaming} /> : null}
      {blockLinks.map((img) => (
        <a class="media-link" href={img.url} target="_blank" rel="noopener noreferrer" key={img.url}>
          {img.url}
        </a>
      ))}
      {files.map((a) => <FileCard key={a.path} attachment={a} />)}
      {item.streaming ? <RunningStatusLine item={{ tools: display.tools, text: display.text, reasoning: item.reasoning, timestampMs: item.timestampMs }} /> : null}
      {item.interrupted ? <span class="interrupted-note">{t("已中断", "Interrupted")}</span> : null}
      {!item.streaming && item.text.trim() ? <ActionRow item={item} actions={actions} /> : null}
    </div>
  );
}
