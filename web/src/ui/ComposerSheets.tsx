import { useEffect, useMemo, useState } from "preact/hooks";
import { BackClose, useBackClose } from "../app/useBackClose";
import { hermesPaths } from "../api/gateway";
import { deletePrompt, loadPrompts, newPromptId, upsertPrompt, type SavedPrompt } from "../app/prompts";
import { isBotSession, isListable } from "../app/sources";
import { useApp } from "../app/store";
import type { ProfileSessionsResponse, SessionListItem } from "../hermes/types";
import { BackIcon, CheckIcon, CloseIcon, PlusIcon } from "./icons";
import { projectLabelOf } from "./SessionRow";
import { Sheet } from "./Sheet";

// The composer's secondary surfaces (Android ChatScreen add-content sheet, SavedPromptSheet,
// PromptLibraryScreen and SessionPickerDialog), Web-sized.

// ---- saved prompts --------------------------------------------------------------------------

export function SavedPromptsSheet({ onPick, onManage, onClose }: { onPick: (body: string) => void; onManage: () => void; onClose: () => void }) {
  const { t } = useApp();
  const prompts = loadPrompts();
  return (
    <Sheet closeLabel={t("关闭", "Close")} onClose={onClose} wide>
      <div class="prompts-head">
        <span />
        <div class="prompts-title">
          <strong>{t("常用提示", "Saved prompts")}</strong>
          <span>{t(`${prompts.length} 条`, `${prompts.length} prompts`)}</span>
        </div>
        <button type="button" class="text-button" onClick={onManage}>
          {t("管理", "Manage")}
        </button>
      </div>
      {prompts.length === 0 ? <p class="picker-note">{t("还没有保存的提示词，点右上角「管理」添加。", "No saved prompts yet — add one with Manage.")}</p> : null}
      {prompts.map((p) => (
        <button type="button" class="prompt-pick" key={p.id} onClick={() => onPick(p.body)}>
          <span class="prompt-pick-title">{p.title || p.body.split("\n")[0]}</span>
          <span class="prompt-pick-body">{p.body}</span>
        </button>
      ))}
    </Sheet>
  );
}

/** Full-screen prompt library: list, new, edit, delete (confirmed). */
export function PromptLibrary({ onClose }: { onClose: () => void }) {
  const { t } = useApp();
  const [prompts, setPrompts] = useState<SavedPrompt[]>(loadPrompts);
  const [editing, setEditing] = useState<SavedPrompt | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<SavedPrompt | null>(null);
  useBackClose(onClose);

  if (editing) {
    const isNew = !prompts.some((p) => p.id === editing.id);
    const valid = editing.body.trim() !== "";
    return (
      <div class="source-dialog" role="dialog" aria-modal="true" aria-label={isNew ? t("新建提示词", "New prompt") : t("编辑提示词", "Edit prompt")}>
        <BackClose onClose={() => setEditing(null)} />
        <header class="topbar">
          <div class="topbar-row">
            <button type="button" class="icon-button" aria-label={t("取消", "Cancel")} onClick={() => setEditing(null)}>
              <BackIcon />
            </button>
            <h1 class="topbar-title left">{isNew ? t("新建提示词", "New prompt") : t("编辑提示词", "Edit prompt")}</h1>
            <button
              type="button"
              class="text-button"
              disabled={!valid}
              onClick={() => {
                setPrompts(upsertPrompt({ ...editing, title: editing.title.trim(), body: editing.body }));
                setEditing(null);
              }}
            >
              {t("保存", "Save")}
            </button>
          </div>
        </header>
        <div class="prompt-form">
          <label class="field-label" for="prompt-title">
            {t("标题", "Title")}
          </label>
          <input id="prompt-title" class="sheet-input" maxLength={80} value={editing.title} onInput={(e) => setEditing({ ...editing, title: (e.target as HTMLInputElement).value })} />
          <label class="field-label" for="prompt-body">
            {t("提示词", "Prompt")}
          </label>
          <textarea id="prompt-body" class="sheet-input prompt-body-input" rows={8} value={editing.body} onInput={(e) => setEditing({ ...editing, body: (e.target as HTMLTextAreaElement).value })} />
          {!isNew ? (
            <button type="button" class="text-button danger-text" onClick={() => setConfirmDelete(editing)}>
              {t("删除", "Delete")}
            </button>
          ) : null}
        </div>
        {confirmDelete ? (
          <Sheet title={t("删除这条提示词？", "Delete this prompt?")} closeLabel={t("取消", "Cancel")} onClose={() => setConfirmDelete(null)}>
            <p class="sheet-body">{t(`“${confirmDelete.title || confirmDelete.body.slice(0, 20)}”将被删除。`, `"${confirmDelete.title || confirmDelete.body.slice(0, 20)}" will be deleted.`)}</p>
            <div class="sheet-buttons">
              <button type="button" class="text-button subtle" onClick={() => setConfirmDelete(null)}>
                {t("取消", "Cancel")}
              </button>
              <button
                type="button"
                class="primary-button inline danger"
                onClick={() => {
                  setPrompts(deletePrompt(confirmDelete.id));
                  setConfirmDelete(null);
                  setEditing(null);
                }}
              >
                {t("删除", "Delete")}
              </button>
            </div>
          </Sheet>
        ) : null}
      </div>
    );
  }

  return (
    <div class="source-dialog" role="dialog" aria-modal="true" aria-label={t("常用提示", "Saved prompts")}>
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("返回", "Back")} onClick={onClose}>
            <BackIcon />
          </button>
          <h1 class="topbar-title left">{t("常用提示", "Saved prompts")}</h1>
          <button type="button" class="icon-button" aria-label={t("新建", "New")} onClick={() => setEditing({ id: newPromptId(), title: "", body: "" })}>
            <PlusIcon />
          </button>
        </div>
      </header>
      <div class="prompt-library">
        {prompts.length === 0 ? <p class="empty-line">{t("还没有保存的提示词，点击右上角添加。", "No saved prompts yet. Add one at the top right.")}</p> : null}
        {prompts.map((p) => (
          <button type="button" class="prompt-pick" key={p.id} onClick={() => setEditing(p)}>
            <span class="prompt-pick-title">{p.title || p.body.split("\n")[0]}</span>
            <span class="prompt-pick-body">{p.body}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ---- session picker ------------------------------------------------------------------------

/**
 * Pick conversations to attach as Markdown (docs/SESSION_EXCHANGE_REQUIREMENTS.md §3): full screen,
 * multi-select capped by the attachment slots left, the current conversation excluded, archived
 * rows only when searching (titles only), a full row dims once the cap is reached.
 */
export function SessionPicker({
  excludeId,
  slots,
  onDone,
  onClose,
}: {
  excludeId: string | null;
  slots: number;
  onDone: (sessions: SessionListItem[]) => void;
  onClose: () => void;
}) {
  const app = useApp();
  const { t, client, device } = app;
  const [query, setQuery] = useState("");
  const [archived, setArchived] = useState<SessionListItem[] | null>(null);
  const [selected, setSelected] = useState<SessionListItem[]>([]);
  useBackClose(onClose);

  const q = query.trim().toLowerCase();
  useEffect(() => {
    if (!q || archived || !device) return;
    client
      .deviceApi<ProfileSessionsResponse>(device.deviceId, "GET", `${hermesPaths.profileSessions}?limit=500&order=recent&archived=only`)
      .then((body) => setArchived(Array.isArray(body?.sessions) ? body.sessions.map((s) => ({ ...s, archived: true })) : []), () => setArchived([]));
  }, [q]);

  const rows = useMemo(() => {
    const live = app.sessions.filter((s) => isListable(s) && !isBotSession(s) && !s.archived && s.id !== excludeId);
    if (!q) return live;
    const match = (s: SessionListItem) => (s.title || s.display_name || "").toLowerCase().includes(q);
    return [...live.filter(match), ...(archived ?? []).filter((s) => s.id !== excludeId && match(s))];
  }, [app.sessions, archived, q, excludeId]);

  const left = slots - selected.length;
  const toggle = (s: SessionListItem) => {
    if (selected.some((x) => x.id === s.id)) setSelected(selected.filter((x) => x.id !== s.id));
    else if (left > 0) setSelected([...selected, s]);
  };

  return (
    <div class="source-dialog" role="dialog" aria-modal="true" aria-label={t("添加会话", "Add conversations")}>
      <header class="topbar">
        <div class="topbar-row">
          <button type="button" class="icon-button" aria-label={t("关闭", "Close")} onClick={onClose}>
            <CloseIcon />
          </button>
          <h1 class="topbar-title left">{t("添加会话", "Add conversations")}</h1>
          <span class="topbar-spacer" />
        </div>
        <div class="picker-search">
          <input class="search-input" type="search" value={query} placeholder={t("搜索会话标题（含已归档）", "Search titles (incl. archived)")} onInput={(e) => setQuery((e.target as HTMLInputElement).value)} />
        </div>
      </header>
      <div class="picker-rows">
        {rows.length === 0 ? <p class="empty-line">{q ? t("没有匹配的会话", "No matching conversations") : t("没有可以添加的会话", "No conversations to add")}</p> : null}
        {rows.map((s) => {
          const on = selected.some((x) => x.id === s.id);
          const dim = !on && left <= 0;
          const project = projectLabelOf(s, null);
          return (
            <button type="button" class={`pick-row${dim ? " dim" : ""}`} key={s.id} aria-pressed={on} disabled={dim} onClick={() => toggle(s)}>
              <span class="row-main">
                <span class="row-title">
                  {s.archived ? <span class="row-tag first">{t("已归档", "Archived")}</span> : null}
                  {s.title || s.display_name || t("未命名会话", "Untitled")}
                </span>
                {project || s.model ? <span class="row-subline mono">{[project, s.model].filter(Boolean).join(" · ")}</span> : null}
              </span>
              <span class={`check-box${on ? " on" : ""}`} aria-hidden="true">
                {on ? <CheckIcon size={14} /> : null}
              </span>
            </button>
          );
        })}
      </div>
      <footer class="picker-footer-bar">
        <span class="picker-left">{t(`最多再选 ${left} 个`, `${left} more allowed`)}</span>
        <button type="button" class="primary-button inline" disabled={selected.length === 0} onClick={() => onDone(selected)}>
          {selected.length ? t(`添加 ${selected.length} 个会话`, `Add ${selected.length}`) : t("添加", "Add")}
        </button>
      </footer>
    </div>
  );
}
