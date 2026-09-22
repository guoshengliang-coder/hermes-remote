// Read aloud with the browser's speech synthesis (Android uses its TTS engine). One utterance at a
// time; listeners learn which message is speaking so its button can say "stop".

type Listener = (key: string | null) => void;

let speakingKey: string | null = null;
const listeners = new Set<Listener>();

function emit() {
  for (const l of listeners) l(speakingKey);
}

export function speechSupported(): boolean {
  return typeof window !== "undefined" && "speechSynthesis" in window && typeof SpeechSynthesisUtterance !== "undefined";
}

export function speakingNow(): string | null {
  return speakingKey;
}

export function onSpeaking(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function stopSpeaking(): void {
  if (!speechSupported()) return;
  window.speechSynthesis.cancel();
  speakingKey = null;
  emit();
}

/** Speak `text` for message `key`; speaking the same key again stops it. */
export function toggleSpeak(key: string, text: string): void {
  if (!speechSupported()) return;
  if (speakingKey === key) {
    stopSpeaking();
    return;
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = /[㐀-鿿]/.test(text) ? "zh-CN" : "en-US";
  const done = () => {
    if (speakingKey === key) {
      speakingKey = null;
      emit();
    }
  };
  utterance.onend = done;
  utterance.onerror = done;
  speakingKey = key;
  emit();
  window.speechSynthesis.speak(utterance);
}
