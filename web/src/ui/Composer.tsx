import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import {
  attachmentKind,
  checkAttachments,
  MAX_ATTACHMENTS,
  prepareImage,
  type PendingAttachment,
} from "../chat/attachments";
import { useDraftText } from "../app/useDraftText";
import { useAttachmentDraft } from "../app/conversationMemory";
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
import { VoiceJournal, VoiceRecordingStore, voiceRecordingActive, type VoiceRecording, type VoiceDeliveryReceipt } from "../chat/voiceRecordings";
import { draftEpoch, loadDraft } from "../app/drafts";
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
  onSend: (text: string, attachments: PendingAttachment[], onDelivered?: VoiceDeliveryReceipt) => void;
  onInterrupt: () => void;
  /** Where unsent text is kept (app/drafts.ts); null keeps it in memory only. */
  draftKey?: string | null;
  /** Replace the text, e.g. "edit & resend"; a new nonce applies it again. */
  seed?: { text: string; nonce: number; append?: boolean; attachments?: PendingAttachment[] } | null;
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
  const attachmentDraft = useAttachmentDraft(draftKey);
  const { attachments, set: setAttachments } = attachmentDraft;
  const [problem, setProblem] = useState<AppError | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [focused, setFocused] = useState(false);
  const area = useRef<HTMLTextAreaElement>(null);
  const composer = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  // iOS maps data to the document picker directly; an unrestricted input also offers Photos.
  // iPadOS can identify as a desktop Mac. This changes the picker hint, never the file's MIME type.
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  // HG-174: set while the pointer is inside the composer's own controls. Safari, Firefox and iOS
  // never move focus onto a pressed button, so the textarea's blur then arrives with no
  // relatedTarget — the same shape as a tap on the transcript. The pointerdown is what tells the
  // two apart; this flag is consumed by the blur it belongs to.
  const pressedInside = useRef(false);

  const [voiceMode, setVoiceMode] = useState(false);
  const [voicePhase, setVoicePhase] = useState<"idle" | "held" | "waiting">("idle");
  const [voiceRecording, setVoiceRecording] = useState(false);
  const [voiceLimited, setVoiceLimited] = useState(false);
  const [voiceText, setVoiceText] = useState("");
  const [voiceNotice, setVoiceNotice] = useState("");
  const [requestingMic, setRequestingMic] = useState(false);
  const voice = useRef<BrowserVoiceSession | null>(null);
  const voiceScope = draftKey ?? JSON.stringify([location.origin, app.account?.id ?? "", app.device?.deviceId ?? "", sessionId]);
  const voiceOwner = useRef(voiceScope); voiceOwner.current = voiceScope;
  const recordingStore = useMemo(() => new VoiceRecordingStore(), [voiceScope]);
  const [pendingVoice, setPendingVoice] = useState<(VoiceRecording & { restored?: boolean }) | null>(null);
  const [voiceSettling, setVoiceSettling] = useState(false);
  const [voiceRecoveryLoaded, setVoiceRecoveryLoaded] = useState(false);
  const journal = useRef<VoiceJournal | null>(null);
  const interrupted = useRef<(() => void) | null>(null);
  useEffect(() => {
    let active = true;
    setPendingVoice(null);
    setVoiceRecoveryLoaded(false);
    if (typeof indexedDB !== "undefined") void recordingStore.list(voiceScope).then(records => {
      if (active) setPendingVoice(records[0] ? { ...records[0], restored: true } : null);
    }).catch(() => {}).finally(() => { if (active) setVoiceRecoveryLoaded(true); });
    else setVoiceRecoveryLoaded(true);
    const timer = setInterval(() => {
      if (active && !voice.current) void recordingStore.list(voiceScope).then(records => {
        if (active) setPendingVoice(current => { const next = current && records.find(r => r.id === current.id); return next ? { ...next, restored: current?.restored } : null; });
      }).catch(() => {});
    }, 5_000);
    return () => { active = false; clearInterval(timer); };
  }, [voiceScope, recordingStore]);
  const permissionEpoch = useRef(0);
  const intent = useRef<VoiceAction>("send");
  const latest = useRef({ disabled, blocked, generating, voiceReady, onSend, text, attachments, preparing, generatingCount });
  latest.current = { disabled, blocked, generating, voiceReady, onSend, text, attachments, preparing, generatingCount };
  const voiceAvailable = app.features.has("voice-input") && voiceCaptureSupported();
  function collapse() { area.current?.blur(); setFocused(false); }
  function retainDisabledActionPress(event: Event) {
    if (event.target instanceof Element && event.target.closest('.send-button:disabled')) {
      // WebKit can synthesize a compatibility click on the nearest enabled control (Add).
      // An inert action must neither blur the draft nor be retargeted to its neighbour.
      event.preventDefault();
      // Default prevention keeps focus here; do not leave a flag that could swallow a later
      // unrelated/programmatic blur after the inert press has ended.
      pressedInside.current = false;
    }
  }
  function cancelVoice(keep = false) {
    if (keep && voice.current && interrupted.current) { interrupted.current(); return; }
    const partial = voice.current?.cancel() ?? "";
    voice.current = null;
    if (journal.current) void journal.current.discard().catch(() => {});
    journal.current = null; interrupted.current = null;
    setVoicePhase("idle"); setVoiceRecording(false); setVoiceText("");
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
    if (voice.current || !voiceRecoveryLoaded || voiceSettling || pendingVoice || disabled || blocked || generating || !voiceReady || preparing || generatingCount > 0 || !app.device) return;
    runVoice();
  }
  function runVoice(saved?: VoiceRecording, replay?: Uint8Array[], action: VoiceAction = "send") {
    if (voice.current || blocked || !app.device) return;
    const owner = voiceScope, epoch = draftEpoch(), before = latest.current.text;
    const store = recordingStore;
    const owns = () => voiceOwner.current === owner && epoch === draftEpoch();
    intent.current = action;
    if (saved) setVoiceMode(true);
    setVoiceText(""); setVoiceRecording(false); setVoiceLimited(false); setVoicePhase(saved ? "waiting" : "held"); setProblem(null); setVoiceNotice("");
    let writer: VoiceJournal | null = null;
    let limited = false;
    let finishing = false;
    const deviceId = app.device.deviceId;
    const settle = (event: Extract<import("../chat/voiceSession").VoiceEvent, { kind: "failed" | "final" }>, record: VoiceRecording | null) => {
      if (!owns()) return;
      setVoiceSettling(false); setVoicePhase("idle"); setVoiceRecording(false); setVoiceText("");
      if (event.kind === "failed") {
        if (record) setPendingVoice(record);
        else if (event.text.trim()) { setText(draft => appendVoiceText(draft, event.text)); setVoiceMode(false); setFocused(true); }
        setProblem(event.error);
        setVoiceNotice(record ? t("录音已保留，可重试，无需重新说。", "Recording kept. Retry without recording again.") : event.text.trim() ? t("已识别的文字已放入输入框，请检查后发送。", "Recognized text is in the input. Review it before sending.") : t("本次没有可保留的文字。", "There is no text to keep from this recording."));
        return;
      }
      const state = latest.current;
      if (record && !event.text.trim()) {
        setPendingVoice(record); setVoiceMode(false);
        setProblem(appError("HR-VOICE-005"));
        setVoiceNotice(t("没有识别到文字；录音仍保留，可重试转文字。", "No speech was recognized. Audio is kept for another transcription attempt."));
        return;
      }
      let message = appendVoiceText(state.text, event.text);
      const keep = event.limited || intent.current === "edit" || !record?.complete && !!record || state.disabled || state.blocked || state.generating || !state.voiceReady || state.preparing || state.generatingCount > 0 || !!saved && state.text !== before;
      const complete = () => {
        if (!owns()) return;
        const current = latest.current;
        message = appendVoiceText(current.text, event.text);
        const changed = current.text !== state.text || current.disabled || current.blocked || current.generating || !current.voiceReady || current.preparing || current.generatingCount > 0;
        setPendingVoice(null);
        if (keep || changed || !event.text.trim()) {
          if (event.text.trim()) setText(message);
          flushDraft(); setVoiceMode(false); setFocused(true);
          if (event.limited) setVoiceNotice(event.text.trim() ? t("已录满 5 分钟，文字已放入输入框。可继续补充或发送。", "The 5-minute recording limit was reached. Text is in the input; add more or send it.") : t("已录满 5 分钟，但没有识别到文字。请重试或改用键盘。", "The 5-minute recording limit was reached, but no speech was recognized. Retry or use the keyboard."));
          if (record) {
            if (draftKey && loadDraft(draftKey) === message) void store.remove(record.id).catch(() => {});
            else {
              void store.handoff(record.id, message, current.text).catch(() => {});
              setPendingVoice({ ...record, bytes: 0, text: message, baseText: current.text, restored: true }); setProblem(appError("HR-VOICE-006"));
            }
          }
        } else {
          const delivered: VoiceDeliveryReceipt | undefined = record ? () => { void store.remove(record.id).catch(() => {}); } : undefined;
          if (delivered && record && draftKey) delivered.onStored = id => {
            // A lazy new chat acquired its durable ID. Keep an uncertain send recoverable there.
            const parts = draftKey.split("\n");
            if (parts[1] === "new") {
              parts[1] = id;
              const prefix = "\nscope:", at = draftKey.indexOf(prefix);
              if (at >= 0) {
                try {
                  const scope = JSON.parse(draftKey.slice(at + prefix.length)) as unknown[];
                  scope[4] = id; parts[2] = "scope:" + JSON.stringify(scope);
                } catch { return; }
              }
              void store.relocate(record.id, parts.join("\n")).catch(() => {});
            }
          };
          sendContent(message, delivered);
        }
      };
      // First persist a text recovery record. A send failure/reload can never destroy the only copy.
      if (record) {
        setVoiceSettling(true);
        void store.handoff(record.id, message, state.text).then(() => { if (owns()) { setVoiceSettling(false); complete(); } }, () => {
          if (owns()) { setVoiceSettling(false); setPendingVoice(record); setProblem(appError("HR-VOICE-006")); }
        });
      } else complete();
    };
    const recording = new BrowserVoiceSession({
      endpoint: async () => { await app.client.settled(); return paths.deviceVoice(deviceId); },
      ...(saved && replay ? { replay: { chunks: replay, limited: saved.limited } } : {}),
      onAudio: pcm => {
        writer ??= new VoiceJournal(store, owner, () => recording.storageFailed());
        journal.current = writer; writer.append(pcm);
      },
      onEvent: event => {
        if (voice.current !== recording || !owns()) return;
        if (event.kind === "recording") { setVoiceRecording(true); return; }
        if (event.kind === "partial") { setVoiceText(event.text); return; }
        if (event.kind === "degraded") { setVoiceNotice(t("实时识别暂不可用，正在本机录音，可继续说。", "Live recognition is unavailable. Recording locally; keep talking.") + " " + event.error.code); return; }
        if (event.kind === "progress") { setVoiceText(t(`正在重新识别：已发送 ${Math.floor(event.sentMs / 1000)} / ${Math.ceil(event.totalMs / 1000)} 秒音频`, `Recognizing again: ${Math.floor(event.sentMs / 1000)} / ${Math.ceil(event.totalMs / 1000)}s of audio sent`)); return; }
        if (event.kind === "waiting") { finishing = true; limited = event.limited ?? false; setVoicePhase("waiting"); setVoiceRecording(false); setVoiceLimited(event.limited ?? false); return; }
        voice.current = null; journal.current = null; interrupted.current = null;
        if (writer) {
          setVoiceSettling(true);
          void writer.seal(event.kind === "final" || (event.kind === "failed" && !["HR-PERM-006", "HR-VOICE-006"].includes(event.error.code)), limited || event.kind === "final" && !!event.limited)
            .then(record => settle(event, record), () => settle({ kind: "failed", text: event.text, error: appError("HR-VOICE-006") }, null));
        } else settle(event, saved ?? null);
      },
    });
    interrupted.current = () => {
      const partial = recording.cancel();
      voice.current = null; journal.current = null; interrupted.current = null;
      setVoicePhase("idle"); setVoiceRecording(false); setVoiceText("");
      if (writer) {
        setVoiceSettling(true);
        void writer.seal(finishing, limited).then(record => {
          settle({ kind: "failed", text: partial, error: appError("HR-VOICE-003") }, record);
          if (owns()) setProblem(null); // A deliberate pause/system interruption is not a network error.
        }, () => settle({ kind: "failed", text: partial, error: appError("HR-VOICE-006") }, null));
      } else if (saved) { setPendingVoice(saved); setVoiceMode(false); }
      else if (partial.trim()) { setText(draft => appendVoiceText(draft, partial)); setVoiceMode(false); setFocused(true); }
    };
    journal.current = null; voice.current = recording; recording.start();
  }
  async function retryVoice(action: VoiceAction) {
    if (!pendingVoice || voiceRecordingActive(pendingVoice) || voice.current || voiceSettling || blocked) return;
    const saved = pendingVoice, owner = voiceScope, epoch = draftEpoch();
    if (saved.text) {
      const message = latest.current.text === saved.text || latest.current.text === saved.baseText ? saved.text : appendVoiceText(latest.current.text, saved.text);
      setText(message); flushDraft(); setVoiceMode(false); setFocused(true);
      if (draftKey && loadDraft(draftKey) === message) {
        await recordingStore.remove(saved.id).catch(() => {});
        if (voiceOwner.current === owner && epoch === draftEpoch()) setPendingVoice(null);
      } else setProblem(appError("HR-VOICE-006"));
      return;
    }
    setVoiceSettling(true);
    try {
      const chunks = await recordingStore.audio(saved.id);
      if (voiceOwner.current !== owner || epoch !== draftEpoch()) return;
      setVoiceSettling(false);
      // Loading does not turn a restored recording into a send authorization.
      runVoice(saved, chunks, saved.complete && !saved.restored ? action : "edit");
    } catch { if (voiceOwner.current === owner) { setVoiceSettling(false); setProblem(appError("HR-VOICE-006")); } }
  }
  function releaseVoice(action: VoiceAction) {
    if (voicePhase === "waiting") return;
    if (action === "cancel") { cancelVoice(); return; }
    intent.current = action; voice.current?.finish();
  }
  useEffect(() => {
    if (blocked) cancelVoice(true);
  }, [blocked]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState === "hidden") { cancelVoice(true); flushDraft(); } };
    const leave = () => { cancelVoice(true); flushDraft(); };
    document.addEventListener("visibilitychange", hide);
    window.addEventListener("pagehide", leave);
    return () => { document.removeEventListener("visibilitychange", hide); window.removeEventListener("pagehide", leave); permissionEpoch.current++; cancelVoice(true); };
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
    cancelVoice(true); setVoiceSettling(false); setVoiceNotice(""); permissionEpoch.current++; setRequestingMic(false); setVoiceMode(false); setFocused(false);
    setSheet(null); setPreparing(false); setGeneratingCount(0);
    setProblem(null);
  }, [draftKey]);

  useEffect(() => {
    if (!seed) return;
    setText(current => seed.append && current.trim() ? `${current}\n\n${seed.text}` : seed.text);
    if (seed.attachments) {
      const restored = seed.attachments;
      setAttachments(current => [...restored, ...current.filter(a => !restored.some(r => r.id === a.id))]);
    }
    if (seed.append) { setVoiceMode(false); setFocused(true); requestAnimationFrame(() => area.current?.focus()); }
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
    const owner = attachmentDraft.capture();
    const { accepted, rejected } = checkAttachments(owner.value.length, [...list]);
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
          if (owner.isCurrent()) setProblem(appError("HR-WEB-006", `${file.name}: too-large after re-encoding`));
          continue;
        }
        prepared.push({ id: `att-${++seq}`, file: image.blob, name: image.name, mimeType: image.mimeType, kind, previewUrl: URL.createObjectURL(image.blob) });
      } else {
        prepared.push({ id: `att-${++seq}`, file, name: file.name, mimeType: file.type || "application/octet-stream", kind });
      }
    }
    owner.write((current) => [...current, ...prepared].slice(0, MAX_ATTACHMENTS));
    if (owner.isCurrent()) setPreparing(false);
  }

  /** Each picked conversation becomes its own Markdown chip, all placed together (HG-38). */
  async function attachConversations(picked: SessionListItem[]) {
    const owner = attachmentDraft.capture();
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
    owner.write((current) => [...current, ...made].slice(0, MAX_ATTACHMENTS));
    if (owner.isCurrent()) {
      setGeneratingCount(0);
      if (failed.length) setProblem(appError("HR-SESS-014", `${failed.length} of ${picked.length} failed — ${failed.join("; ")}`));
    }
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

  const canSend = !disabled && !preparing && generatingCount === 0 && (text.trim() !== "" || attachments.length > 0);

  function sendContent(message: string, onDelivered?: VoiceDeliveryReceipt) {
    const state = latest.current;
    if (onDelivered) state.onSend(message.trim(), state.attachments, onDelivered);
    else state.onSend(message.trim(), state.attachments);
    setText("");
    flushDraft();
    setAttachments([]); // preview URLs now belong to the sent bubble
    setProblem(null); setVoiceNotice("");
    collapse();
  }

  function send() {
    if (canSend) sendContent(text);
  }

  if (blocked) return <div class="composer-wrap">{blocked}</div>;

  const full = attachments.length >= MAX_ATTACHMENTS;
  const addButton = () => (
    <button type="button" class="icon-button composer-add" aria-label={t("添加内容", "Add content")} disabled={disabled} onClick={() => { collapse(); setSheet("add"); }}>
      <PlusIcon />
    </button>
  );
  const sendOrStop = () => generating ? (
    <button type="button" class="send-button stop" aria-label={t("停止", "Stop")} onClick={onInterrupt}>
      <span class="send-button-disc" aria-hidden="true"><StopIcon /></span>
    </button>
  ) : (
    <button type="button" class="send-button" aria-label={t("发送", "Send")} disabled={!canSend} onClick={send}>
      <span class="send-button-disc" aria-hidden="true">
        {preparing ? <span class="spinner tiny" aria-hidden="true" /> : <SendIcon />}
      </span>
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
      {problem ? <ErrorNotice error={problem} language={language} onRetry={problem.code === "HR-PERM-006" ? () => void enterVoice() : problem.code.startsWith("HR-VOICE-") && !pendingVoice ? () => { setProblem(null); setVoiceMode(true); } : undefined} onDismiss={() => setProblem(null)} variant="inline" /> : null}
      {pendingVoice ? <div class="voice-recovery" role="status">
        <p>{voiceRecordingActive(pendingVoice) ? t("这段录音仍在录制，请在原页面松手后重试。", "This recording is still active. Release it in the original view first.") : pendingVoice.text ? t("上次语音文字已保留；请先核对发送记录，再恢复到输入框。", "Previous voice text kept. Check sent messages before restoring it.") : pendingVoice.complete ? t("完整录音已保留", "Complete recording kept") : t("录音已中止，已保存片段可转文字", "Recording interrupted. Saved audio can be transcribed")}{pendingVoice.bytes ? ` · ${Math.floor(pendingVoice.bytes / 32000)}s` : ""}</p>
        <p>{t("仅本机保留，24 小时后清理。恢复后请检查文字并重新添加缺失附件。", "Kept on this device for 24 hours. Review restored text and re-add missing attachments.")}</p>
        <div class="error-actions">
          {pendingVoice.complete && !pendingVoice.limited && !pendingVoice.restored && !pendingVoice.text ? <button type="button" class="text-button" disabled={voiceRecordingActive(pendingVoice) || voiceSettling || voicePhase !== "idle" || disabled || !voiceReady || generating} onClick={() => void retryVoice("send")}>{t("重试发送", "Retry send")}</button> : null}
          <button type="button" class="text-button" disabled={voiceRecordingActive(pendingVoice) || voiceSettling || voicePhase !== "idle"} onClick={() => void retryVoice("edit")}>{pendingVoice.text ? t("恢复文字", "Restore text") : t("转文字", "Transcribe")}</button>
          <button type="button" class="text-button" disabled={voiceRecordingActive(pendingVoice) || voiceSettling || voicePhase !== "idle"} onClick={() => { const saved = pendingVoice; void recordingStore.remove(saved.id).then(() => { setPendingVoice(null); setProblem(null); setVoiceNotice(""); }, () => setProblem(appError("HR-VOICE-006"))); }}>{t("丢弃", "Discard")}</button>
        </div>
      </div> : null}
      {voiceNotice ? <p class="composer-progress" role="status">{voiceNotice}</p> : null}
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
        // WebKit may suppress bubbling on disabled buttons; capture must run before blur
        // can replace that same slot with the compact attachment action under the finger.
        onPointerDownCapture={(event) => { pressedInside.current = true; retainDisabledActionPress(event); }}
        onTouchStartCapture={retainDisabledActionPress}
      >
        {[
          { ref: camera, accept: "image/*", capture: "environment" as const, multiple: false },
          { ref: photos, accept: "image/*", capture: undefined, multiple: true },
          { ref: picker, accept: ios ? "application/octet-stream" : undefined, capture: undefined, multiple: true },
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
          <VoiceComposer t={t} phase={voicePhase} recording={voiceRecording} limited={voiceLimited} feedbackEnabled={app.voiceFeedback === "on"} text={voiceText} notice={voiceNotice} disabled={voicePhase === "idle" && (disabled || generating || !voiceReady || preparing || generatingCount > 0 || voiceSettling || !voiceRecoveryLoaded || !!pendingVoice)} onBegin={beginVoice} onRelease={releaseVoice} onInterrupted={() => cancelVoice(true)} onCancelWait={() => cancelVoice(true)} />
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
