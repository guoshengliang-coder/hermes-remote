import { useEffect, useState } from "preact/hooks";
import { contractNotice, type ContractReport, type HealthNotice } from "../app/health";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon } from "./icons";

// App health strip on the list (DESIGN §5.20, Android HealthStrip + GatewayHealthMonitor): shown only
// when something is wrong — this device offline, the Relay unreachable, or the Mac's Hermes failing
// the Connector's contract check. Status is probed every 30 s while visible; the contract report is
// cached for 5 minutes. Tapping the strip shows the coded explanation and "check again".

const STATUS_EVERY_MS = 30_000;
const CONTRACT_TTL_MS = 5 * 60_000;
let contractCache: { deviceId: string; at: number; notice: HealthNotice | null } | null = null;

type Health = { kind: "ok" } | { kind: "offline" } | { kind: "unreachable"; error: AppError } | { kind: "contract"; notice: HealthNotice };

export function HealthStrip() {
  const { t, language, client, device } = useApp();
  const deviceId = device?.deviceId ?? null;
  const [health, setHealth] = useState<Health>({ kind: "ok" });
  const [open, setOpen] = useState(false);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (!deviceId) return;
    let live = true;
    const check = async () => {
      if (typeof navigator !== "undefined" && navigator.onLine === false) {
        if (live) setHealth({ kind: "offline" });
        return;
      }
      try {
        await client.deviceApi(deviceId, "GET", "status");
      } catch (e) {
        const status = (e as { status?: number }).status;
        // A Mac that is merely offline has its own banner (HR-CONN-005); only a Relay that cannot
        // be reached at all is this strip's business.
        if (status === undefined || status === 0 || status >= 500 && status !== 503) {
          if (live) setHealth({ kind: "unreachable", error: appError("HR-CONN-002", `status probe: ${status ?? "no response"}`) });
          return;
        }
      }
      let notice: HealthNotice | null;
      if (contractCache && contractCache.deviceId === deviceId && Date.now() - contractCache.at < CONTRACT_TTL_MS && nonce === 0) {
        notice = contractCache.notice;
      } else {
        try {
          const report = await client.deviceApi<ContractReport>(deviceId, "GET", "hermes-remote/contract");
          notice = contractNotice(report);
        } catch {
          notice = null; // could not look: never shown as a fault
        }
        contractCache = { deviceId, at: Date.now(), notice };
      }
      if (live) setHealth(notice ? { kind: "contract", notice } : { kind: "ok" });
    };
    void check();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void check();
    }, STATUS_EVERY_MS);
    const onNet = () => void check();
    window.addEventListener("online", onNet);
    window.addEventListener("offline", onNet);
    return () => {
      live = false;
      clearInterval(timer);
      window.removeEventListener("online", onNet);
      window.removeEventListener("offline", onNet);
    };
  }, [deviceId, nonce]);

  if (health.kind === "ok") return null;
  const label =
    health.kind === "offline"
      ? t("设备已离线", "You're offline")
      : health.kind === "unreachable"
        ? t("Relay 暂时无法连接", "Gateway unreachable")
        : health.notice.severity === "breaking"
          ? t("Mac 上的 Hermes 不兼容", "Hermes on the Mac is incompatible")
          : t("Mac 上的 Hermes 部分功能不可用", "Some Hermes features are unavailable");
  const error = health.kind === "offline" ? appError("HR-CONN-001") : health.kind === "unreachable" ? health.error : health.notice.error;
  const tone = health.kind === "contract" && health.notice.severity === "degraded" ? "warn" : "bad";
  return (
    <div class={`health-strip ${tone}`}>
      <button type="button" class="health-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span class={`dot ${tone === "warn" ? "dot-warn" : "dot-bad"}`} aria-hidden="true" />
        <span class="health-label">{label}</span>
        <ChevronIcon open={open} />
      </button>
      {open ? (
        <ErrorNotice
          error={{ ...error, retryable: true }}
          language={language}
          onRetry={() => {
            contractCache = null;
            setNonce(nonce + 1);
          }}
          variant="inline"
        />
      ) : null}
    </div>
  );
}
