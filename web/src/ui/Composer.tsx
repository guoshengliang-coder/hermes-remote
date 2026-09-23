import { useEffect, useRef, useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import {
  attachmentKind,
  checkAttachments,
  MAX_ATTACHMENTS,
  prepareImage,
  type PendingAttachment,
} from "../chat/attachments";
import { loadDraft, saveDraft } from "../app/drafts";
import { appError, type AppError, type Language } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { useApp } from "../app/store";
import { explicitProfile } from "../app/profile";
import { historyItems } from "../chat/model";
import { fetchFullHistory } from "../chat/history";
import { transcriptAttachmentName, transcriptMarkdownForAttachment } from "../chat/transcript";
import type { SessionListItem } from "../hermes/types";
import { CameraIcon, ChatIcon, CloseIcon, FileIcon, ImageIcon, ListIcon, PlusIcon, SendIcon, StopIcon } from "./icons";
import { PromptLibrary, SavedPromptsSheet, SessionPicker } from "./ComposerSheets";
import { Sheet } from "./Sheet";

// Bottom composer: autosizing textarea, attachments, send / stop. Enter sends on a desktop
// keyboard (Shift+Enter is a newline); on touch devices only the button sends. IME composition
// never sends.

export interface ComposerProps {
  t: Translate;
  language: Language;
  generating: boolean;
  disabled: boolean;
  onSend: (text: string, attachments: PendingAttachment[]) => void;
  onInterrupt: () => void;
  /** Where unsent text is kept (app/drafts.ts); null keeps it in memory only. */
  draftKey?: string | null;
  /** Replace the text, e.g. "edit & resend"; a new nonce applies it again. */
  seed?: { text: string; nonce: number } | null;
  /** The model chip (`model · effort`), shown when the Gateway admits model selection. */
  chip?: { label: string; onClick: () => void } | null;
  /** Replaces the input: this conversation is running in another client (HR-SESS-013). */
  blocked?: preact.ComponentChildren;
  /** The open conversation, left out of 「添加会话」. */
  sessionId?: string | null;
  /** Tapping an image chip (preview / edit / remove). */
  onOpenAttachment?: (attachment: PendingAttachment, replace: (next: PendingAttachment) => void, remove: () => void) => void;
}

/** A transcript attachment is capped like any direct attachment (6 MB, SESSION_EXCHANGE §4.2). */
const MAX_TRANSCRIPT_BYTES = 6 * 1024 * 1024;

const finePointer = () => typeof matchMedia === "function" && matchMedia("(hover: hover) and (pointer: fine)").matches;

let seq = 0;

const DRAFT_DEBOUNCE_MS = 400;

export function Composer({ t, language, generating, disabled, onSend, onInterrupt, draftKey = null, seed = null, chip = null, blocked = null, sessionId = null, onOpenAttachment }: ComposerProps) {
  const app = useApp();
  const [sheet, setSheet] = useState<"add" | "prompts" | "library" | "picker" | null>(null);
  const [generatingCount, setGeneratingCount] = useState(0);
  const camera = useRef<HTMLInputElement>(null);
  const photos = useRef<HTMLInputElement>(null);
  const [text, setText] = useState(() => (draftKey ? loadDraft(draftKey) : ""));
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [problem, setProblem] = useState<AppError | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [focused, setFocused] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);
  const composer = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);

  // A different conversation brings its own draft.
  const keyRef = useRef(draftKey);
  useEffect(() => {
    if (keyRef.current === draftKey) return;
    keyRef.current = draftKey;
    setText(draftKey ? loadDraft(draftKey) : "");
  }, [draftKey]);

  useEffect(() => {
    if (!draftKey) return;
    const timer = setTimeout(() => saveDraft(draftKey, text), DRAFT_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [text, draftKey]);

  useEffect(() => {
    if (!seed) return;
    setText(seed.text);
    area.current?.focus();
  }, [seed?.nonce]);

  useEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
  }, [text]);

  async function addFiles(list: FileList | null) {
    if (!list || !list.length) return;
    const { accepted, rejected } = checkAttachments(attachments.length, [...list]);
    if (rejected.length) {
      const why = rejected.map((r) => `${r.name}: ${r.problem}`).join("; ");
      setProblem(appError("HR-WEB-006", `${why} (max ${MAX_ATTACHMENTS} files, 6 MB each)`));
    } else {
      setProblem(null);
    }
    setPreparing(true);
    const prepared: PendingAttachment[] = [];
    for (const file of accepted) {
      const kind = attachmentKind(file.type);
      if (kind === "image") {
        const image = await prepareImage(file);
        if (image.blob.size > 6 * 1024 * 1024) {
          setProblem(appError("HR-WEB-006", `${file.name}: too-large after re-encoding`));
          continue;
        }
        prepared.push({ id: `att-${++seq}`, file: image.blob, name: image.name, mimeType: image.mimeType, kind, previewUrl: URL.createObjectURL(image.blob) });
      } else {
        prepared.push({ id: `att-${++seq}`, file, name: file.name, mimeType: file.type || "application/octet-stream", kind });
      }
    }
    setAttachments((current) => [...current, ...prepared].slice(0, MAX_ATTACHMENTS));
    setPreparing(false);
  }

  /** Each picked conversation becomes its own Markdown chip, all placed together (HG-38). */
  async function attachConversations(picked: SessionListItem[]) {
    setSheet(null);
    const device = app.device;
    if (!device) return;
    setGeneratingCount(picked.length);
    const now = Date.now();
    const made: PendingAttachment[] = [];
    const failed: string[] = [];
    for (const session of picked) {
      const title = session.title || session.display_name || null;
      try {
        // The whole transcript, not just the newest page the chat itself opens on (HG-104).
        const rows = await fetchFullHistory((page) => app.client.messages(device.deviceId, session.id, explicitProfile(session), page));
        const markdown = transcriptMarkdownForAttachment(title, historyItems(rows), language, now, MAX_TRANSCRIPT_BYTES);
        if (!markdown) throw new Error("nothing to attach");
        const name = transcriptAttachmentName(title, now);
        made.push({ id: `att-${++seq}`, file: new Blob([markdown], { type: "text/markdown" }), name, mimeType: "text/markdown", kind: "file" });
      } catch (e) {
        failed.push(`${title ?? session.id}: ${e instanceof Error ? e.message : String(e)}`);
      }
    }
    setAttachments((current) => [...current, ...made].slice(0, MAX_ATTACHMENTS));
    setGeneratingCount(0);
    if (failed.length) setProblem(appError("HR-SESS-014", `${failed.length} of ${picked.length} failed — ${failed.join("; ")}`));
  }

  function insertPrompt(body: string) {
    setSheet(null);
    setText((current) => (current.trim() ? `${current.trimEnd()}\n${body}` : body));
    area.current?.focus();
  }

  function remove(id: string) {
    setAttachments((current) => {
      const gone = current.find((a) => a.id === id);
      if (gone?.previewUrl) URL.revokeObjectURL(gone.previewUrl);
      return current.filter((a) => a.id !== id);
    });
  }

  const canSend = !disabled && !preparing && (text.trim() !== "" || attachments.length > 0);

  function send() {
    if (!canSend) return;
    onSend(text.trim(), attachments);
    setText("");
    if (draftKey) saveDraft(draftKey, "");
    setAttachments([]); // preview URLs now belong to the sent bubble
    setProblem(null);
  }

  if (blocked) return <div class="composer-wrap">{blocked}</div>;

  const full = attachments.length >= MAX_ATTACHMENTS;
  const addButton = () => (
    <button type="button" class="icon-button composer-add" aria-label={t("添加内容", "Add content")} disabled={disabled} onClick={() => { setFocused(false); setSheet("add"); }}>
      <PlusIcon />
    </button>
  );
  const sendOrStop = () => generating ? (
    <button type="button" class="send-button stop" aria-label={t("停止", "Stop")} onClick={onInterrupt}><StopIcon /></button>
  ) : (
    <button type="button" class="send-button" aria-label={t("发送", "Send")} disabled={!canSend} onClick={send}>
      {preparing ? <span class="spinner tiny" aria-hidden="true" /> : <SendIcon />}
    </button>
  );
  const sheets = (
    <>
      {sheet === "add" ? (
        <Sheet title={t("添加内容", "Add content")} closeLabel={t("关闭", "Close")} onClose={() => setSheet(null)}>
          <div class="add-tiles">
            {[
              { label: t("拍照", "Camera"), icon: <CameraIcon />, ref: camera },
              { label: t("照片", "Photos"), icon: <ImageIcon />, ref: photos },
              { label: t("文件", "Files"), icon: <FileIcon />, ref: picker },
            ].map((tile) => (
              <button
                type="button"
                class="add-tile"
                key={tile.label}
                disabled={full}
                onClick={() => {
                  setSheet(null);
                  tile.ref.current?.click();
                }}
              >
                {tile.icon}
                <span>{tile.label}</span>
              </button>
            ))}
          </div>
          <button type="button" class="add-row" onClick={() => setSheet("prompts")}>
            <ListIcon />
            <span class="add-row-text">
              <span class="add-row-title">{t("常用提示", "Saved prompts")}</span>
              <span class="add-row-sub">{t("插入一条已保存的提示词", "Insert a saved prompt")}</span>
            </span>
          </button>
          <button type="button" class="add-row" disabled={full} onClick={() => setSheet("picker")}>
            <ChatIcon />
            <span class="add-row-text">
              <span class="add-row-title">{t("添加会话", "Add conversations")}</span>
              <span class="add-row-sub">{t("把已有对话转成 Markdown 一起发出", "Send existing conversations along as Markdown")}</span>
            </span>
          </button>
        </Sheet>
      ) : null}
      {sheet === "prompts" ? <SavedPromptsSheet onPick={insertPrompt} onManage={() => setSheet("library")} onClose={() => setSheet(null)} /> : null}
      {sheet === "library" ? <PromptLibrary onClose={() => setSheet("prompts")} /> : null}
      {sheet === "picker" ? (
        <SessionPicker excludeId={sessionId} slots={MAX_ATTACHMENTS - attachments.length} onDone={(picked) => void attachConversations(picked)} onClose={() => setSheet(null)} />
      ) : null}
    </>
  );

  return (
    <div class="composer-wrap">
      {sheets}
      {problem ? <ErrorNotice error={problem} language={language} onDismiss={() => setProblem(null)} variant="inline" /> : null}
      {attachments.length ? (
        <div class="attachment-strip">
          {attachments.map((a) => (
            <div class="attachment-chip" key={a.id}>
              {a.previewUrl ? (
                <button
                  type="button"
                  class="chip-open"
                  aria-label={t(`预览图片 ${a.name}`, `Preview image ${a.name}`)}
                  onClick={() =>
                    onOpenAttachment?.(
                      a,
                      (next) => {
                        // Same id, same place in the strip (upload order is the strip order).
                        if (a.previewUrl && a.previewUrl !== next.previewUrl) URL.revokeObjectURL(a.previewUrl);
                        setAttachments((current) => current.map((x) => (x.id === a.id ? next : x)));
                      },
                      () => remove(a.id),
                    )
                  }
                >
                  <img src={a.previewUrl} alt={a.name} />
                </button>
              ) : (
                <span class="attachment-file"><FileIcon size={16} />{a.name}</span>
              )}
              <button type="button" class="chip-remove" aria-label={t("移除附件", "Remove attachment")} onClick={() => remove(a.id)}>
                <CloseIcon size={14} />
              </button>
            </div>
          ))}
        </div>
      ) : null}
      {generatingCount ? (
        <p class="composer-progress" role="status">
          <span class="spinner tiny" aria-hidden="true" />
          {t(`正在生成 ${generatingCount} 份对话记录…`, `Preparing ${generatingCount} transcript${generatingCount === 1 ? "" : "s"}…`)}
        </p>
      ) : null}
      <div ref={composer} class={`composer${focused ? " expanded" : ""}`}>
        {[
          { ref: camera, accept: "image/*", capture: "environment" as const, multiple: false },
          { ref: photos, accept: "image/*", capture: undefined, multiple: true },
          { ref: picker, accept: undefined, capture: undefined, multiple: true },
        ].map((input, i) => (
          <input
            key={i}
            ref={input.ref}
            class="visually-hidden"
            type="file"
            accept={input.accept}
            capture={input.capture}
            multiple={input.multiple}
            tabIndex={-1}
            onChange={(e) => {
              const el = e.target as HTMLInputElement;
              void addFiles(el.files);
              el.value = "";
            }}
          />
        ))}
        <textarea
          ref={area}
          class="composer-input"
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={focused ? t("输入消息…", "Type a message…") : t("发消息", "Message")}
          enterkeyhint="send"
          onFocus={() => setFocused(true)}
          onBlur={(e) => { if (!composer.current?.contains(e.relatedTarget as Node | null)) setFocused(false); }}
          onInput={(e) => setText((e.target as HTMLTextAreaElement).value)}
          onKeyDown={(e) => {
            if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
            if (!finePointer()) return;
            e.preventDefault();
            send();
          }}
        />
        {focused ? (
          <div class="composer-actions">
            {chip ? (
              <button type="button" class="model-chip mono" onClick={() => { setFocused(false); chip.onClick(); }} aria-label={t(`模型：${chip.label}`, `Model: ${chip.label}`)}>
                {chip.label}<span aria-hidden="true">⌄</span>
              </button>
            ) : <span class="composer-action-spacer" />}
            {addButton()}
            {sendOrStop()}
          </div>
        ) : generating || canSend || text.trim() || attachments.length ? sendOrStop() : addButton()}
      </div>
      <p class="composer-disclaimer">{t("内容由 AI 生成", "Content generated by AI")}</p>
    </div>
  );
}
