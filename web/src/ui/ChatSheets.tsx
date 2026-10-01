import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { useBackClose } from "../app/useBackClose";
import { copyText } from "../app/clipboard";
import { useApp } from "../app/store";
import { historySyncError } from "../chat/history";
import { itemsWithFullHistory, type ChatItem, type ChatState } from "../chat/model";
import { turnGroups } from "../chat/turns";
import { formatTimeSeparator, transcriptFileBaseName, transcriptMarkdown, transcriptText } from "../chat/transcript";
import { appError, diagnostics, type AppError } from "../errors";
import type { MessageRow } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { readableText } from "../markdown/render";
import { ArrowDownIcon, BackIcon, ChevronDownIcon, ChevronIcon, ChevronUpIcon, CloseIcon, SearchIcon } from "./icons";
import { copyWithFeedback } from "./Markdown";
import { Sheet, SheetAction } from "./Sheet";

// The chat page's secondary surfaces (Android ChatScreen ⋮ menu, ChatSearchBar, the prompts sheet,
// ShareTranscriptSheet, the user-bubble menu and TextSelectionDialog), Web-sized.

// ---- in-chat search --------------------------------------------------------------------------

export interface SearchHit {
  key: string;
  /** Occurrence index inside that turn. */
  nth: number;
}

/** Visible body text only — not thinking, not tools (HG-45). */
export function searchHits(items: readonly ChatItem[], query: string): SearchHit[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const hits: SearchHit[] = [];
  for (const item of items) {
    if (item.note || (item.role !== "user" && item.role !== "assistant")) continue;
    const text = (item.role === "assistant" ? readableText(item.text) : item.text).toLowerCase();
    let from = 0;
    let nth = 0;
    for (;;) {
      const at = text.indexOf(q, from);
      if (at < 0) break;
      hits.push({ key: item.key, nth: nth++ });
      from = at + q.length;
    }
  }
  return hits;
}

type HighlightRegistry = { set: (name: string, value: unknown) => void; delete: (name: string) => void };

function highlights(): HighlightRegistry | null {
  const registry = (globalThis as { CSS?: { highlights?: HighlightRegistry } }).CSS?.highlights;
  return registry && typeof (globalThis as { Highlight?: unknown }).Highlight === "function" ? registry : null;
}

/**
 * Mark every occurrence with the CSS Custom Highlight API (no DOM mutation, so the sanitized
 * Markdown stays untouched) and scroll the focused one into view. Browsers without the API still
 * scroll to and outline the focused turn.
 */
export function useSearchHighlights(root: HTMLElement | null, query: string, hits: readonly SearchHit[], focus: number, version: unknown): void {
  useEffect(() => {
    const registry = highlights();
    const q = query.trim().toLowerCase();
    const focused = hits[focus];
    root?.querySelectorAll(".search-focus").forEach((el) => el.classList.remove("search-focus"));
    if (!root || !q) {
      registry?.delete("chat-search");
      registry?.delete("chat-search-current");
      return;
    }
    const all: Range[] = [];
    let current: Range | null = null;
    root.querySelectorAll<HTMLElement>(".turn[data-key]").forEach((turn) => {
      const key = turn.dataset.key;
      let nth = 0;
      turn.querySelectorAll(".bubble, .markdown").forEach((body) => {
        const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
          acceptNode: (node) => ((node.parentElement?.closest(".block-head") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT)),
        });
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          const text = (node.textContent ?? "").toLowerCase();
          let from = 0;
          for (;;) {
            const at = text.indexOf(q, from);
            if (at < 0) break;
            const range = document.createRange();
            range.setStart(node, at);
            range.setEnd(node, at + q.length);
            all.push(range);
            if (focused && key === focused.key && nth === focused.nth) current = range;
            nth++;
            from = at + q.length;
          }
        }
      });
    });
    if (registry) {
      const H = (globalThis as unknown as { Highlight: new (...r: Range[]) => unknown }).Highlight;
      registry.set("chat-search", new H(...all));
      if (current) registry.set("chat-search-current", new H(current));
      else registry.delete("chat-search-current");
    }
    if (focused) {
      const turn = root.querySelector<HTMLElement>(`.turn[data-key="${CSS.escape(focused.key)}"]`);
      // The outline is only the fallback: with the Highlight API the current hit is marked itself.
      if (!registry) turn?.classList.add("search-focus");
      const target = (current as Range | null)?.getBoundingClientRect ? current : null;
      if (target) {
        const box = (target as Range).getBoundingClientRect();
        const view = root.getBoundingClientRect();
        root.scrollTop += box.top - view.top - view.height / 2;
      } else turn?.scrollIntoView({ block: "center" });
    }
  }, [root, query, hits, focus, version]);

  useEffect(
    () => () => {
      highlights()?.delete("chat-search");
      highlights()?.delete("chat-search-current");
    },
    [],
  );
}

export function ChatSearchBar({
  query,
  onQuery,
  hits,
  focus,
  onFocus,
  onClose,
  onSearchAll,
}: {
  query: string;
  onQuery: (q: string) => void;
  hits: number;
  focus: number;
  onFocus: (i: number) => void;
  onClose: () => void;
  onSearchAll: () => void;
}) {
  const { t } = useApp();
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => input.current?.focus(), []);
  const none = query.trim() !== "" && hits === 0;
  return (
    <>
      <div class="topbar-row search-row">
        <button type="button" class="icon-button" aria-label={t("关闭搜索", "Close search")} onClick={onClose}>
          <BackIcon />
        </button>
        <input
          ref={input}
          class="search-input"
          type="search"
          enterkeyhint="search"
          value={query}
          placeholder={t("在对话中搜索…", "Search in chat…")}
          onInput={(e) => onQuery((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && hits) onFocus((focus + 1) % hits);
          }}
        />
        {hits ? <span class="search-count mono">{`${focus + 1}/${hits}`}</span> : null}
        <button type="button" class="icon-button" aria-label={t("上一个", "Previous")} disabled={!hits} onClick={() => onFocus((focus - 1 + hits) % hits)}>
          <ChevronUpIcon />
        </button>
        <button type="button" class="icon-button" aria-label={t("下一个", "Next")} disabled={!hits} onClick={() => onFocus((focus + 1) % hits)}>
          <ChevronDownIcon />
        </button>
      </div>
      {none ? (
        <div class="search-none">
          <span>{t("此会话中没有匹配", "No matches in this chat")}</span>
          <button type="button" class="text-button" onClick={onSearchAll}>
            <SearchIcon size={16} />
            {t("在全部会话中搜索", "Search all chats")}
          </button>
        </div>
      ) : null}
    </>
  );
}

// ---- prompts list --------------------------------------------------------------------------

export function PromptsSheet({ items, currentKey, bot = false, loading = false, error, onRetry, onJump, onLatest, onClose }: {
  items: readonly ChatItem[];
  currentKey?: string | null;
  bot?: boolean;
  loading?: boolean;
  error?: AppError | null;
  onRetry?: () => void;
  onJump: (key: string | null) => void;
  onLatest: () => void;
  onClose: () => void;
}) {
  const { t, language } = useApp();
  const groups = turnGroups(items);
  const prompts = items.filter((i) => i.role === "user" && !i.note);
  const byKey = new Map(prompts.map((item, i) => [item.key, { item, index: i + 1 }]));
  const rows = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const index = groups.findIndex((g) => g.key === currentKey);
    const list = rows.current?.closest<HTMLElement>(".picker-list");
    const above = rows.current?.children[Math.max(0, index - 2)] as HTMLElement | undefined;
    if (index >= 0 && list && above) list.scrollTop = above.offsetTop - rows.current!.offsetTop;
  }, [currentKey, loading]);
  return (
    <Sheet title={bot ? t("对方的提问", "Their prompts") : t("我的提问", "Your prompts")} closeLabel={t("关闭", "Close")} onClose={onClose} wide className="chat-prompts-sheet"
      titleAccessory={<span class="prompt-count mono" aria-live="polite">{loading || error ? "…" : t(`${prompts.length} 条`, `${prompts.length} prompts`)}</span>}
      headerAction={<button type="button" class="icon-button" aria-label={t("回到最新", "Latest")} onClick={onLatest}><ArrowDownIcon /></button>}>
      {loading ? <p class="picker-note" role="status">{t("正在读取全部提问…", "Loading all prompts…")}</p> : null}
      {error ? <ErrorNotice error={error} language={language} onRetry={onRetry} /> : null}
      {prompts.length === 0 && !loading && !error ? <p class="picker-note">{t("还没有提问。", "No prompts yet.")}</p> : null}
      <div ref={rows}>
        {groups.map((group) => {
          const prompt = group.key === null ? undefined : byKey.get(group.key);
          const item = prompt?.item;
          const index = prompt?.index ?? null;
          const current = group.key === currentKey;
          const summary = group.key === null ? t("会话开始", "Start of chat") : language === "en" ? group.summary.en : group.summary.zh;
          return (
            <button type="button" class={`prompt-row${current ? " current" : ""}`} key={group.key ?? "start"} aria-current={current ? "location" : undefined}
              aria-label={`${index === null ? "" : t(`第 ${index} 条，`, `Prompt ${index}, `)}${summary}${current ? t("，当前位置", ", You are here") : ""}`} onClick={() => onJump(group.key)}>
              <span class={`prompt-index mono${index === null ? " start" : ""}`} aria-hidden="true">{index}</span>
              <span class="prompt-content">
                <span class="prompt-text">{summary}</span>
                {item?.timestampMs != null ? <span class="prompt-time mono">{formatTimeSeparator(item.timestampMs, language)}</span> : null}
              </span>
              <span class="prompt-chevron"><ChevronIcon size={20} /></span>
            </button>
          );
        })}
      </div>
    </Sheet>
  );
}

// ---- share ----------------------------------------------------------------------------------

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

export interface ShareTranscript {
  /** null until the whole conversation is known. */
  items: readonly ChatItem[] | null;
  loading: boolean;
  error: AppError | null;
  retry: () => void;
}

/**
 * What "share transcript" exports: the whole conversation (HG-104). The chat shows only the pages
 * read so far; while older ones exist, opening the sheet fetches every stored row and rebuilds
 * the items the way the page would show them, with local turns kept as on screen.
 */
export function useShareTranscript(open: boolean, state: ChatState, loadFull: (() => Promise<MessageRow[]>) | null): ShareTranscript {
  const needFull = open && state.older.hasMore && loadFull !== null;
  const [rows, setRows] = useState<MessageRow[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!open) {
      setRows(null);
      setError(null);
      setLoading(false);
    }
    if (!needFull || rows) return;
    let live = true;
    setLoading(true);
    setError(null);
    loadFull!().then(
      (all) => {
        if (!live) return;
        setRows(all);
        setLoading(false);
      },
      (e: unknown) => {
        if (!live) return;
        setError(historySyncError(e, "full transcript for sharing"));
        setLoading(false);
      },
    );
    return () => {
      live = false;
    };
  }, [open, needFull, attempt]);
  const items = useMemo(() => (!needFull ? state.items : rows ? itemsWithFullHistory(state, rows) : null), [needFull, rows, state]);
  return { items, loading: needFull && !rows && loading, error: needFull && !rows ? error : null, retry: () => setAttempt((n) => n + 1) };
}

export function ShareSheet({ title, transcript, onClose }: { title: string | null; transcript: ShareTranscript; onClose: () => void }) {
  const { t, language, flash } = useApp();
  const items = transcript.items ?? [];

  async function shareText() {
    const text = transcriptText(items, language);
    onClose();
    if (!text) return flash(t("暂无可导出的内容", "Nothing to export yet"));
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: title ?? "Hermes GO", text });
        return;
      } catch (error) {
        if (isAbort(error)) return;
      }
    }
    await copyWithFeedback(text, flash, t("已复制，可粘贴分享", "Copied — paste it anywhere"));
  }

  async function shareMarkdown() {
    const now = Date.now();
    const md = transcriptMarkdown(title, items, language, now);
    onClose();
    if (!md) return flash(t("暂无可导出的内容", "Nothing to export yet"));
    const name = `${transcriptFileBaseName(title, now)}.md`;
    const file = new File([md], name, { type: "text/markdown" });
    if (typeof navigator.canShare === "function" && navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title: title ?? "Hermes GO" });
        return;
      } catch (error) {
        if (isAbort(error)) return;
      }
    }
    const url = URL.createObjectURL(file);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.rel = "noopener";
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
  }

  return (
    <Sheet title={t("分享对话", "Share transcript")} closeLabel={t("关闭", "Close")} onClose={onClose}>
      {transcript.loading ? (
        <div class="older-history" role="status">
          <span class="spinner tiny" aria-hidden="true" />
          {t("正在读取完整对话…", "Loading the whole conversation…")}
        </div>
      ) : null}
      {transcript.error ? <ErrorNotice error={transcript.error} language={language} onRetry={transcript.retry} variant="inline" /> : null}
      <SheetAction label={t("文字", "Plain text")} hint={t("系统分享，或复制到剪贴板", "System share, or copy to the clipboard")} disabled={!transcript.items} onClick={() => void shareText()} />
      <SheetAction label={t("Markdown 文件", "Markdown file")} hint={t("保留代码块、表格和列表", "Keeps code blocks, tables and lists")} disabled={!transcript.items} onClick={() => void shareMarkdown()} />
    </Sheet>
  );
}

// ---- user bubble menu -----------------------------------------------------------------------

export function UserMenuSheet({ item, onEdit, onViewSource, onClose }: { item: ChatItem; onEdit: () => void; onViewSource: () => void; onClose: () => void }) {
  const { t, flash } = useApp();
  return (
    <Sheet closeLabel={t("关闭", "Close")} onClose={onClose}>
      <SheetAction
        label={t("复制", "Copy")}
        onClick={() => {
          onClose();
          void copyWithFeedback(item.text, flash, t("已复制", "Copied"));
        }}
      />
      <SheetAction
        label={t("编辑并重新发送", "Edit & resend")}
        onClick={() => {
          onClose();
          onEdit();
        }}
      />
      {item.send === "failed" && item.error ? (
        <SheetAction
          label={t("复制诊断信息", "Copy diagnostics")}
          onClick={() => {
            onClose();
            void copyText(diagnostics(item.error!)).then(
              () => flash(t("已复制", "Copied")),
              () => flash(appError("HR-WEB-007")),
            );
          }}
        />
      ) : null}
      <SheetAction
        label={t("查看原文 / 选择", "View source / Select")}
        onClick={() => {
          onClose();
          onViewSource();
        }}
      />
    </Sheet>
  );
}

// ---- view source / select (TextSelectionDialog) ---------------------------------------------

/** Readable text by default (marks gone, structure kept); "show source" gives the Markdown. */
export function SourceDialog({ item, onClose }: { item: ChatItem; onClose: () => void }) {
  const { t } = useApp();
  const markdown = item.role === "assistant";
  const [source, setSource] = useState(false);
  const readable = useMemo(() => (markdown ? readableText(item.text) : item.text), [item.text, markdown]);
  useBackClose(onClose);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div class="source-dialog" role="dialog" aria-modal="true" aria-label={t("查看原文 / 选择", "View source / Select")}>
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("关闭", "Close")} onClick={onClose}>
            <CloseIcon />
          </button>
          <h1 class="topbar-title left">{source ? t("原文", "Source") : t("选择文字", "Select text")}</h1>
          {markdown ? (
            <button type="button" class="text-button" onClick={() => setSource(!source)}>
              {source ? t("显示正文", "Show text") : t("显示原文", "Show source")}
            </button>
          ) : (
            <span class="topbar-spacer" />
          )}
        </div>
      </header>
      <pre class={`source-body${source ? " mono" : ""}`}>{source ? item.text : readable}</pre>
    </div>
  );
}
