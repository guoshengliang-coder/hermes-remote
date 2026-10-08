import { useEffect, useRef, useState } from "preact/hooks";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";
import {
  announcePushState,
  currentPushEpoch,
  resetPushWorker,
  disablePush,
  messagePushWorker,
  PUSH_PATH,
  pushEnvironment,
  pushWorker,
} from "../app/push";
import { readPushStatus, type PushStatus } from "../app/pushStatus";
import { appError, type AppError } from "../errors";
import { GatewayHttpError } from "../api/gateway";
import { CloseIcon } from "./icons";
import { ErrorNotice } from "./ErrorNotice";

// Keep diagnostic category/status, never provider endpoints, key material or raw messages.
function pushCause(error: unknown, stage: string): string {
  const category =
    error instanceof GatewayHttpError
      ? `HTTP ${error.status}`
      : error instanceof DOMException &&
          [
            "NotAllowedError",
            "AbortError",
            "InvalidStateError",
            "NetworkError",
            "SecurityError",
            "NotSupportedError",
          ].includes(error.name)
        ? error.name
        : error instanceof TypeError
          ? "TypeError"
          : "Error";
  return `${stage}: ${category}`;
}

export function PushNotifications({ onClose }: { onClose: () => void }) {
  const { client, language, t, account } = useApp();
  useBackClose(onClose);
  const environment = pushEnvironment();
  const [status, setStatus] = useState<PushStatus>(environment === "supported" ? "loading" : environment);
  const [pending, setPending] = useState<"on" | "off" | null>(null);
  const alive = useRef(true);
  const reading = useRef(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [retryAction, setRetryAction] = useState<"refresh" | "enable" | "disable">("refresh");
  useEffect(() => {
    alive.current = true;
    void refresh();
    return () => { alive.current = false; reading.current++; };
  }, [client, account?.id]);
  const close = () => { onClose(); };
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") close(); };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  });
  async function refresh() {
    setError(null);
    setRetryAction("refresh");
    if (environment !== "supported") {
      setStatus(environment);
      return;
    }
    const request = ++reading.current;
    setStatus("loading");
    setPending(null);
    try {
      const next = await readPushStatus(client);
      if (!alive.current || request !== reading.current) return;
      setStatus(next);
      setPending(next === "enabled" ? "on" : "off");
    } catch (e) {
      if (!alive.current || request !== reading.current) return;
      setStatus("error");
      setError(appError("HR-WEB-012", pushCause(e, "push state query failed")));
    }
  }
  async function enable() {
    // This call must happen directly in the click, before any asynchronous worker/network work.
    const epoch = currentPushEpoch();
    reading.current++;
    const accountId = account?.id;
    setBusy(true);
    setError(null);
    setRetryAction("enable");
    try {
      const permission = Notification.requestPermission();
      const granted = await permission;
      if (epoch !== currentPushEpoch()) return;
      if (granted !== "granted") {
        setStatus(Notification.permission === "denied" ? "denied" : "off");
        return;
      }
      const config = await client.request<{ publicKey: string }>(
        "GET",
        PUSH_PATH,
      );
      if (epoch !== currentPushEpoch()) return;
      const registration = await pushWorker();
      let subscription = await registration.pushManager.getSubscription();
      if (epoch !== currentPushEpoch()) return;
      if (!subscription) {
        const bytes = Uint8Array.from(
          atob(config.publicKey.replace(/-/g, "+").replace(/_/g, "/")),
          (c) => c.charCodeAt(0),
        );
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: bytes,
        });
      }
      if (epoch !== currentPushEpoch()) return;
      const channelId = crypto.randomUUID();
      // Local binding before registration blocks older account/channel packets.
      await messagePushWorker({ type: "push-bind", channelId }, epoch);
      if (epoch !== currentPushEpoch()) return;
      await client.request("PUT", PUSH_PATH, {
        body: {
          accountId,
          subscription: subscription.toJSON(),
          channelId,
          language,
        },
      });
      if (epoch === currentPushEpoch()) {
        announcePushState(true);
        setStatus("enabled");
        if (alive.current) onClose();
      }
    } catch (e) {
      if (epoch !== currentPushEpoch()) return;
      if (epoch === currentPushEpoch())
        await resetPushWorker().catch(() => undefined);
      setError(
        appError("HR-WEB-012", pushCause(e, "push subscription failed")),
      );
    } finally {
      setBusy(false);
    }
  }
  async function disable() {
    reading.current++;
    setBusy(true);
    setError(null);
    setRetryAction("disable");
    try {
      await disablePush(client);
      setStatus("off");
      if (alive.current) onClose();
    } catch (e) {
      // Local channel is blocked, but server cleanup failed: do not claim a completed switch.
      setStatus("error");
      setError(appError("HR-WEB-012", pushCause(e, "push disable failed")));
    } finally {
      setBusy(false);
    }
  }
  const save = () => {
    if (busy || (status !== "off" && status !== "enabled") || !pending) return;
    if (pending === (status === "enabled" ? "on" : "off")) { onClose(); return; }
    if (pending === "on") void enable();
    else void disable();
  };
  const canChoose = status === "off" || status === "enabled";
  const options = [
    { id: "on", label: t("开启", "On"), description: t("回答完成、需要确认及任务异常时提醒", "Notify on completed answers, confirmations and interrupted tasks") },
    { id: "off", label: t("关闭", "Off"), description: t("不接收本浏览器的后台消息通知", "No background notifications in this browser") },
  ] as const;
  const copy: Record<string, [string, string]> = {
    error: ["尚未确认通知状态，请重试。", "Notification state is unconfirmed. Retry."],
    loading: ["正在读取通知状态…", "Checking notifications…"],
    install: [
      "在 Safari 分享菜单中选择“添加到主屏幕”，从主屏幕打开后开启通知。需要 iOS 16.4 或更高版本。",
      "In Safari, use Share → Add to Home Screen, then open the installed app to enable notifications. Requires iOS 16.4 or later.",
    ],
    unsupported: [
      "当前环境不支持消息推送，前台提醒仍可使用。",
      "Push is unsupported here. Foreground reminders still work.",
    ],
    unavailable: [
      "服务器尚未开启消息推送，前台提醒仍可使用。",
      "Push is not enabled on this server. Foreground reminders still work.",
    ],
    denied: /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1) ? [
      "通知权限已拒绝。请打开系统设置 → 通知 → Hermes GO，允许通知后重新检查。",
      "Permission was denied. Open Settings → Notifications → Hermes GO, allow notifications, then recheck.",
    ] : [
      "通知权限已拒绝。请在浏览器的网站设置中允许通知，然后重新检查。",
      "Permission was denied. Allow notifications in your browser's site settings, then recheck.",
    ],
    enabled: [
      "通知已开启。回答完成、需要确认及任务异常将提醒你；展示遵循系统通知与专注模式。",
      "Notifications are on for completed answers, confirmations and interrupted or unknown tasks. System notification and Focus settings apply.",
    ],
    off: [
      "消息通知未开启。选择开启并保存后才会请求系统授权。",
      "Notifications are off. Permission is requested only when you select On and save.",
    ],
  };
  return (
    <>
      <div class="drawer-theme-scrim" onClick={close} />
      <div
        class="drawer-theme-sheet"
        role="dialog"
        aria-modal="true"
        aria-label={t("消息通知", "Notifications")}
      >
        <div class="sheet-grip" />
        <div class="drawer-theme-header">
          <h2>{t("消息通知", "Notifications")}</h2>
          <button
            class="drawer-theme-close"
            aria-label={t("关闭", "Close")}
            onClick={close}
          >
            <CloseIcon size={16} />
          </button>
        </div>
        <div class="push-settings">
          <p role="status">{t(...(copy[status] ?? copy.off)!)}</p>
          {error ? (
            <ErrorNotice
              error={error}
              language={language}
              onRetry={busy ? undefined : () => { if (retryAction === "enable") void enable(); else if (retryAction === "disable") void disable(); else void refresh(); }}
            />
          ) : null}
          {status === "denied" ? <ErrorNotice error={appError("HR-PERM-002")} language={language} variant="inline" /> : null}
          {status === "denied" ? (
            <button class="text-button" disabled={busy} onClick={() => void refresh()}>
              {t("已修改设置，重新检查", "Recheck after changing settings")}
            </button>
          ) : null}
          {status === "unavailable" ? <button type="button" class="text-button" onClick={() => void refresh()}>{t("重新检查", "Recheck")}</button> : null}
        </div>
        {canChoose ? <>
          <div class="drawer-theme-options" role="radiogroup" aria-label={t("消息通知", "Notifications")}>
            {options.map(option => <button type="button" key={option.id} class="drawer-theme-option" role="radio"
              aria-label={option.label} aria-checked={pending === option.id} disabled={busy} onClick={() => setPending(option.id)}>
              <span class="drawer-theme-copy"><strong>{option.label}{(status === "enabled" ? "on" : "off") === option.id ? <em>{t("当前使用", "In use")}</em> : null}</strong><small>{option.description}</small></span>
              <span class={`drawer-theme-radio${pending === option.id ? " selected" : ""}`} aria-hidden="true" />
            </button>)}
          </div>
          <button type="button" class="drawer-theme-save" disabled={busy || pending === null} onClick={save}>{busy ? t("保存中…", "Saving…") : t("保存", "Save")}</button>
        </> : null}
      </div>
    </>
  );
}
