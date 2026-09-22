import type { AccountDevice } from "../api/gateway";
import type { Translate } from "../app/i18n";
import type { AppError, Language } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { MacIcon } from "./icons";

// Choose which Mac this browser talks to. The choice is remembered in localStorage (a device id is
// not a secret); offline Macs stay selectable but say so.

export interface DevicePickerProps {
  devices: AccountDevice[];
  selectedId: string | null;
  language: Language;
  t: Translate;
  error?: AppError | null;
  onRetry?: () => void;
  onSelect: (device: AccountDevice) => void;
  onSignOut: () => void;
}

export function DevicePicker({ devices, selectedId, language, t, error, onRetry, onSelect, onSignOut }: DevicePickerProps) {
  return (
    <div class="page">
      <header class="topbar">
        <div class="topbar-row">
          <span class="topbar-spacer" />
          <h1 class="topbar-title">{t("选择 Mac", "Choose a Mac")}</h1>
          <span class="topbar-spacer" />
        </div>
      </header>
      <main class="content">
        {error ? <ErrorNotice error={error} language={language} onRetry={onRetry} /> : null}
        {!error && devices.length === 0 ? (
          <div class="empty-state">
            <p>{t("这个账号还没有连接 Mac。请在 Mac 上打开 Hermes Go Desktop 并登录同一账号。", "No Mac is connected to this account yet. Open Hermes Go Desktop on the Mac and sign in with the same account.")}</p>
            {onRetry ? (
              <button type="button" class="text-button" onClick={onRetry}>
                {t("刷新", "Refresh")}
              </button>
            ) : null}
          </div>
        ) : null}
        <ul class="device-list">
          {devices.map((device) => {
            const online = device.connector?.online === true;
            return (
              <li key={device.deviceId}>
                <button type="button" class={`device-row${device.deviceId === selectedId ? " selected" : ""}`} onClick={() => onSelect(device)}>
                  <span class="device-icon">
                    <MacIcon />
                  </span>
                  <span class="device-text">
                    <span class="device-name">{device.desktopDisplayName || device.deviceId}</span>
                    <span class="device-sub">
                      <span class={`dot ${online ? "dot-good" : "dot-off"}`} aria-hidden="true" />
                      {online ? t("在线", "Online") : t("离线", "Offline")}
                      {device.access === "operator" ? ` · ${t("共享给我", "Shared with me")}` : ""}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        <div class="picker-footer">
          <button type="button" class="text-button subtle" onClick={onSignOut}>
            {t("退出登录", "Sign out")}
          </button>
        </div>
      </main>
    </div>
  );
}
