import { useEffect, useState } from "preact/hooks";
import pkg from "../../package.json";
import type { ThemeMode } from "../app/appearance";
import { useDefaultModel } from "../app/defaultModel";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";
import { ErrorNotice } from "./ErrorNotice";
import { BackIcon, ChevronIcon, CloseIcon, CubeIcon, MacIcon, MoonIcon, SettingsIcon, SunIcon } from "./icons";

type Translate = (zh: string, en: string) => string;
const themeLabel = (mode: ThemeMode, t: Translate) =>
  mode === "system" ? t("跟随系统", "Follow system") : mode === "light" ? t("温润浅色", "Warm light") : t("黑曜石深色", "Obsidian dark");

// Web retains its existing features while the drawer follows Android's card-page geometry.
export function AccountDrawer({ onClose }: { onClose: () => void }) {
  const app = useApp();
  const { t, device } = app;
  const [page, setPage] = useState<"home" | "settings">("home");
  const [themeOpen, setThemeOpen] = useState(false);
  const [pendingTheme, setPendingTheme] = useState<ThemeMode>(app.themeMode);
  const defaultModel = useDefaultModel(app.client, device?.deviceId ?? "", null, app.features.has("default-model"));
  const closeTop = () => {
    if (themeOpen) { setThemeOpen(false); return false; }
    if (page === "settings") { setPage("home"); return false; }
    onClose();
  };
  useBackClose(closeTop);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") closeTop(); };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  });

  const openTheme = () => { setPendingTheme(app.themeMode); setThemeOpen(true); };
  const saveTheme = () => { app.setThemeMode(pendingTheme); setThemeOpen(false); };
  const online = device?.connector?.online === true;
  const latency = device?.gateway?.latencyMs;
  const modelValue = defaultModel.model ? `${defaultModel.model.model} · ${defaultModel.model.provider}` :
    defaultModel.loading ? t("读取中…", "Loading…") : "—";

  return (
    <>
      <div class="drawer-scrim" onClick={onClose} />
      <aside class="account-drawer" role="dialog" aria-modal="true" aria-label={page === "home" ? "Hermes GO" : t("设置", "Settings")}>
        <header class="drawer-header">
          {page === "settings" ? <button type="button" class="drawer-header-action" aria-label={t("返回", "Back")} onClick={() => setPage("home")}><BackIcon size={21} /></button> : null}
          <h2>{page === "home" ? "Hermes GO" : t("设置", "Settings")}</h2>
          {page === "home" ? (
            <button type="button" class="drawer-header-action" aria-label={t("设置", "Settings")} onClick={() => setPage("settings")}><SettingsIcon size={21} /></button>
          ) : (
            <button type="button" class="drawer-header-action" aria-label={t("关闭", "Close")} onClick={onClose}><CloseIcon size={20} /></button>
          )}
        </header>
        {page === "home" ? (
          <div class="drawer-body">
            <button type="button" class="drawer-device" onClick={() => { onClose(); app.chooseDevice(); }}>
              <span class="drawer-device-icon"><MacIcon size={20} /></span>
              <span class="drawer-device-copy">
                <strong>{t("远程节点", "Remote Mac")}</strong>
                <small class={online ? "" : "offline"}>{online ? `${device?.desktopDisplayName || t("远程节点", "Remote Mac")} ${t("（当前）", "(current)")}` : t("连接器离线", "Connector offline")}</small>
              </span>
              <span class={`drawer-device-status${online ? "" : " offline"}`}>{online && typeof latency === "number" ? (latency < 1000 ? `${Math.round(latency)} ms` : `${(latency / 1000).toFixed(1)} s`) : online ? "" : t("离线", "Offline")}</span>
              <ChevronIcon />
            </button>
            <div class="drawer-shortcuts">
              <button type="button" class="drawer-shortcut" aria-label={t("主题", "Theme")} onClick={openTheme}>
                <MoonIcon size={20} />
                <span class="drawer-shortcut-title">{t("主题", "Theme")}</span>
                <span class="drawer-shortcut-value">{themeLabel(app.themeMode, t)}</span>
                <ChevronIcon />
              </button>
              {app.features.has("default-model") ? (
                <>
                  <div class="drawer-shortcut static" aria-label={t("默认模型", "Default model")}>
                    <CubeIcon size={20} />
                    <span class="drawer-shortcut-title">{t("默认模型", "Default model")}</span>
                    <span class="drawer-shortcut-value mono" title={modelValue}>{modelValue}</span>
                  </div>
                  {defaultModel.error ? <ErrorNotice error={defaultModel.error} language={app.language} onRetry={defaultModel.retry} variant="inline" /> : null}
                </>
              ) : null}
            </div>
          </div>
        ) : (
          <div class="drawer-body settings-body">
            <section class="drawer-settings-section">
              <h3>{t("语言", "Language")}</h3>
              <p>{t("跟随系统、简体中文或 English", "Follow the system, Simplified Chinese or English")}</p>
              <div class="drawer-choices" role="group" aria-label={t("语言", "Language")}>
                {([
                  { id: "system", label: t("跟随系统", "Follow system") },
                  { id: "zh", label: t("简体中文", "简体中文") },
                  { id: "en", label: "English" },
                ] as const).map((option) => <button type="button" key={option.id} aria-pressed={app.languagePreference === option.id}
                  onClick={() => app.setLanguagePreference(option.id)}>{option.label}</button>)}
              </div>
            </section>
            <section class="drawer-settings-section">
              <h3>{t("Hermes GO 账号", "Hermes GO account")}</h3>
              <p>{app.account?.email ?? "—"}</p>
              <button type="button" class="drawer-link" onClick={() => { onClose(); void app.signOut(); }}>{t("退出登录", "Sign out")}</button>
              <a class="drawer-link danger" href="/account">{t("删除账号", "Delete account")}</a>
            </section>
            <section class="drawer-settings-section">
              <h3>{t("关于", "About")}</h3>
              <p>Web {pkg.version}</p>
              <p>Gateway {app.gatewayVersion ?? "—"}</p>
            </section>
          </div>
        )}
        <footer class="drawer-footer"><span class="drawer-footer-rule" aria-hidden="true"><i />✦<i /></span><span>Your AI Agent, in Your Pocket</span></footer>
      </aside>
      {themeOpen ? <ThemeSheet inUse={app.themeMode} pending={pendingTheme} onPending={setPendingTheme} onSave={saveTheme} onClose={() => setThemeOpen(false)} t={t} /> : null}
    </>
  );
}

function ThemeSheet({ inUse, pending, onPending, onSave, onClose, t }: {
  inUse: ThemeMode;
  pending: ThemeMode;
  onPending: (mode: ThemeMode) => void;
  onSave: () => void;
  onClose: () => void;
  t: Translate;
}) {
  useBackClose(onClose);
  const options = [
    { id: "system", icon: <MacIcon size={20} />, description: t("根据浏览器的系统外观自动切换", "Follows your browser's system appearance") },
    { id: "light", icon: <SunIcon size={20} />, description: t("手工纸质柔光，长文案阅读无眩光", "Soft handmade paper for comfortable reading") },
    { id: "dark", icon: <MoonIcon size={20} />, description: t("暗夜质感，OLED 省电高对比", "High-contrast obsidian, easy on an OLED panel") },
  ] as const;
  return (
    <>
      <div class="drawer-theme-scrim" onClick={onClose} />
      <div class="drawer-theme-sheet" role="dialog" aria-modal="true" aria-label={t("外观与主题", "Appearance & theme")}>
        <div class="sheet-grip" aria-hidden="true" />
        <div class="drawer-theme-header">
          <h2>{t("外观与主题", "Appearance & theme")}</h2>
          <button type="button" class="drawer-theme-close" aria-label={t("关闭", "Close")} onClick={onClose}><CloseIcon size={16} /></button>
        </div>
        <div class="drawer-theme-options" role="radiogroup" aria-label={t("主题", "Theme")}>
          {options.map((option) => <button type="button" key={option.id} class="drawer-theme-option" role="radio"
            aria-label={themeLabel(option.id, t)} aria-checked={pending === option.id} onClick={() => onPending(option.id)}>
            <span class="drawer-theme-icon">{option.icon}</span>
            <span class="drawer-theme-copy"><strong>{themeLabel(option.id, t)}{inUse === option.id ? <em>{t("当前使用", "In use")}</em> : null}</strong><small>{option.description}</small></span>
            <span class={`drawer-theme-radio${pending === option.id ? " selected" : ""}`} aria-hidden="true" />
          </button>)}
        </div>
        <button type="button" class="drawer-theme-save" onClick={onSave}>{t("保存", "Save")}</button>
      </div>
    </>
  );
}
