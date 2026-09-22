import { useEffect, useState } from "preact/hooks";
import { hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { defaultProjectPath } from "../app/localPrefs";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import type { ProfileSessionsResponse, SessionListItem } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { BackIcon } from "./icons";
import { SessionRow } from "./SessionRow";

// Archived conversations (DESIGN §5.3, Android ArchivedScreen): a full page from the list's "more"
// menu; tap opens the chat. Unarchive and delete need the Gateway allowlist (batch 4), so the Web
// page is read-only for now.

export function ArchivedPage() {
  const { t, language, client, device } = useApp();
  const deviceId = device?.deviceId ?? null;
  const [rows, setRows] = useState<SessionListItem[] | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!deviceId) return;
    let live = true;
    setError(null);
    client
      .deviceApi<ProfileSessionsResponse>(deviceId, "GET", `${hermesPaths.profileSessions}?limit=500&order=recent&archived=only`)
      .then(
        (body) => live && setRows(Array.isArray(body?.sessions) ? body.sessions.filter((s) => s.archived !== false) : []),
        (e: unknown) => live && setError(toAppError(e, "device")),
      );
    return () => {
      live = false;
    };
  }, [deviceId, attempt]);

  const now = Date.now();
  const defaultProject = deviceId ? defaultProjectPath(deviceId) : null;
  return (
    <div class="page list-page">
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={() => navigate({ name: "list" })}>
            <BackIcon />
          </button>
          <h1 class="topbar-title left">{t("已归档", "Archived")}</h1>
          <span class="topbar-spacer" />
        </div>
      </header>
      <main class="content">
        {error ? <ErrorNotice error={error} language={language} onRetry={() => setAttempt(attempt + 1)} /> : null}
        {!rows && !error ? <div class="center-spinner"><span class="spinner" /></div> : null}
        {rows && rows.length === 0 ? (
          <div class="empty-state">
            <p class="empty-line">{t("暂无归档会话", "No archived conversations")}</p>
          </div>
        ) : null}
        {rows?.map((s) => (
          <SessionRow key={s.id} session={s} now={now} defaultProject={defaultProject} onOpen={() => navigate({ name: "chat", sessionId: s.id })} />
        ))}
      </main>
    </div>
  );
}
