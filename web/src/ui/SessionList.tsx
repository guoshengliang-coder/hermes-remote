import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { GatewayHttpError, hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { groupSessions, lastActiveMs, relativeTime, sessionSubline, type GroupId } from "../app/grouping";
import { deriveProjects, disambiguatedLabels, inProject } from "../app/projects";
import { navigate } from "../app/router";
import { isListable } from "../app/sources";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { buildSearchQuery } from "../hermes/search-query";
import type { ProfileSessionsResponse, SessionListItem, SessionListResponse } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { CheckIcon, ChevronIcon, CloseIcon, FolderIcon, MoreIcon, PinMark, PlusIcon, SearchIcon } from "./icons";

// Session list (DESIGN §5.2 simplified for the Web, §5.21): rows on the paper surface, groups
// needs-you → pinned → today → yesterday → last 7 days → older (each header collapses), search in
// the top bar, an optional project filter from the "more" menu, neutral FAB.

interface SearchHit {
  session_id?: string;
  id?: string;
  title?: string | null;
  snippet?: string | null;
  last_active?: number | null;
  model?: string | null;
}

const GROUP_LABEL: Record<GroupId, [string, string]> = {
  "needs-you": ["需要你处理", "Needs you"],
  pinned: ["已置顶 · 仅此设备", "Pinned · this device"],
  today: ["今天", "Today"],
  yesterday: ["昨天", "Yesterday"],
  recent: ["前 7 天", "Previous 7 days"],
  older: ["更早", "Older"],
};

export async function loadSessions(client: ReturnType<typeof useApp>["client"], deviceId: string): Promise<SessionListItem[]> {
  try {
    const body = await client.deviceApi<ProfileSessionsResponse>(deviceId, "GET", `${hermesPaths.profileSessions}?limit=500&order=recent`);
    return Array.isArray(body?.sessions) ? body.sessions : [];
  } catch (error) {
    // Older Hermes without the cross-profile list: fall back to the default profile's.
    if (error instanceof GatewayHttpError && error.status === 404) {
      const body = await client.deviceApi<SessionListResponse>(deviceId, "GET", `${hermesPaths.sessions}?limit=200&offset=0&order=recent`);
      return Array.isArray(body?.sessions) ? body.sessions : [];
    }
    throw error;
  }
}

export function SessionList() {
  const app = useApp();
  const { t, language, device, client } = app;
  const [loading, setLoading] = useState(app.sessions.length === 0);
  const [error, setError] = useState<AppError | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [searchError, setSearchError] = useState<AppError | null>(null);
  const [now, setNow] = useState(Date.now());
  const [projectsOpen, setProjectsOpen] = useState(false);
  const filter = app.projectFilter;
  const searchRef = useRef<HTMLInputElement>(null);
  const deviceId = device?.deviceId ?? null;
  const offline = device ? device.connector?.online === false : false;

  async function refresh() {
    if (!deviceId) return;
    setError(null);
    try {
      const rows = await loadSessions(client, deviceId);
      app.setSessions(rows);
      setNow(Date.now());
    } catch (e) {
      setError(toAppError(e, "device"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh();
    const onVisible = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    document.addEventListener("visibilitychange", onVisible);
    const tick = setInterval(() => setNow(Date.now()), 60_000);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      clearInterval(tick);
    };
  }, [deviceId]);

  // A lifecycle event usually means the list moved (new title, new activity): refetch.
  useEffect(() => {
    if (app.inbox.primed) void refresh();
  }, [app.inbox.cursor]);

  useEffect(() => {
    if (searching) searchRef.current?.focus();
  }, [searching]);

  // Arrived from a chat's "search all chats": open the search with that query.
  useEffect(() => {
    if (app.listSearchSeed === null) return;
    setQuery(app.listSearchSeed);
    setSearching(true);
    app.setListSearchSeed(null);
  }, [app.listSearchSeed]);

  useEffect(() => {
    if (!searching || !deviceId) return;
    const q = buildSearchQuery(query);
    if (!q) {
      setHits(null);
      setSearchError(null);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const body = await client.deviceApi<{ results?: SearchHit[]; sessions?: SearchHit[] }>(
          deviceId,
          "GET",
          `${hermesPaths.search(q)}&limit=30`,
          { signal: controller.signal },
        );
        setHits(body?.results ?? body?.sessions ?? []);
        setSearchError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setSearchError(toAppError(e, "device"));
      }
    }, 300);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, searching, deviceId]);

  const rows = useMemo(() => {
    // A session waiting on the user but not in the loaded page still gets a row.
    const known = new Set(app.sessions.map((s) => s.id));
    const extra: SessionListItem[] = [];
    for (const id of app.needsYou) {
      if (known.has(id)) continue;
      const entry = app.inbox.latest[id];
      extra.push({ id, title: entry?.title ?? null, last_active: entry ? entry.occurredAtMs / 1000 : null });
    }
    return [...extra, ...app.sessions.filter(isListable)];
  }, [app.sessions, app.needsYou, app.inbox.latest]);

  const projects = useMemo(() => deriveProjects(app.sessions.filter(isListable)), [app.sessions]);
  const projectLabels = useMemo(() => disambiguatedLabels(projects), [projects]);

  // The filter narrows everything except needs-you: a waiting question is never hidden by a filter.
  const shown = useMemo(
    () => (filter ? rows.filter((r) => app.needsYou.has(r.id) || inProject(r, filter.path)) : rows),
    [rows, filter, app.needsYou],
  );
  const groups = useMemo(() => groupSessions(shown, app.needsYou, now, app.isPinned), [shown, app.needsYou, now, app.isPinned]);
  const noFolderLabel = t("未指定文件夹", "No folder");

  function open(id: string) {
    navigate({ name: "chat", sessionId: id });
  }

  return (
    <div class="page list-page">
      <header class="topbar">
        {searching ? (
          <div class="topbar-row search-row">
            <SearchIcon />
            <input
              ref={searchRef}
              class="search-input"
              type="search"
              enterkeyhint="search"
              value={query}
              placeholder={t("搜索会话", "Search conversations")}
              onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
            />
            <button
              type="button"
              class="icon-button"
              aria-label={t("关闭搜索", "Close search")}
              onClick={() => {
                setSearching(false);
                setQuery("");
                setHits(null);
              }}
            >
              <CloseIcon />
            </button>
          </div>
        ) : (
          <div class="topbar-row">
            <span class="topbar-spacer" />
            <span class="topbar-spacer" />
            <h1 class="topbar-title">Hermes GO</h1>
            <button type="button" class="icon-button" aria-label={t("搜索", "Search")} onClick={() => setSearching(true)}>
              <SearchIcon />
            </button>
            <button type="button" class="icon-button" aria-label={t("更多", "More")} aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
              <MoreIcon />
            </button>
          </div>
        )}
        {menuOpen ? (
          <>
            <div class="menu-scrim" onClick={() => setMenuOpen(false)} />
            <div class="menu" role="menu">
              <div class="menu-caption">
                {app.account?.email ?? app.account?.displayName ?? ""}
                {device ? <span class="menu-caption-sub">{device.desktopDisplayName || device.deviceId}</span> : null}
              </div>
              <button type="button" role="menuitem" class="menu-item" onClick={() => { setMenuOpen(false); setProjectsOpen(true); }}>
                {t("按项目查看", "View by project")}
              </button>
              <button type="button" role="menuitem" class="menu-item" onClick={() => { setMenuOpen(false); app.chooseDevice(); }}>
                {t("切换 Mac", "Switch Mac")}
              </button>
              <button type="button" role="menuitem" class="menu-item danger" onClick={() => { setMenuOpen(false); void app.signOut(); }}>
                {t("退出登录", "Sign out")}
              </button>
            </div>
          </>
        ) : null}
        {filter && !searching ? (
          <div class="filter-bar">
            <span class="filter-chip mono">
              <FolderIcon size={14} />
              <span class="filter-label">{filter.path === null ? noFolderLabel : filter.label}</span>
              <button type="button" class="filter-clear" aria-label={t("显示全部会话", "Show all conversations")} onClick={() => app.setProjectFilter(null)}>
                <CloseIcon size={14} />
              </button>
            </span>
          </div>
        ) : null}
      </header>
      <main class="content">
        {offline ? (
          <ErrorNotice error={appError("HR-CONN-005")} language={language} onRetry={() => void refresh()} variant="banner" />
        ) : null}
        {searching && query.trim() ? (
          <section class="search-results">
            {searchError ? <ErrorNotice error={searchError} language={language} /> : null}
            {hits === null && !searchError ? <div class="center-spinner"><span class="spinner" /></div> : null}
            {hits && hits.length === 0 ? <p class="empty-line">{t("没有找到匹配的会话", "No matching conversations")}</p> : null}
            {hits?.map((hit) => {
              const id = hit.session_id ?? hit.id ?? "";
              if (!id) return null;
              return (
                <button type="button" class="session-row with-divider" key={id} onClick={() => open(id)}>
                  <span class="row-main">
                    <span class="row-title">{hit.title || t("未命名会话", "Untitled")}</span>
                    {hit.snippet ? <span class="row-snippet">{hit.snippet}</span> : null}
                  </span>
                  <span class="row-time">{relativeTime(lastActiveMs(hit), now, language)}</span>
                </button>
              );
            })}
          </section>
        ) : (
          <>
            {error ? <ErrorNotice error={error} language={language} onRetry={() => void refresh()} /> : null}
            {loading && !error ? <div class="center-spinner"><span class="spinner" /></div> : null}
            {!loading && !error && groups.length === 0 ? (
              <p class="empty-line">
                {filter ? t("这个项目里没有会话，点右下角在这里新建", "No conversations in this project — start one below") : t("还没有会话，点右下角开始", "No conversations yet — start one below")}
              </p>
            ) : null}
            {groups.map((group) => {
              const folded = app.collapsed.has(group.id);
              return (
              <section class={`group group-${group.id}`} key={group.id}>
                <h2 class="group-heading">
                  <button type="button" class="group-header" aria-expanded={!folded} onClick={() => app.toggleGroup(group.id)}>
                    <span class="pillar" aria-hidden="true" />
                    <span class="group-label">{language === "en" ? GROUP_LABEL[group.id][1] : GROUP_LABEL[group.id][0]}</span>
                    <span class="group-count mono">{group.sessions.length}</span>
                    <ChevronIcon open={!folded} />
                  </button>
                </h2>
                {folded ? null : group.sessions.map((session) => {
                  // Inside a project the folder is the filter itself; the branch says more.
                  const subline = filter ? [session.git_branch ?? "", session.model ?? ""].filter((p) => p.trim()).join(" · ") : sessionSubline(session);
                  const waiting = group.id === "needs-you";
                  const pinned = app.isPinned(session);
                  return (
                    <button type="button" class="session-row" key={session.id} onClick={() => open(session.id)}>
                      <span class="row-main">
                        <span class="row-title">{session.title || session.display_name || t("未命名会话", "Untitled")}</span>
                        {waiting ? (
                          <span class="row-status warn">
                            <span class="dot dot-warn" aria-hidden="true" />
                            {t("等待你的回应", "Waiting for you")}
                          </span>
                        ) : subline || pinned ? (
                          <span class="row-subline mono">
                            {pinned ? <PinMark label={t("已置顶", "Pinned")} /> : null}
                            {subline}
                          </span>
                        ) : null}
                      </span>
                      <span class="row-time">{relativeTime(lastActiveMs(session), now, language)}</span>
                    </button>
                  );
                })}
              </section>
              );
            })}
          </>
        )}
      </main>
      <button
        type="button"
        class="fab"
        aria-label={filter?.path ? t(`在 ${filter.label} 中新建会话`, `New chat in ${filter.label}`) : t("新会话", "New chat")}
        onClick={() => navigate({ name: "new" })}
      >
        <PlusIcon size={24} />
      </button>
      {projectsOpen ? (
        <>
          <div class="sheet-scrim" onClick={() => setProjectsOpen(false)} />
          <div class="picker-sheet" role="dialog" aria-modal="true" aria-label={t("按项目查看", "View by project")}>
            <div class="sheet-grip" aria-hidden="true" />
            <div class="picker-head">
              <h2 class="picker-title">{t("按项目查看", "View by project")}</h2>
              <button type="button" class="icon-button" aria-label={t("关闭", "Close")} onClick={() => setProjectsOpen(false)}>
                <CloseIcon />
              </button>
            </div>
            <div class="picker-list">
              <button
                type="button"
                class={`picker-row${filter ? "" : " selected"}`}
                aria-pressed={!filter}
                onClick={() => {
                  app.setProjectFilter(null);
                  setProjectsOpen(false);
                }}
              >
                <span class="picker-text">
                  <span class="picker-name">{t("全部会话", "All conversations")}</span>
                </span>
                <span class="picker-count mono">{app.sessions.filter((s) => !s.archived && isListable(s)).length}</span>
                <span class="picker-check">{filter ? null : <CheckIcon size={18} />}</span>
              </button>
              {projects.map((project) => {
                const selected = filter !== null && filter.path === project.path;
                const label = project.path === null ? noFolderLabel : (projectLabels.get(project.path) ?? project.label);
                return (
                  <button
                    type="button"
                    class={`picker-row${selected ? " selected" : ""}`}
                    aria-pressed={selected}
                    key={project.path ?? "__no_folder__"}
                    onClick={() => {
                      app.setProjectFilter({ path: project.path, label });
                      setProjectsOpen(false);
                    }}
                  >
                    <span class="picker-icon"><FolderIcon size={18} /></span>
                    <span class="picker-text">
                      <span class="picker-name">{label}</span>
                      {project.path ? (
                        // rtl so a long path is cut at its start; <bdi> keeps the path itself ltr.
                        <span class="picker-path mono">
                          <bdi>{project.path}</bdi>
                        </span>
                      ) : null}
                    </span>
                    <span class="picker-count mono">{project.count}</span>
                    <span class="picker-check">{selected ? <CheckIcon size={18} /> : null}</span>
                  </button>
                );
              })}
            </div>
            <p class="picker-note">{t("项目按会话所在的文件夹整理，只能在 Android 应用里管理。", "Projects follow each conversation's folder. Manage them in the Android app.")}</p>
          </div>
        </>
      ) : null}
    </div>
  );
}
