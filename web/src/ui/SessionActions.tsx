import { useState } from "preact/hooks";
import { draftKey, saveDraft } from "../app/drafts";
import { toAppError } from "../app/failures";
import { defaultProjectPath } from "../app/localPrefs";
import { explicitProfile } from "../app/profile";
import { basename, deriveProjects, disambiguatedLabels, projectKeyOf } from "../app/projects";
import { isBotSession, isListable } from "../app/sources";
import { useApp } from "../app/store";
import { moveSessionTo } from "../chat/oneShot";
import { appError, RPC, type AppError } from "../errors";
import { HermesSocketError } from "../hermes/client";
import type { SessionListItem } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { CheckIcon, ChevronIcon, FolderIcon } from "./icons";
import { Sheet, SheetAction } from "./Sheet";
import { loadSessions } from "./SessionList";

// The row action sheet (DESIGN §5.5 行长按操作单, Android RowActionSheet / SessionActionItems):
// pin · rename · move to project · archive (always confirmed, not red: it is reversible) · delete
// (red, confirmed: irreversible). Archived rows get unarchive · delete. Every write goes through the
// Gateway's one-shape allowlist (Web batch 4) and appears only when the Gateway advertises it.

export type SessionChange = "renamed" | "archived" | "unarchived" | "deleted" | "moved";

type Step = "menu" | "rename" | "move" | "archive" | "delete";

/** A failed move, by Hermes' code (Android ProjectMoveErrors.kt). */
export function moveError(error: unknown): AppError {
  if (error instanceof HermesSocketError && error.kind === "rpc") {
    if (error.code === RPC.SESSION_BUSY) return appError("HR-SESS-004", error.message);
    if (error.code === 4017) return appError("HR-SESS-003", error.message);
    if (error.code === RPC.SESSION_NOT_FOUND) return appError("HR-SESS-001", error.message);
    if (error.code === RPC.WEB_METHOD_REFUSED) return appError("HR-WEB-001", error.message);
  }
  return appError("HR-SESS-005", error instanceof Error ? error.message : String(error));
}

export function SessionActionSheet({
  session,
  archived = false,
  busy = false,
  onClose,
  onChanged,
  move,
  start,
}: {
  session: SessionListItem;
  /** The archived page's sheet: unarchive · delete. */
  archived?: boolean;
  /** A run is in flight: moving is refused by Hermes (4009), so it is greyed out. */
  busy?: boolean;
  onClose: () => void;
  onChanged?: (change: SessionChange) => void;
  /** The chat page moves through its own socket; elsewhere a one-shot socket is used. */
  move?: (cwd: string) => Promise<void>;
  /** Open straight on one step (the chat's 「归档对话」 or its project subtitle). */
  start?: "archive" | "move";
}) {
  const app = useApp();
  const { t, language, client, device, features } = app;
  const deviceId = device?.deviceId ?? "";
  const [step, setStep] = useState<Step>(start ?? "menu");
  const [title, setTitle] = useState(session.title || session.display_name || "");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const profile = explicitProfile(session);
  const name = session.title || session.display_name || t("未命名会话", "Untitled");
  const defaultProject = deviceId ? defaultProjectPath(deviceId) : null;
  const currentKey = projectKeyOf(session) ?? defaultProject;
  const currentLabel = !currentKey || currentKey === defaultProject ? t("默认项目", "Default project") : basename(currentKey);
  const canManage = features.has("session-manage");
  const canDelete = features.has("session-delete");
  const canMove = features.has("workspace-move") && !archived && !isBotSession(session);
  const pinned = app.isPinned(session);

  async function refresh() {
    if (!deviceId) return;
    try {
      app.setSessions(await loadSessions(client, deviceId));
    } catch {
      /* the next visible refresh catches up */
    }
  }

  async function run(work: () => Promise<unknown>, change: SessionChange, done: string | null) {
    setWorking(true);
    setError(null);
    try {
      await work();
      if (change === "deleted") {
        if (pinned) app.togglePin(session);
        if (deviceId) saveDraft(draftKey(deviceId, session.id), "");
      }
      onClose();
      if (done) app.flash(done);
      onChanged?.(change);
      void refresh();
    } catch (e) {
      setError(change === "moved" ? moveError(e) : toAppError(e, "device"));
    } finally {
      setWorking(false);
    }
  }

  const projects = (() => {
    const derived = deriveProjects(app.sessions.filter((s) => isListable(s) && !isBotSession(s))).filter((p) => p.path !== null);
    const labels = disambiguatedLabels(derived);
    const rows: { path: string; label: string }[] = [];
    if (defaultProject) rows.push({ path: defaultProject, label: t("默认项目", "Default project") });
    for (const p of derived) if (p.path !== defaultProject) rows.push({ path: p.path!, label: labels.get(p.path) ?? p.label });
    return rows;
  })();

  if (step === "rename") {
    const clean = title.trim();
    return (
      <Sheet title={t("重命名会话", "Rename conversation")} closeLabel={t("取消", "Cancel")} onClose={onClose}>
        <div class="sheet-form">
          <input
            class="sheet-input"
            value={title}
            maxLength={200}
            autoFocus
            enterkeyhint="done"
            onInput={(e) => setTitle((e.target as HTMLInputElement).value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && clean && !working) void run(() => client.updateSession(deviceId, session.id, { title: clean }, profile), "renamed", null);
            }}
          />
          {error ? <ErrorNotice error={error} language={language} variant="inline" /> : null}
        </div>
        <div class="sheet-buttons">
          <button type="button" class="text-button subtle" onClick={onClose}>
            {t("取消", "Cancel")}
          </button>
          <button
            type="button"
            class="primary-button inline"
            disabled={!clean || working}
            onClick={() => void run(() => client.updateSession(deviceId, session.id, { title: clean }, profile), "renamed", null)}
          >
            {t("保存", "Save")}
          </button>
        </div>
      </Sheet>
    );
  }

  if (step === "archive" || step === "delete") {
    const del = step === "delete";
    return (
      <Sheet title={del ? t("删除会话？", "Delete conversation?") : t("归档这个对话？", "Archive this conversation?")} closeLabel={t("取消", "Cancel")} onClose={onClose}>
        <p class="sheet-body">
          {del
            ? t(`“${name}”将被永久删除。`, `"${name}" will be permanently deleted.`)
            : t("可在「已归档」中恢复。", "You can restore it from Archived.")}
        </p>
        {error ? <ErrorNotice error={error} language={language} variant="inline" /> : null}
        <div class="sheet-buttons">
          <button type="button" class="text-button subtle" onClick={onClose}>
            {t("取消", "Cancel")}
          </button>
          <button
            type="button"
            class={`primary-button inline${del ? " danger" : ""}`}
            disabled={working}
            onClick={() =>
              void (del
                ? run(() => client.deleteSession(deviceId, session.id, profile), "deleted", t("已删除", "Deleted"))
                : run(() => client.updateSession(deviceId, session.id, { archived: true }, profile), "archived", t("已归档", "Archived")))
            }
          >
            {del ? t("删除", "Delete") : t("归档", "Archive")}
          </button>
        </div>
      </Sheet>
    );
  }

  if (step === "move") {
    return (
      <Sheet title={t("移动到项目", "Move to project")} closeLabel={t("关闭", "Close")} onClose={onClose}>
        {error ? <ErrorNotice error={error} language={language} variant="inline" /> : null}
        {projects.map((p) => {
          const current = p.path === currentKey;
          return (
            <button
              type="button"
              class={`picker-row${current ? " selected" : ""}`}
              key={p.path}
              disabled={working}
              onClick={() => {
                if (current) return onClose();
                void run(
                  () => (move ? move(p.path) : moveSessionTo(client, deviceId, session.id, p.path, profile)),
                  "moved",
                  t(`已移动到 ${p.label}`, `Moved to ${p.label}`),
                );
              }}
            >
              <span class="picker-icon">
                <FolderIcon size={18} />
              </span>
              <span class="picker-text">
                <span class="picker-name">{current ? t(`当前 · ${p.label}`, `Current · ${p.label}`) : p.label}</span>
                <span class="picker-path mono">
                  <bdi>{p.path}</bdi>
                </span>
              </span>
              <span class="picker-check">{current ? <CheckIcon size={18} /> : null}</span>
            </button>
          );
        })}
        {projects.length === 0 ? <p class="picker-note">{t("还没有可以移入的项目。", "No projects to move into yet.")}</p> : null}
      </Sheet>
    );
  }

  return (
    <Sheet closeLabel={t("关闭", "Close")} onClose={onClose}>
      <div class="action-sheet-head">
        <span class="action-chip">{archived ? t("已归档", "Archived") : t("会话", "Conversation")}</span>
        <span class="action-title">{name}</span>
      </div>
      {error ? <ErrorNotice error={error} language={language} variant="inline" /> : null}
      {archived ? (
        <>
          {canManage ? (
            <SheetAction
              label={t("取消归档", "Unarchive")}
              disabled={working}
              onClick={() => void run(() => client.updateSession(deviceId, session.id, { archived: false }, profile), "unarchived", t("已恢复", "Restored"))}
            />
          ) : null}
        </>
      ) : (
        <>
          <SheetAction
            label={pinned ? t("取消置顶", "Unpin") : t("置顶", "Pin")}
            onClick={() => {
              app.togglePin(session);
              onClose();
              app.flash(pinned ? t("已取消置顶", "Unpinned") : t("已置顶（仅此设备）", "Pinned on this device"));
            }}
          />
          {canManage ? <SheetAction label={t("重命名", "Rename")} onClick={() => setStep("rename")} /> : null}
          {canMove ? (
            <button type="button" class="sheet-action row-value" disabled={busy} onClick={() => setStep("move")}>
              <span class="sheet-action-label">{t("移动到项目", "Move to project")}</span>
              <span class="sheet-action-value">
                {currentLabel}
                <ChevronIcon />
              </span>
            </button>
          ) : null}
          {canManage ? <SheetAction label={t("归档会话", "Archive conversation")} hint={t("可在归档箱恢复", "Restore it from Archived")} onClick={() => setStep("archive")} /> : null}
        </>
      )}
      {canDelete ? (
        <>
          <div class="sheet-divider" />
          <SheetAction label={t("删除会话", "Delete conversation")} hint={t("不可撤销", "Can't be undone")} danger onClick={() => setStep("delete")} />
        </>
      ) : null}
    </Sheet>
  );
}
