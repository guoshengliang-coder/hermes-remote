import { useEffect, useLayoutEffect, useRef, useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import { voiceActionAt, type VoiceAction, type VoiceTarget } from "../chat/voiceGesture";
import { VoiceHaptics } from "../chat/voiceHaptics";
import { VOICE_RECORDING_LIMIT_MS } from "../chat/voicePolicy";
import { CloseIcon, TextIcon } from "./icons";

export interface VoiceComposerProps {
  t: Translate;
  phase: "idle" | "held" | "waiting";
  recording: boolean;
  limited?: boolean;
  feedbackEnabled: boolean;
  text: string;
  disabled: boolean;
  onBegin: () => void;
  onRelease: (action: VoiceAction) => void;
  onInterrupted: () => void;
  onCancelWait: () => void;
}
export function VoiceComposer({ t, phase, recording, limited, feedbackEnabled, text, disabled, onBegin, onRelease, onInterrupted, onCancelWait }: VoiceComposerProps) {
  const [zone, setZone] = useState<VoiceAction>("send");
  const [elapsed, setElapsed] = useState(0);
  const gesture = useRef<{ id: number | "keyboard"; started: number; touchId?: number; key?: string } | null>(null);
  const hold = useRef<HTMLButtonElement>(null), overlay = useRef<HTMLDivElement>(null);
  const removeListeners = useRef(() => {});
  const settled = useRef(false);
  const latest = useRef({ phase, onRelease, onInterrupted });
  latest.current = { phase, onRelease, onInterrupted };
  const action = useRef<VoiceAction>("send");
  const cancel = useRef<HTMLButtonElement>(null), edit = useRef<HTMLButtonElement>(null);
  const active = phase !== "idle";
  const haptics = useRef(new VoiceHaptics());
  useLayoutEffect(() => {
    haptics.current.sync(recording && phase === "held" && gesture.current !== null, feedbackEnabled, zone);
  }, [recording, phase, feedbackEnabled, zone]);
  useEffect(() => {
    const reset = () => haptics.current.reset();
    const hide = () => { if (document.visibilityState === "hidden") reset(); };
    window.addEventListener("pagehide", reset);
    document.addEventListener("visibilitychange", hide);
    return () => {
      window.removeEventListener("pagehide", reset);
      document.removeEventListener("visibilitychange", hide);
      reset();
    };
  }, []);
  useEffect(() => {
    if (phase === "idle") { setElapsed(0); return; }
    if (phase === "waiting" && limited) { setElapsed(VOICE_RECORDING_LIMIT_MS); return; }
    if (phase !== "held" || !recording) return;
    const started = Date.now(); setElapsed(0);
    const timer = setInterval(() => setElapsed(Math.min(VOICE_RECORDING_LIMIT_MS, Date.now() - started)), 120);
    return () => clearInterval(timer);
  }, [phase, recording, limited]);
  useLayoutEffect(() => {
    if (phase !== "held") clearGesture();
    if (phase === "idle") settled.current = false;
  }, [phase]);
  useEffect(() => () => clearGesture(), []);
  function clearGesture() {
    const current = gesture.current;
    gesture.current = null;
    removeListeners.current(); removeListeners.current = () => {};
    haptics.current.reset();
    if (current && typeof current.id === "number") {
      try { if (hold.current?.hasPointerCapture?.(current.id)) hold.current.releasePointerCapture(current.id); }
      catch { /* Capture is only an optimization; the browser may have already released it. */ }
    }
  }
  function begin(id: number | "keyboard", key?: string) {
    if (disabled || phase !== "idle" || gesture.current) return false;
    gesture.current = { id, started: Date.now(), key }; settled.current = false;
    action.current = "send"; setZone("send");
    // Install before onBegin renders the overlay. The end may target that overlay or the
    // document even when capture is missing; effects would leave a fast-release gap.
    removeListeners.current = watchGesture();
    onBegin();
    return true;
  }
  function release(force?: VoiceAction) {
    const current = gesture.current;
    if ((!current && !force) || latest.current.phase !== "held" || settled.current) return;
    settled.current = true; clearGesture();
    latest.current.onRelease(force ?? (Date.now() - current!.started < 250 ? "cancel" : action.current));
  }
  function interrupt() {
    if (!gesture.current || latest.current.phase !== "held" || settled.current) return;
    settled.current = true; clearGesture();
    latest.current.onInterrupted();
  }
  function move(x: number, y: number) {
    const next = voiceActionAt(x, y, target(cancel.current), target(edit.current), action.current);
    action.current = next; setZone(next);
  }
  function watchGesture() {
    const matches = (e: PointerEvent) => gesture.current?.id === e.pointerId;
    const pointerMove = (e: PointerEvent) => {
      if (!matches(e)) return;
      // A missed mouse/pen up is observable on the next move. Touch buttons are not reliable.
      if ((e.pointerType === "mouse" || e.pointerType === "pen") && e.buttons === 0) interrupt();
      else move(e.clientX, e.clientY);
    };
    const pointerUp = (e: PointerEvent) => { if (matches(e)) { move(e.clientX, e.clientY); release(); } };
    const pointerInterrupted = (e: PointerEvent) => { if (matches(e)) interrupt(); };
    const touchEnd = (e: TouchEvent) => {
      const touch = Array.from(e.changedTouches).find(t => t.identifier === gesture.current?.touchId);
      if (!touch) return;
      if (e.type === "touchcancel") interrupt();
      else { move(touch.clientX, touch.clientY); release(); }
    };
    const hidden = () => { if (document.visibilityState === "hidden") interrupt(); };
    const keyUp = (e: KeyboardEvent) => { if (gesture.current?.id === "keyboard" && e.key === gesture.current.key) { e.preventDefault(); release(); } };
    window.addEventListener("pointermove", pointerMove, true);
    window.addEventListener("pointerup", pointerUp, true);
    window.addEventListener("pointercancel", pointerInterrupted, true);
    window.addEventListener("lostpointercapture", pointerInterrupted, true);
    window.addEventListener("touchend", touchEnd, true);
    window.addEventListener("touchcancel", touchEnd, true);
    window.addEventListener("keyup", keyUp, true);
    window.addEventListener("blur", interrupt);
    window.addEventListener("pagehide", interrupt);
    document.addEventListener("visibilitychange", hidden);
    return () => {
      window.removeEventListener("pointermove", pointerMove, true);
      window.removeEventListener("pointerup", pointerUp, true);
      window.removeEventListener("pointercancel", pointerInterrupted, true);
      window.removeEventListener("lostpointercapture", pointerInterrupted, true);
      window.removeEventListener("touchend", touchEnd, true);
      window.removeEventListener("touchcancel", touchEnd, true);
      window.removeEventListener("keyup", keyUp, true);
      window.removeEventListener("blur", interrupt);
      window.removeEventListener("pagehide", interrupt);
      document.removeEventListener("visibilitychange", hidden);
    };
  }
  function target(el: HTMLElement | null): VoiceTarget | null {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, radius: r.width / 2 };
  }
  // HG-172: a held touch is the control's own gesture. preventDefault on contextmenu stops the
  // browser's long-press default from running on top of it — Android pops the selection/search
  // sheet and iOS starts selecting text, and either steals the pointer (cancelling the recording).
  // The overlay covers the held button, so it carries the same default, and styles.css keeps the
  // button and overlay unselectable (user-select / -webkit-touch-callout).
  const blockLongPress = (e: Event) => e.preventDefault();
  return <>
    <button ref={hold} type="button" class="voice-hold" disabled={disabled && !active}
      aria-label={t("按住说话；向左上滑取消，向右上滑转文字", "Hold to talk; slide up left to cancel or up right to edit")}
      onContextMenu={blockLongPress}
      onPointerDown={(e) => {
        if (e.button !== 0 || gesture.current || disabled) return;
        e.preventDefault();
        if (begin(e.pointerId)) {
          try { e.currentTarget.setPointerCapture?.(e.pointerId); }
          catch { /* Global listeners still own the gesture when capture is unavailable. */ }
        }
      }}
      onTouchStart={(e) => {
        // WebKit can emit touchend/cancel without the pointer equivalent. Touch identifiers
        // differ from pointerId: remember the initiating touch, never a later second finger.
        const current = gesture.current;
        if (current && typeof current.id === "number" && current.touchId === undefined) current.touchId = e.changedTouches[0]?.identifier;
      }}
      onKeyDown={(e) => {
        if (e.key === "Escape") { e.preventDefault(); phase === "waiting" ? onCancelWait() : release("cancel"); }
        if (gesture.current?.id === "keyboard" && ["ArrowLeft", "ArrowRight", "ArrowDown"].includes(e.key)) {
          e.preventDefault(); action.current = e.key === "ArrowLeft" ? "cancel" : e.key === "ArrowRight" ? "edit" : "send"; setZone(action.current);
        }
        if ((e.key === " " || e.key === "Enter") && !e.repeat) { e.preventDefault(); begin("keyboard", e.key); }
      }}
      onBlur={(e) => { if (gesture.current?.id === "keyboard" && !overlay.current?.contains(e.relatedTarget as Node | null)) interrupt(); }}
    >{phase === "held" ? t("松开发送", "Release to send") : phase === "waiting" ? t("正在完成识别…", "Finishing recognition…") : t("按住说话", "Hold to talk")}</button>
    {active ? <div ref={overlay} class="voice-overlay" role="dialog" aria-modal="true" aria-label={t("语音输入", "Voice input")}
      onContextMenu={blockLongPress}
      onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); phase === "waiting" ? onCancelWait() : release("cancel"); } }}>
      <div class="voice-stage">
        <div class="voice-card">
          <div class="voice-card-head"><span class="voice-clock mono">{`${Math.floor(elapsed / 60000).toString().padStart(2, "0")}:${Math.floor(elapsed / 1000 % 60).toString().padStart(2, "0")} / 05:00`}</span>
            <span>{phase === "waiting" ? t("正在完成识别…", "Finishing recognition…") : zone === "cancel" ? t("松手取消", "Release to cancel") : zone === "edit" ? t("松手转文字", "Release to edit") : t("松手发送", "Release to send")}</span></div>
          <div class="voice-wave" aria-hidden="true">{Array.from({ length: 21 }, (_, i) => <i key={i} style={{ height: `${12 + 32 * Math.abs(Math.sin(i * 1.7 + elapsed / 400))}px` }} />)}</div>
          {phase === "held" && elapsed >= VOICE_RECORDING_LIMIT_MS - 15_000 ? <p role="status">{t(`还可录 ${Math.ceil((VOICE_RECORDING_LIMIT_MS - elapsed) / 1000)} 秒，到时转为草稿`, `Remaining: ${Math.ceil((VOICE_RECORDING_LIMIT_MS - elapsed) / 1000)}s; then save to draft`)}</p> : null}
          <p class="voice-transcript" role="status">{text || t("正在聆听…", "Listening…")}</p>
        </div>
        <div class="voice-targets">
          <div><button ref={cancel} type="button" class={`voice-target${zone === "cancel" ? " selected cancel" : ""}`} onClick={() => phase === "waiting" ? onCancelWait() : release("cancel")} aria-label={t("取消录音", "Cancel recording")}><CloseIcon size={28} /></button><span>{t("移到这里取消", "Slide here to cancel")}</span></div>
          <div><button ref={edit} type="button" class={`voice-target${zone === "edit" ? " selected" : ""}`} onClick={() => release("edit")} aria-label={t("转为文字", "Convert to text")}><TextIcon size={28} /></button><span>{t("滑到这里转文字", "Slide here to edit")}</span></div>
        </div>
        {phase === "waiting" ? <button type="button" class="voice-wait-cancel" onClick={onCancelWait}>{t("取消等待，保留临时文字", "Cancel waiting and keep partial text")}</button> : <p class="voice-listening">{t("正在聆听", "Listening")}</p>}
      </div>
    </div> : null}
  </>;
}
