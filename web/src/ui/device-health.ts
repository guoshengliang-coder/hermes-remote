import type { AccountDevice } from "../api/gateway";
import type { Translate } from "../app/i18n";

export function deviceHealthText(device: AccountDevice, t: Translate): string {
  const health = device.connection;
  if (!health || health.version !== 1) return device.connector.online ? t("在线", "Online") : t("离线", "Offline");
  if (health.business === "lost") return health.control === "lost" ? t("离线", "Offline") : t("聊天连接重连中", "Chat connection reconnecting");
  if (health.local === "unhealthy") return t("HR-CONN-006 · 请在 Mac 上检查 Hermes", "HR-CONN-006 · Check Hermes on the Mac");
  if ([health.business, health.control].includes("suspect")) return t("网络拥塞 · 传输已减速，可继续聊天", "Network congested · Transfers slowed; chat available");
  if ([health.business, health.control].includes("recovering")) return t("连接恢复中 · 可继续聊天", "Recovering · Chat available");
  if (health.control === "lost") return t("健康检查重连中 · 可继续聊天", "Health connection reconnecting · Chat available");
  if (health.local === "unknown" || health.healthAgeMs >= 60_000) return t("Hermes 状态待刷新", "Hermes status unknown");
  if (health.local === "degraded") return t("Hermes 响应较慢 · 正在复查", "Hermes responding slowly · Rechecking");
  return t("在线", "Online");
}
