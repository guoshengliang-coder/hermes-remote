import { useEffect, useRef, useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import { voiceActionAt, type VoiceAction, type VoiceTarget } from "../chat/voiceGesture";
import { CloseIcon, TextIcon } from "./icons";

export interface VoiceComposerProps {
  t: Translate;
  phase: "idle" | "held" | "waiting";
  text: string;
  disabled: boolean;
  onBegin: () => void;
  onRelease: (action: VoiceAction) => void;
  onCancelWait: () => void;
}
export function VoiceComposer({ t, phase, text, disabled, onBegin, onRelease, onCancelWait }: VoiceComposerProps) {
  const [zone, setZone] = useState<VoiceAction>("send");
  const [elapsed, setElapsed] = useState(0);
  const gesture = useRef<{ id: number | "keyboard"; started: number } | null>(null);
  const action = useRef<VoiceAction>("send");
  const cancel = useRef<HTMLButtonElement>(null), edit = useRef<HTMLButtonElement>(null);
  const active = phase !== "idle";
  useEffect(() => {
    if (phase !== "held") return;
    const started = Date.now(); setElapsed(0);
    const timer = setInterval(() => setElapsed(Date.now() - started), 120);
    return () => clearInterval(timer);
  }, [phase]);
  useEffect(() => { if (phase === "idle") gesture.current = null; }, [phase]);
  function begin(id: number | "keyboard") {
    if (disabled || phase !== "idle" || gesture.current) return;
    gesture.current = { id, started: Date.now() }; action.current = "send"; setZone("send"); onBegin();
  }
  function release(force?: VoiceAction) {
    const current = gesture.current;
    if (!current) return;
    gesture.current = null;
    onRelease(force ?? (Date.now() - current.started < 250 ? "cancel" : action.current));
  }
  function target(el: HTMLElement | null): VoiceTarget | null {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, radius: r.width / 2 };
  }
  return <>
    <button type="button" class="voice-hold" disabled={disabled && !active}
      aria-label={t("按住说话；向左上滑取消，向右上滑转文字", "Hold to talk; slide up left to cancel or up right to edit")}
      onPointerDown={(e) => {
        if (e.button !== 0 || gesture.current || disabled) return;
        e.preventDefault(); e.currentTarget.setPointerCapture?.(e.pointerId); begin(e.pointerId);
      }}
      onPointerMove={(e) => {
        if (gesture.current?.id !== e.pointerId) return;
        const next = voiceActionAt(e.clientX, e.clientY, target(cancel.current), target(edit.current));
        action.current = next; setZone(next);
      }}
      onPointerUp={(e) => { if (gesture.current?.id === e.pointerId) release(); }}
      onPointerCancel={() => release("cancel")}
      onLostPointerCapture={() => release("cancel")}
      onKeyDown={(e) => {
        if (e.key === "Escape") { e.preventDefault(); phase === "waiting" ? onCancelWait() : release("cancel"); }
        if (gesture.current?.id === "keyboard" && ["ArrowLeft", "ArrowRight", "ArrowDown"].includes(e.key)) {
          e.preventDefault(); action.current = e.key === "ArrowLeft" ? "cancel" : e.key === "ArrowRight" ? "edit" : "send"; setZone(action.current);
        }
        if ((e.key === " " || e.key === "Enter") && !e.repeat) { e.preventDefault(); begin("keyboard"); }
      }}
      onKeyUp={(e) => { if (e.key === " " || e.key === "Enter") { e.preventDefault(); release(); } }}
      onBlur={() => { if (gesture.current?.id === "keyboard") release("cancel"); }}
    >{phase === "held" ? t("松开发送", "Release to send") : phase === "waiting" ? t("正在完成识别…", "Finishing recognition…") : t("按住说话", "Hold to talk")}</button>
    {active ? <div class="voice-overlay" role="dialog" aria-modal="true" aria-label={t("语音输入", "Voice input")}
      onKeyDown={(e) => { if (e.key === "Escape") { e.preventDefault(); phase === "waiting" ? onCancelWait() : release("cancel"); } }}>
      <div class="voice-stage">
        <div class="voice-card">
          <div class="voice-card-head"><span class="voice-clock mono">{`${Math.floor(elapsed / 60000).toString().padStart(2, "0")}:${Math.floor(elapsed / 1000 % 60).toString().padStart(2, "0")}`}</span>
            <span>{phase === "waiting" ? t("正在完成识别…", "Finishing recognition…") : zone === "cancel" ? t("松手取消", "Release to cancel") : zone === "edit" ? t("松手转文字", "Release to edit") : t("松手发送", "Release to send")}</span></div>
          <div class="voice-wave" aria-hidden="true">{Array.from({ length: 21 }, (_, i) => <i key={i} style={{ height: `${12 + 32 * Math.abs(Math.sin(i * 1.7 + elapsed / 400))}px` }} />)}</div>
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
