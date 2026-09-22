import { useState } from "preact/hooks";
import type { AppError, Language } from "../errors";
import { localized } from "../errors";

// Every user-visible failure: localized explanation, the HR-* code on its own line, technical
// details behind a toggle, and Retry only when the error is retryable (docs/ERROR_HANDLING.md).

export interface ErrorNoticeProps {
  error: AppError;
  language: Language;
  onRetry?: (() => void) | undefined;
  onDismiss?: (() => void) | undefined;
  variant?: "card" | "inline" | "banner";
}

export function ErrorNotice({ error, language, onRetry, onDismiss, variant = "card" }: ErrorNoticeProps) {
  const [open, setOpen] = useState(false);
  const en = language === "en";
  return (
    <div class={`error-notice error-${variant}`} role="alert">
      <p class="error-text">{localized(error, language)}</p>
      <p class="error-code mono">{en ? `Error code: ${error.code}` : `错误码：${error.code}`}</p>
      <div class="error-actions">
        {error.retryable && onRetry ? (
          <button type="button" class="text-button" onClick={onRetry}>
            {en ? "Retry" : "重试"}
          </button>
        ) : null}
        {error.details ? (
          <button type="button" class="text-button subtle" aria-expanded={open} onClick={() => setOpen(!open)}>
            {en ? "Details" : "详情"}
          </button>
        ) : null}
        {onDismiss ? (
          <button type="button" class="text-button subtle" onClick={onDismiss}>
            {en ? "Dismiss" : "关闭"}
          </button>
        ) : null}
      </div>
      {open && error.details ? <pre class="error-details mono">{error.details}</pre> : null}
    </div>
  );
}
