import { useEffect, useState } from "preact/hooks";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";
import {
  bindPushWorker,
  announcePushState,
  currentPushEpoch,
  resetPushWorker,
  disablePush,
  messagePushWorker,
  PUSH_PATH,
  pushEnvironment,
  pushWorker,
} from "../app/push";
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
  const [status, setStatus] = useState("loading");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  useEffect(() => {
    void refresh();
  }, []);
  async function refresh() {
    setError(null);
    if (environment !== "supported") {
      setStatus(environment);
      return;
    }
    try {
      const config = await bindPushWorker(client);
      const subscription = await (
        await pushWorker()
      ).pushManager.getSubscription();
      setStatus(
        Notification.permission === "denied"
          ? "denied"
          : config.registration && subscription
            ? "enabled"
            : "off",
      );
    } catch (e) {
      if (e instanceof GatewayHttpError && e.status === 404)
        setStatus("unavailable");
      else {
        setStatus("off");
        setError(
          appError("HR-WEB-012", pushCause(e, "push state query failed")),
        );
      }
    }
  }
  async function enable() {
    // This call must happen directly in the click, before any asynchronous worker/network work.
    const epoch = currentPushEpoch();
    const accountId = account?.id;
    setBusy(true);
    setError(null);
    try {
      const permission = Notification.requestPermission();
      if ((await permission) !== "granted") {
        setStatus(Notification.permission === "denied" ? "denied" : "off");
        return;
      }
      const config = await client.request<{ publicKey: string }>(
        "GET",
        PUSH_PATH,
      );
      const registration = await pushWorker();
      let subscription = await registration.pushManager.getSubscription();
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
      }
    } catch (e) {
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
    setBusy(true);
    setError(null);
    try {
      await disablePush(client);
      setStatus("off");
    } catch (e) {
      setStatus("off");
      setError(appError("HR-WEB-012", pushCause(e, "push disable failed")));
    } finally {
      setBusy(false);
    }
  }
  const copy: Record<string, [string, string]> = {
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
    denied: [
      "通知权限已拒绝。请打开 iPhone 设置 → 通知 → Hermes GO，允许通知后重新打开本应用。",
      "Permission was denied. Open iPhone Settings → Notifications → Hermes GO, allow notifications, then reopen this app.",
    ],
    enabled: [
      "通知已开启。回答完成、需要确认及任务异常将提醒你；展示遵循系统通知与专注模式。",
      "Notifications are on for completed answers, confirmations and interrupted or unknown tasks. System notification and Focus settings apply.",
    ],
    off: [
      "消息通知未开启。只有点击下方按钮时才会请求系统授权。",
      "Notifications are off. Permission is requested only when you press the button below.",
    ],
  };
  return (
    <>
      <div class="drawer-theme-scrim" onClick={onClose} />
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
            onClick={onClose}
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
              onRetry={() => void refresh()}
            />
          ) : null}
          {status === "off" ? (
            <button
              class="text-button"
              disabled={busy}
              onClick={() => void enable()}
            >
              {t("开启消息通知", "Enable notifications")}
            </button>
          ) : null}
          {status === "enabled" || error ? (
            <button
              class="text-button"
              disabled={busy}
              onClick={() => void disable()}
            >
              {t("关闭消息通知", "Turn off notifications")}
            </button>
          ) : null}
          {status === "denied" ? (
            <button class="text-button" onClick={() => void refresh()}>
              {t("已修改设置，重新检查", "Recheck after changing settings")}
            </button>
          ) : null}
        </div>
      </div>
    </>
  );
}
