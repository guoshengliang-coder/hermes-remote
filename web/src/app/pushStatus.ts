import { useEffect, useState } from "preact/hooks";
import { GatewayHttpError, type GatewayClient } from "../api/gateway";
import { PUSH_PATH, pushEnvironment, type PushConfig } from "./push";

export type PushStatus = "loading" | "install" | "unsupported" | "unavailable" | "denied" | "enabled" | "off" | "error";

/** Read-only: opening a drawer never subscribes, binds a channel or requests OS permission. */
export async function readPushStatus(client: GatewayClient): Promise<PushStatus> {
  const environment = pushEnvironment();
  if (environment !== "supported") return environment;
  try {
    const config = await client.request<PushConfig>("GET", PUSH_PATH);
    if (Notification.permission === "denied") return "denied";
    const worker = await navigator.serviceWorker.getRegistration("/app/");
    const subscription = await worker?.pushManager.getSubscription();
    return config.registration && subscription ? "enabled" : "off";
  } catch (error) {
    if (error instanceof GatewayHttpError && error.status === 404) return "unavailable";
    throw error;
  }
}

export function pushStatusLabel(status: PushStatus, t: (zh: string, en: string) => string): string {
  switch (status) {
    case "install": return t("需添加到主屏幕", "Add to Home Screen");
    case "unsupported": return t("系统不支持", "Unsupported");
    case "unavailable": return t("服务未开启", "Service unavailable");
    case "denied": return t("权限未开启", "Permission disabled");
    case "enabled": return t("开启", "On");
    case "off": return t("关闭", "Off");
    case "error": return t("读取失败", "Couldn't check");
    default: return t("读取中…", "Checking…");
  }
}

export function usePushStatus(client: GatewayClient, accountId: string | undefined, refreshKey: unknown): PushStatus {
  const environment = pushEnvironment();
  const [status, setStatus] = useState<PushStatus>(environment === "supported" ? "loading" : environment);
  useEffect(() => {
    let active = true;
    let revision = 0;
    const refresh = () => {
      const request = ++revision;
      void readPushStatus(client).then(
        value => { if (active && request === revision) setStatus(value); },
        () => { if (active && request === revision) setStatus("error"); },
      );
    };
    refresh();
    window.addEventListener("hermes-push-state", refresh);
    window.addEventListener("focus", refresh);
    return () => {
      active = false;
      window.removeEventListener("hermes-push-state", refresh);
      window.removeEventListener("focus", refresh);
    };
  }, [client, accountId, refreshKey]);
  return environment === "supported" ? status : environment;
}
