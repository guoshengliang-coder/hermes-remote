import { useEffect, useRef, useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import {
  attachmentKind,
  checkAttachments,
  MAX_ATTACHMENTS,
  prepareImage,
  type PendingAttachment,
} from "../chat/attachments";
import { useDraftText } from "../app/useDraftText";
import { appError, type AppError, type Language } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { useApp } from "../app/store";
import { explicitProfile } from "../app/profile";
import { historyItems } from "../chat/model";
import { fetchFullHistory } from "../chat/history";
import { transcriptAttachmentName, transcriptMarkdownForAttachment } from "../chat/transcript";
import type { SessionListItem } from "../hermes/types";
import { CameraIcon, KeyboardIcon, MicIcon, ChatIcon, CloseIcon, FileIcon, ImageIcon, ListIcon, PlusIcon, SendIcon, StopIcon } from "./icons";
import { PromptLibrary, SavedPromptsSheet, SessionPicker } from "./ComposerSheets";
import { Sheet } from "./Sheet";
import { paths } from "../api/gateway";
import { allowMicrophone, voiceCaptureSupported } from "../chat/voiceCapture";
import { BrowserVoiceSession } from "../chat/voiceSession";
import { appendVoiceText, type VoiceAction } from "../chat/voiceGesture";
import { VoiceComposer } from "./VoiceComposer";
import { useBackClose } from "../app/useBackClose";

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
  /** Voice requires a live conversation socket; reconnecting cannot auto-send speech. */
  voiceReady?: boolean;
  /** The open conversation, left out of 「添加会话」. */
  sessionId?: string | null;
  /** Tapping an image chip (preview / edit / remove). */
  onOpenAttachment?: (attachment: PendingAttachment, replace: (next: PendingAttachment) => void, remove: () => void) => void;
}

/** Generated transcript attachments keep their own 6 MiB cap (SESSION_EXCHANGE §4.2). */
const MAX_TRANSCRIPT_BYTES = 6 * 1024 * 1024;

const finePointer = () => typeof matchMedia === "function" && matchMedia("(hover: hover) and (pointer: fine)").matches;

let seq = 0;

export function Composer({ t, language, generating, disabled, onSend, onInterrupt, draftKey = null, seed = null, chip = null, blocked = null, sessionId = null, voiceReady = true, onOpenAttachment }: ComposerProps) {
  const app = useApp();
  const [sheet, setSheet] = useState<"add" | "prompts" | "library" | "picker" | null>(null);
  const [generatingCount, setGeneratingCount] = useState(0);
  const camera = useRef<HTMLInputElement>(null);
  const photos = useRef<HTMLInputElement>(null);
  const [text, setText, flushDraft] = useDraftText(draftKey);
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [problem, setProblem] = useState<AppError | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [focused, setFocused] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);
  const composer = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  // HG-174: set while the pointer is inside the composer's own controls. Safari, Firefox and iOS
  // never move focus onto a pressed button, so the textarea's blur then arrives with no
  // relatedTarget — the same shape as a tap on the transcript. The pointerdown is what tells the
  // two apart; this flag is consumed by the blur it belongs to.
  const pressedInside = useRef(false);

  const [voiceMode, setVoiceMode] = useState(false);
  const [voicePhase, setVoicePhase] = useState<"idle" | "held" | "waiting">("idle");
  const [voiceText, setVoiceText] = useState("");
  const [requestingMic, setRequestingMic] = useState(false);
  const voice = useRef<BrowserVoiceSession | null>(null);
  const permissionEpoch = useRef(0);
  const intent = useRef<VoiceAction>("send");
  const latest = useRef({ disabled, blocked, generating, voiceReady, onSend });
  latest.current = { disabled, blocked, generating, voiceReady, onSend };
  const voiceAvailable = app.features.has("voice-input") && voiceCaptureSupported();
  function collapse() { area.current?.blur(); setFocused(false); }
  function cancelVoice(keep = false) {
    const partial = voice.current?.cancel() ?? "";
    voice.current = null;
    setVoicePhase("idle"); setVoiceText("");
    if (keep && partial.trim()) { setText((draft) => appendVoiceText(draft, partial)); setVoiceMode(false); setFocused(true); }
  }
  async function enterVoice() {
    setRequestingMic(true);
    const epoch = ++permissionEpoch.current;
    // getUserMedia may remain unanswered forever: release the UI after ten seconds. Any late
    // permission result still stops its tracks, and cannot switch a new conversation into voice.
    const timeout = setTimeout(() => {
      if (permissionEpoch.current === epoch) { permissionEpoch.current++; setRequestingMic(false); setProblem(appError("HR-PERM-006")); }
    }, 10_000);
    try {
      await allowMicrophone();
      // Ask first, move after (HG-174). Collapsing before the grant left the two-row keyboard
      // composer on screen for the whole permission round trip, and a layout change between the
      // press and its click unmounts the button the finger is still on. Collapse and switch in one
      // step instead — the Android composer this mirrors applies collapseComposer() and voiceMode
      // together — so a denial leaves the composer exactly as the user had it.
      if (permissionEpoch.current === epoch) { collapse(); setVoiceMode(true); setProblem(null); }
    } catch {
      if (permissionEpoch.current === epoch) setProblem(appError("HR-PERM-006"));
    } finally {
      clearTimeout(timeout);
      if (permissionEpoch.current === epoch) setRequestingMic(false);
    }
  }
  function beginVoice() {
    if (voice.current || disabled || blocked || generating || !voiceReady || !app.device) return;
    intent.current = "send"; setVoiceText(""); setVoicePhase("held"); setProblem(null);
    const deviceId = app.device.deviceId;
    const recording = new BrowserVoiceSession({
      endpoint: async () => { await app.client.settled(); return paths.deviceVoice(deviceId); },
      onEvent: (event) => {
        if (voice.current !== recording) return;
        if (event.kind === "partial") { setVoiceText(event.text); return; }
        if (event.kind === "waiting") { setVoicePhase("waiting"); return; }
        voice.current = null; setVoicePhase("idle"); setVoiceText("");
        const state = latest.current;
        if (event.kind === "failed" || intent.current === "edit" || state.disabled || state.blocked || state.generating || !state.voiceReady) {
          if (event.text.trim()) { setText((draft) => appendVoiceText(draft, event.text)); setVoiceMode(false); setFocused(true); }
          if (event.kind === "failed") setProblem(event.error);
        } else {
          // A speech message is its own prompt; existing text and attachment drafts stay intact.
          state.onSend(event.text, []); collapse();
        }
      },
    });
    voice.current = recording; recording.start();
  }
  function releaseVoice(action: VoiceAction) {
    if (action === "cancel") { cancelVoice(); return; }
    intent.current = action; voice.current?.finish();
  }
  useEffect(() => {
    if (disabled || blocked || generating || !voiceReady) cancelVoice(true);
  }, [disabled, blocked, generating, voiceReady]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState === "hidden") { cancelVoice(true); flushDraft(); } };
    document.addEventListener("visibilitychange", hide);
    return () => { document.removeEventListener("visibilitychange", hide); permissionEpoch.current++; voice.current?.cancel(); voice.current = null; };
  }, []);
  // Back leaves the conversation in two steps (HG-180). A focused text field owns a back step of
  // its own — the first back exits the input and returns to the browsing state; only the next back
  // leaves the chat for the list/project/archive page it was opened from. An in-progress voice
  // capture owns the step too, so back first cancels the recording and keeps the partial text.
  // A sheet on top has its own step and is closed first, so the composer stands down while one is
  // open (`!sheet`).
  useBackClose(() => { if (voice.current) cancelVoice(true); else collapse(); }, (focused || voicePhase !== "idle") && !sheet);

  // A different conversation brings its own draft.
  const keyRef = useRef(draftKey);
  useEffect(() => {
    if (keyRef.current === draftKey) return;
    keyRef.current = draftKey;
    cancelVoice(); permissionEpoch.current++; setRequestingMic(false); setVoiceMode(false); setFocused(false);
    setAttachments((current) => { current.forEach((a) => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl); }); return []; });
    setProblem(null);
  }, [draftKey]);

  useEffect(() => {
    if (!seed) return;
    setText(seed.text);
    area.current?.focus();
  }, [seed?.nonce]);

  useEffect(() => {
    if (focused && !voiceMode) area.current?.focus();
  }, [focused, voiceMode]);

  useEffect(() => {
    const el = area.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, focused ? 168 : 96)}px`;
  }, [text, focused]);

  async function addFiles(list: FileList | null) {
    if (!list || !list.length) return;
    const { accepted, rejected } = checkAttachments(attachments.length, [...list]);
    if (rejected.length) {
      const why = rejected.map((r) => `${r.name}: ${r.problem}`).join("; ");
      const oversizedFile = rejected.some((r) => r.problem === "too-large" && [...list].some((f) => f.name === r.name && attachmentKind(f.type) === "file"));
      setProblem(appError(oversizedFile ? "HR-FILE-008" : "HR-WEB-006", why));
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
    flushDraft();
    setAttachments([]); // preview URLs now belong to the sent bubble
    setProblem(null);
    collapse();
  }

  if (blocked) return <div class="composer-wrap">{blocked}</div>;

  const full = attachments.length >= MAX_ATTACHMENTS;
  const addButton = () => (
    <button type="button" class="icon-button composer-add" aria-label={t("添加内容", "Add content")} disabled={disabled} onClick={() => { collapse(); setSheet("add"); }}>
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
      {problem ? <ErrorNotice error={problem} language={language} onRetry={problem.code === "HR-PERM-006" ? () => void enterVoice() : problem.code.startsWith("HR-VOICE-") ? () => { setProblem(null); setVoiceMode(true); } : undefined} onDismiss={() => setProblem(null)} variant="inline" /> : null}
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
      <div
        ref={composer}
        class={`composer${focused && !voiceMode ? " expanded" : ""}${voiceMode ? " voice-mode" : ""}`}
        onPointerDown={() => { pressedInside.current = true; }}
      >
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
        {voiceMode ? <>
          <button type="button" class="icon-button composer-voice" aria-label={t("切换键盘输入", "Switch to keyboard")} disabled={voicePhase !== "idle"} onClick={() => { cancelVoice(); setVoiceMode(false); setFocused(true); area.current?.focus(); }}><KeyboardIcon /></button>
          <VoiceComposer t={t} phase={voicePhase} text={voiceText} disabled={disabled || generating || !voiceReady || preparing} onBegin={beginVoice} onRelease={releaseVoice} onCancelWait={() => cancelVoice(true)} />
          {generating ? sendOrStop() : addButton()}
        </> : <>
        {!focused && voiceAvailable ? <button type="button" class="icon-button composer-voice" aria-label={t("切换语音输入", "Switch to voice input")} disabled={disabled || requestingMic} onClick={() => void enterVoice()}>{requestingMic ? <span class="spinner tiny" /> : <MicIcon />}</button> : null}
        <textarea
          ref={area}
          class="composer-input"
          rows={1}
          value={text}
          disabled={disabled}
          placeholder={t("输入消息…", "Type a message…")}
          enterkeyhint={finePointer() ? "send" : "enter"}
          onFocus={() => setFocused(true)}
          onBlur={(e) => {
            // HG-174: a press on one of our own controls must not tear the composer down under
            // the finger. Collapsing here unmounts the pressed button before its click fires (the
            // focused and expanded layouts are different elements), so the tap would do nothing
            // but collapse. That control's own handler decides the move: the voice button switches
            // input mode, the model chip and add button collapse and open their sheet.
            const target = e.relatedTarget as Node | null;
            const ownPress = pressedInside.current;
            pressedInside.current = false;
            if (composer.current?.contains(target)) return;
            if (target === null && ownPress) return;
            setFocused(false);
          }}
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
            {voiceAvailable ? <button type="button" class="icon-button composer-voice" aria-label={t("切换语音输入", "Switch to voice input")} disabled={disabled || requestingMic} onClick={() => void enterVoice()}>{requestingMic ? <span class="spinner tiny" /> : <MicIcon />}</button> : null}
            {chip ? (
              <button type="button" class="model-chip mono" onClick={() => { collapse(); chip.onClick(); }} aria-label={t(`模型：${chip.label}`, `Model: ${chip.label}`)}>
                {chip.label}<span aria-hidden="true">⌄</span>
              </button>
            ) : <span class="composer-action-spacer" />}
            {addButton()}
            {sendOrStop()}
          </div>
        ) : generating || canSend || text.trim() || attachments.length ? sendOrStop() : addButton()}
        </>}
      </div>
      <p class="composer-disclaimer">{t("内容由 AI 生成", "Content generated by AI")}</p>
    </div>
  );
}
