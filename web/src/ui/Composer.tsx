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
import { AttachIcon, CloseIcon, FileIcon, SendIcon, StopIcon } from "./icons";

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
}

const finePointer = () => typeof matchMedia === "function" && matchMedia("(hover: hover) and (pointer: fine)").matches;

let seq = 0;

const DRAFT_DEBOUNCE_MS = 400;

export function Composer({ t, language, generating, disabled, onSend, onInterrupt, draftKey = null, seed = null }: ComposerProps) {
  const [text, setText] = useState(() => (draftKey ? loadDraft(draftKey) : ""));
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [problem, setProblem] = useState<AppError | null>(null);
  const [preparing, setPreparing] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);
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
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
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

  return (
    <div class="composer-wrap">
      {problem ? <ErrorNotice error={problem} language={language} onDismiss={() => setProblem(null)} variant="inline" /> : null}
      {attachments.length ? (
        <div class="attachment-strip">
          {attachments.map((a) => (
            <div class="attachment-chip" key={a.id}>
              {a.previewUrl ? <img src={a.previewUrl} alt={a.name} /> : <span class="attachment-file"><FileIcon size={16} />{a.name}</span>}
              <button type="button" class="chip-remove" aria-label={t("移除附件", "Remove attachment")} onClick={() => remove(a.id)}>
                <CloseIcon size={14} />
              </button>
            </div>
          ))}
        </div>
      ) : null}
      <div class="composer">
        <button
          type="button"
          class="icon-button"
          aria-label={t("添加附件", "Attach")}
          disabled={disabled || attachments.length >= MAX_ATTACHMENTS}
          onClick={() => picker.current?.click()}
        >
          <AttachIcon />
        </button>
        <input
          ref={picker}
          class="visually-hidden"
          type="file"
          multiple
          tabIndex={-1}
          onChange={(e) => {
            const input = e.target as HTMLInputElement;
            void addFiles(input.files);
            input.value = "";
          }}
        />
        <textarea
          ref={area}
          class="composer-input"
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={t("给 Hermes 发消息", "Message Hermes")}
          enterkeyhint="send"
          onInput={(e) => setText((e.target as HTMLTextAreaElement).value)}
          onKeyDown={(e) => {
            if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
            if (!finePointer()) return;
            e.preventDefault();
            send();
          }}
        />
        {generating && !canSend ? (
          <button type="button" class="send-button stop" aria-label={t("停止", "Stop")} onClick={onInterrupt}>
            <StopIcon />
          </button>
        ) : (
          <button type="button" class="send-button" aria-label={t("发送", "Send")} disabled={!canSend} onClick={send}>
            {preparing ? <span class="spinner tiny" aria-hidden="true" /> : <SendIcon />}
          </button>
        )}
      </div>
    </div>
  );
}
