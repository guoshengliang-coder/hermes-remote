import { useEffect, useState } from "preact/hooks";
import pkg from "../../package.json";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";
import { BackIcon, ChevronIcon, CloseIcon, MacIcon, SettingsIcon } from "./icons";

// The Web counterpart of the Android card drawer. Only the chosen Mac's data is shown.
export function AccountDrawer({ onClose }: { onClose: () => void }) {
  const app = useApp();
  const { t, device } = app;
  const [page, setPage] = useState<"home" | "settings">("home");
  useBackClose(onClose);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [onClose]);

  const themeOptions = [
    { id: "system", label: t("跟随系统", "System") },
    { id: "light", label: t("浅色", "Light") },
    { id: "dark", label: t("深色", "Dark") },
  ] as const;
  const languageOptions = [
    { id: "system", label: t("跟随系统", "System") },
    { id: "zh", label: t("中文", "中文") },
    { id: "en", label: "English" },
  ] as const;

  return (
    <>
      <div class="drawer-scrim" onClick={onClose} />
      <aside class="account-drawer" role="dialog" aria-modal="true" aria-label={page === "home" ? "Hermes GO" : t("设置", "Settings")}>
        <header class="drawer-header">
          {page === "settings" ? (
            <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={() => setPage("home")}><BackIcon /></button>
          ) : null}
          <h2>{page === "home" ? "Hermes GO" : t("设置", "Settings")}</h2>
          {page === "home" ? (
            <button type="button" class="icon-button" aria-label={t("设置", "Settings")} onClick={() => setPage("settings")}><SettingsIcon size={21} /></button>
          ) : (
            <button type="button" class="icon-button" aria-label={t("关闭", "Close")} onClick={onClose}><CloseIcon /></button>
          )}
        </header>
        {page === "home" ? (
          <div class="drawer-body">
            <button type="button" class="drawer-device" onClick={() => { onClose(); app.chooseDevice(); }}>
              <span class="drawer-device-icon"><MacIcon /></span>
              <span class="drawer-device-copy">
                <strong>{device?.desktopDisplayName || t("远程节点", "Remote Mac")}</strong>
                <small>{device?.connector?.online ? t("在线", "Online") : t("离线", "Offline")}{typeof device?.gateway?.latencyMs === "number" ? ` · ${Math.round(device.gateway.latencyMs)} ms` : ""}</small>
              </span>
              <ChevronIcon />
            </button>
            <section class="drawer-section">
              <h3>{t("主题", "Theme")}</h3>
              <div class="drawer-choices" role="group" aria-label={t("主题", "Theme")}>
                {themeOptions.map((option) => <button type="button" key={option.id} aria-pressed={app.themeMode === option.id}
                  onClick={() => app.setThemeMode(option.id)}>{option.label}</button>)}
              </div>
            </section>
          </div>
        ) : (
          <div class="drawer-body">
            <section class="drawer-section">
              <h3>{t("语言", "Language")}</h3>
              <div class="drawer-choices" role="group" aria-label={t("语言", "Language")}>
                {languageOptions.map((option) => <button type="button" key={option.id} aria-pressed={app.languagePreference === option.id}
                  onClick={() => app.setLanguagePreference(option.id)}>{option.label}</button>)}
              </div>
            </section>
            <section class="drawer-section">
              <h3>{t("Hermes GO 账号", "Hermes GO account")}</h3>
              <p class="drawer-detail">{app.account?.email ?? "—"}</p>
              <button type="button" class="drawer-link" onClick={() => { onClose(); void app.signOut(); }}>{t("退出登录", "Sign out")}</button>
              <a class="drawer-link danger" href="/account">{t("删除账号", "Delete account")}</a>
            </section>
            <section class="drawer-section">
              <h3>{t("关于", "About")}</h3>
              <p class="drawer-detail">Web {pkg.version}</p>
              <p class="drawer-detail">Gateway {app.gatewayVersion ?? "—"}</p>
            </section>
          </div>
        )}
        <footer class="drawer-footer">✦<br />Your AI Agent, in Your Pocket</footer>
      </aside>
    </>
  );
}
