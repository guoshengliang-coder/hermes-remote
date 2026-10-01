import type { Translate } from "../app/i18n";
import { CloseIcon } from "./icons";

// Foreground lifecycle notice (HG-186): a session finished or is waiting on the user while the
// reader is elsewhere. Tapping the body opens that session; the card also carries its own close
// button, so it can be dismissed without leaving the page. Auto-hide lives in App.tsx.

export interface SessionToastProps {
  title: string;
  waiting: boolean;
  event?: string;
  t: Translate;
  onOpen: () => void;
  onClose: () => void;
}

export function SessionToast({ title, waiting, event, t, onOpen, onClose }: SessionToastProps) {
  return (
    <div class="toast">
      <button type="button" class="toast-main" onClick={onOpen}>
        <span class={`dot ${waiting || event === "run.interrupted" || event === "run.unknown" ? "dot-warn" : "dot-good"}`} aria-hidden="true" />
        <span class="toast-text">
          {event === "run.interrupted" ? t(`「${title}」已中断，请检查`, `"${title}" was interrupted. Open to check`) : event === "run.unknown" ? t(`「${title}」状态未确认，请检查`, `"${title}" has an unconfirmed status. Open to check`) : waiting ? t(`「${title}」需要你处理`, `"${title}" needs you`) : t(`「${title}」已完成`, `"${title}" finished`)}
        </span>
      </button>
      <button type="button" class="toast-close" aria-label={t("关闭提醒", "Dismiss notification")} onClick={onClose}>
        <CloseIcon />
      </button>
    </div>
  );
}
