import { useEffect, useState } from "preact/hooks";
import type { FontSize, LanguagePreference, ThemeMode } from "../app/appearance";
import { voiceVibrationSupported } from "../app/voiceFeedback";
import { useDefaultModel } from "../app/defaultModel";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import { useBackClose } from "../app/useBackClose";
import { AccountAvatar } from "./AccountAvatar";
import { ModelSheet } from "./ModelSheet";
import { ErrorNotice } from "./ErrorNotice";
import { ChevronIcon, CloseIcon, CubeIcon, GlobeIcon, MacIcon, MicIcon, MoonIcon, SunIcon, TextSizeIcon } from "./icons";

type Translate = (zh: string, en: string) => string;
type SheetKind = "theme" | "language" | "fontSize" | "model" | "voiceFeedback";

const themeLabel = (mode: ThemeMode, t: Translate) =>
  mode === "system" ? t("跟随系统", "Follow system") : mode === "light" ? t("温润浅色", "Warm light") : t("黑曜石深色", "Obsidian dark");
const languageLabel = (choice: LanguagePreference, t: Translate) =>
  choice === "system" ? t("跟随系统", "Follow system") : choice === "zh" ? "简体中文" : "English";
const fontSizeLabel = (size: FontSize, t: Translate) =>
  size === "standard" ? t("标准", "Default") : size === "large" ? t("大", "Large") : size === "xlarge" ? t("特大", "Larger") : t("超大", "Largest");

/**
 * Card page (DESIGN §5.1 / §5.21), with only the controls Web already provides. HG-168 folded the
 * settings sub-page in here: the gear, the back layer and the flat settings list are gone, so the
 * drawer keeps its single layer. Choices follow the card page's row → bottom sheet pattern.
 * HG-181 moved the Hermes GO account to an avatar + name + email card directly under the title,
 * above the remote-node card (Android's identity-card position), opening the /app/account page;
 * sign-out and delete-account now live there. HG-171 removed the "About" tile: it only ever
 * repeated the static Web bundle version and the Gateway's.
 */
export function AccountDrawer({ onClose }: { onClose: () => void }) {
  const app = useApp();
  const { t, device } = app;
  const [sheet, setSheet] = useState<SheetKind | null>(null);
  const defaultModel = useDefaultModel(app.client, device?.deviceId ?? "", null, app.features.has("default-model"));
  const closeTop = () => {
    if (sheet) { setSheet(null); return false; }
    onClose();
  };
  useBackClose(closeTop);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") closeTop(); };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  });

  const closeSheet = () => setSheet(null);
  const online = device?.connector?.online === true;
  const vibrationSupported = voiceVibrationSupported();
  const feedbackOptions = [
    { id: "on", label: t("开启", "On"), description: t("语音录制与滑动时震动；浏览器或系统可能限制反馈", "Vibrate during voice recording and sliding; your browser or system may limit feedback") },
    { id: "off", label: t("关闭", "Off"), description: t("保留语音输入与视觉提示，不使用震动", "Keep voice input and visual cues without vibration") },
  ] as const;
  const latency = device?.gateway?.latencyMs;
  // HG-184: only the model name, never `模型 · 提供商`. The trailing provider pushed the row
  // past its value slot and ellipsised it, and Android's card page (`CardPage.kt`) shows the same
  // bare `state.defaultModel`.
  const modelValue = defaultModel.model ? defaultModel.model.model :
    defaultModel.loading ? t("读取中…", "Loading…") : "—";
  const languageOptions = [
    { id: "system", label: t("跟随系统", "Follow system"), description: t("跟随浏览器的系统语言", "Follows your browser's system language") },
    { id: "zh", label: "简体中文", description: t("界面显示简体中文", "Simplified Chinese interface") },
    { id: "en", label: "English", description: t("界面显示英文", "English interface") },
  ] as const;
  const fontSizeOptions = [
    { id: "standard", label: t("标准", "Default"), description: t("跟随浏览器默认字号", "Follows the browser's default text size") },
    { id: "large", label: t("大", "Large"), description: t("比默认大 12.5%", "12.5% larger than default") },
    { id: "xlarge", label: t("特大", "Larger"), description: t("比默认大 25%", "25% larger than default") },
    { id: "xxlarge", label: t("超大", "Largest"), description: t("比默认大 50%", "50% larger than default") },
  ] as const;
  const themeOptions = [
    { id: "system", label: themeLabel("system", t), icon: <MacIcon size={20} />, description: t("根据浏览器的系统外观自动切换", "Follows your browser's system appearance") },
    { id: "light", label: themeLabel("light", t), icon: <SunIcon size={20} />, description: t("手工纸质柔光，长文案阅读无眩光", "Soft handmade paper for comfortable reading") },
    { id: "dark", label: themeLabel("dark", t), icon: <MoonIcon size={20} />, description: t("暗夜质感，OLED 省电高对比", "High-contrast obsidian, easy on an OLED panel") },
  ] as const;

  return (
    <>
      <div class="drawer-scrim" onClick={onClose} />
      <aside class="account-drawer" role="dialog" aria-modal="true" aria-label="Hermes GO">
        <header class="drawer-header">
          <h2>Hermes GO</h2>
        </header>
        <div class="drawer-body">
          <button type="button" class="drawer-account" onClick={() => { onClose(); navigate({ name: "account" }); }}>
            <AccountAvatar account={app.account} />
            <span class="drawer-account-copy">
              <strong>{app.account?.displayName?.trim() || app.account?.email || "—"}</strong>
              <small>{app.account?.email ?? "—"}</small>
            </span>
            <ChevronIcon />
          </button>
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
            <button type="button" class="drawer-shortcut" aria-label={t("主题", "Theme")} onClick={() => setSheet("theme")}>
              <MoonIcon size={20} />
              <span class="drawer-shortcut-title">{t("主题", "Theme")}</span>
              <span class="drawer-shortcut-value">{themeLabel(app.themeMode, t)}</span>
              <ChevronIcon />
            </button>
            {app.features.has("default-model") ? (
              <>
                <button type="button" class={`drawer-shortcut${app.features.has("default-model-write") ? "" : " static"}`} disabled={!app.features.has("default-model-write")} aria-label={t("默认模型", "Default model")} onClick={() => setSheet("model")}>
                  <CubeIcon size={20} />
                  <span class="drawer-shortcut-title">{t("默认模型", "Default model")}</span>
                  <span class="drawer-shortcut-value mono" title={modelValue}>{modelValue}</span>
                  {app.features.has("default-model-write") ? <ChevronIcon /> : null}
                </button>
                {defaultModel.error ? <ErrorNotice error={defaultModel.error} language={app.language} onRetry={defaultModel.retry} variant="inline" /> : null}
              </>
            ) : null}
            <button type="button" class="drawer-shortcut" aria-label={t("语言", "Language")} onClick={() => setSheet("language")}>
              <GlobeIcon size={20} />
              <span class="drawer-shortcut-title">{t("语言", "Language")}</span>
              <span class="drawer-shortcut-value">{languageLabel(app.languagePreference, t)}</span>
              <ChevronIcon />
            </button>
            <button type="button" class="drawer-shortcut" aria-label={t("字体大小", "Font size")} onClick={() => setSheet("fontSize")}>
              <TextSizeIcon size={20} />
              <span class="drawer-shortcut-title">{t("字体大小", "Font size")}</span>
              <span class="drawer-shortcut-value">{fontSizeLabel(app.fontSize, t)}</span>
              <ChevronIcon />
            </button>
            <button type="button" class={`drawer-shortcut${vibrationSupported ? "" : " static"}`} aria-label={t("语音震动", "Voice vibration")}
              disabled={!vibrationSupported} onClick={() => setSheet("voiceFeedback")}>
              <MicIcon size={20} />
              <span class="drawer-shortcut-title">{t("语音震动", "Voice vibration")}</span>
              <span class="drawer-shortcut-value">{!vibrationSupported ? t("当前浏览器不支持", "Unsupported in this browser") : app.voiceFeedback === "off" ? t("关闭", "Off") : t("开启", "On")}</span>
              {vibrationSupported ? <ChevronIcon /> : null}
            </button>
          </div>
        </div>
        <footer class="drawer-footer"><span class="drawer-footer-rule" aria-hidden="true"><i />✦<i /></span><span>Your AI Agent, in Your Pocket</span></footer>
      </aside>
      {sheet === "model" && app.features.has("default-model-write") ? <ModelSheet scope="default" aboveDrawer
        current={{ model: null, provider: null }} profile={null} explicitOverride={false}
        actions={{ switchModel: async () => { throw new Error("session action in default mode"); }, reasoning: async () => null, setReasoning: async () => {} }}
        onSwitched={() => defaultModel.retry()} onReasoning={() => {}} onClose={closeSheet} /> : null}
      {sheet === "theme" ? <ChoiceSheet title={t("外观与主题", "Appearance & theme")} options={themeOptions} inUse={app.themeMode}
        onSave={(mode) => { app.setThemeMode(mode); closeSheet(); }} onClose={closeSheet} t={t} /> : null}
      {sheet === "language" ? <ChoiceSheet title={t("语言", "Language")} options={languageOptions} inUse={app.languagePreference}
        onSave={(choice) => { app.setLanguagePreference(choice); closeSheet(); }} onClose={closeSheet} t={t} /> : null}
      {sheet === "fontSize" ? <ChoiceSheet title={t("字体大小", "Font size")} options={fontSizeOptions} inUse={app.fontSize}
        onSave={(size) => { app.setFontSize(size); closeSheet(); }} onClose={closeSheet} t={t} /> : null}
      {sheet === "voiceFeedback" && vibrationSupported ? <ChoiceSheet title={t("语音震动", "Voice vibration")} options={feedbackOptions} inUse={app.voiceFeedback}
        onSave={(choice) => { app.setVoiceFeedback(choice); closeSheet(); }} onClose={closeSheet} t={t} /> : null}
    </>
  );
}

/**
 * One sheet for every card-page choice (主题 / 语言 / 字体大小 / 语音震动):
 * picking only moves the pending item, 当前使用 keeps marking the value actually in force, and
 * 「保存」 is what writes it. Scrim, Escape, the close button and system back all cancel the
 * pending item instead of committing it.
 */
function ChoiceSheet<T extends string>({ title, options, inUse, onSave, onClose, t }: {
  title: string;
  options: readonly { id: T; label: string; description: string; icon?: preact.ComponentChildren }[];
  inUse: T;
  onSave: (id: T) => void;
  onClose: () => void;
  t: Translate;
}) {
  useBackClose(onClose);
  const [pending, setPending] = useState<T>(inUse);
  return (
    <>
      <div class="drawer-theme-scrim" onClick={onClose} />
      <div class="drawer-theme-sheet" role="dialog" aria-modal="true" aria-label={title}>
        <div class="sheet-grip" aria-hidden="true" />
        <div class="drawer-theme-header">
          <h2>{title}</h2>
          <button type="button" class="drawer-theme-close" aria-label={t("关闭", "Close")} onClick={onClose}><CloseIcon size={16} /></button>
        </div>
        <div class="drawer-theme-options" role="radiogroup" aria-label={title}>
          {options.map((option) => <button type="button" key={option.id} class="drawer-theme-option" role="radio"
            aria-label={option.label} aria-checked={pending === option.id} onClick={() => setPending(option.id)}>
            {option.icon ? <span class="drawer-theme-icon">{option.icon}</span> : null}
            <span class="drawer-theme-copy"><strong>{option.label}{inUse === option.id ? <em>{t("当前使用", "In use")}</em> : null}</strong><small>{option.description}</small></span>
            <span class={`drawer-theme-radio${pending === option.id ? " selected" : ""}`} aria-hidden="true" />
          </button>)}
        </div>
        <button type="button" class="drawer-theme-save" onClick={() => onSave(pending)}>{t("保存", "Save")}</button>
      </div>
    </>
  );
}
