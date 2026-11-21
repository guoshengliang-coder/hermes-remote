import { loadProfileSessionPages } from "../app/profileSessionPages";
import { useProfilePreferences } from "../app/profilePreferences";
import { explicitProfile } from "../app/profile";
import { useEffect, useLayoutEffect, useState } from "preact/hooks";
import { useListReturn } from "../app/useListReturn";
import { useStartupPage } from "../startup/readiness";
import { toAppError } from "../app/failures";
import { defaultProjectPath } from "../app/localPrefs";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import type { SessionListItem } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { BackIcon } from "./icons";
import { SessionActionSheet } from "./SessionActions";
import { SessionRow } from "./SessionRow";

// Archived conversations (DESIGN §5.3, Android ArchivedScreen): a full page from the list's "more"
// menu; tap opens the chat. Unarchive and delete need the Gateway allowlist (batch 4), so the Web
// page is read-only for now.

export function ArchivedPage() {
  const { t, language, client, device } = useApp();
  const deviceId = device?.deviceId ?? null;
  const listReturn = useListReturn(`archived:${deviceId}`, { rows: null as SessionListItem[] | null });
  const [rows, setRows] = useState<SessionListItem[] | null>(listReturn.initial.rows);
  const [error, setError] = useState<AppError | null>(null);
  useStartupPage("archived", rows !== null, error);
  const [attempt, setAttempt] = useState(0);
  const [actionFor, setActionFor] = useState<SessionListItem | null>(null);
  useLayoutEffect(() => { listReturn.remember({ rows }); listReturn.restore(); });

  useEffect(() => {
    if (!deviceId) return;
    let live = true;
    setError(null);
    const controller = new AbortController();
    loadProfileSessionPages(client, deviceId, controller.signal, true)
      .then(
        (items) => live && setRows(items.filter(s => s.archived !== false)),
        (e: unknown) => live && setError(toAppError(e, "device")),
      );
    return () => {
      live = false;
      controller.abort();
    };
  }, [deviceId, attempt]);

  const prefs = useProfilePreferences();
  const visible = rows?.filter(s => !prefs.hidden(explicitProfile(s) ?? "default"));
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
      <div class="page-scroll" ref={listReturn.port}>
        <main class="content">
          {error ? <ErrorNotice error={error} language={language} onRetry={() => setAttempt(attempt + 1)} /> : null}
          {!rows && !error ? <div class="center-spinner"><span class="spinner" /></div> : null}
          {visible && visible.length === 0 ? (
            <div class="empty-state">
              <p class="empty-line">{t("暂无归档会话", "No archived conversations")}</p>
            </div>
          ) : null}
          {visible?.map((s) => (
            <SessionRow
              showTime
              key={JSON.stringify([s.profile, s.id])}
              session={s}
              now={now}
              defaultProject={defaultProject}
              onOpen={() => navigate({ name: "chat", sessionId: s.id, profile: explicitProfile(s) ?? undefined })}
              onLongPress={() => setActionFor(s)}
            />
          ))}
          {visible?.length ? <p class="list-hint">{t("长按会话可取消归档或删除。", "Press and hold a conversation to unarchive or delete it.")}</p> : null}
        </main>
      </div>
      {actionFor ? (
        <SessionActionSheet
          session={actionFor}
          archived
          onClose={() => setActionFor(null)}
          onChanged={() => setRows((current) => current?.filter((r) => r.id !== actionFor.id || explicitProfile(r) !== explicitProfile(actionFor)) ?? null)}
        />
      ) : null}
    </div>
  );
}
