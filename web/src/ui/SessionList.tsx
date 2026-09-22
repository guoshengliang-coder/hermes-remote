import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { GatewayHttpError, hermesPaths } from "../api/gateway";
import { botSections, botSourceLabel, botStatusLine } from "../app/bots";
import { draftSessions } from "../app/drafts";
import { toAppError } from "../app/failures";
import { groupSessions, type GroupId } from "../app/grouping";
import { createListRefresher, type ListRefresher } from "../app/listRefresh";
import { defaultProjectPath } from "../app/localPrefs";
import { deriveProjects, disambiguatedLabels, inProject } from "../app/projects";
import { navigate } from "../app/router";
import { rowView } from "../app/rowStatus";
import { isBotSession, isListable } from "../app/sources";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import type { ProfileSessionsResponse, SessionListItem, SessionListResponse } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { HealthStrip } from "./HealthStrip";
import { ArchiveIcon, BotIcon, ChatIcon, CheckIcon, ChevronIcon, ChevronUpIcon, CloseIcon, FolderIcon, MoreIcon, PlusIcon, SearchIcon } from "./icons";
import { SearchView } from "./SearchView";
import { SessionActionSheet } from "./SessionActions";
import { SessionRow } from "./SessionRow";

// Session list (DESIGN §5.2 / §5.16 / §5.21, Android SessionsScreen): segments 会话 / 机器人 (only
// when there are bot conversations), groups needs-you → pinned → today → yesterday → last 7 days →
// older (each header collapses), rows with status line and trailing indicator, a reveal pill when
// something new needs you above the fold, search in the top bar, project filter and archive in the
// "more" menu, a health strip when the Relay or the Mac's Hermes is unwell, and the neutral FAB.

const GROUP_LABEL: Record<GroupId, [string, string]> = {
  "needs-you": ["需要你处理", "Needs you"],
  pinned: ["已置顶 · 仅此设备", "Pinned · this device"],
  today: ["今天", "Today"],
  yesterday: ["昨天", "Yesterday"],
  recent: ["前 7 天", "Previous 7 days"],
  older: ["更早", "Earlier"],
};

export async function loadSessions(client: ReturnType<typeof useApp>["client"], deviceId: string): Promise<SessionListItem[]> {
  try {
    const body = await client.deviceApi<ProfileSessionsResponse>(deviceId, "GET", `${hermesPaths.profileSessions}?limit=500&order=recent`);
    return Array.isArray(body?.sessions) ? body.sessions : [];
  } catch (error) {
    // Older Hermes without the cross-profile list: fall back to the default profile's.
    if (error instanceof GatewayHttpError && error.status === 404) {
      // Upstream `/api/sessions` rejects limit > 100 (422).
      const body = await client.deviceApi<SessionListResponse>(deviceId, "GET", `${hermesPaths.sessions}?limit=100&offset=0&order=recent`);
      return Array.isArray(body?.sessions) ? body.sessions : [];
    }
    throw error;
  }
}

function GroupHeader({ id, label, count, folded, onToggle }: { id: string; label: string; count: number; folded: boolean; onToggle: () => void }) {
  return (
    <h2 class="group-heading">
      <button type="button" class={`group-header group-header-${id}`} aria-expanded={!folded} onClick={onToggle}>
        <span class="pillar" aria-hidden="true" />
        <span class="group-label">{label}</span>
        <span class="group-count mono">{count}</span>
        <ChevronIcon open={!folded} />
      </button>
    </h2>
  );
}

export function SessionList() {
  const app = useApp();
  const { t, language, device, client } = app;
  const [loading, setLoading] = useState(app.sessions.length === 0);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const [query, setQuery] = useState("");
  const [submitNonce, setSubmitNonce] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [projectsOpen, setProjectsOpen] = useState(false);
  const [botFolded, setBotFolded] = useState<ReadonlySet<string>>(new Set());
  const [reveal, setReveal] = useState(0);
  const [actionFor, setActionFor] = useState<SessionListItem | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const deviceId = device?.deviceId ?? null;
  const offline = device ? device.connector?.online === false : false;
  const filter = app.projectFilter;
  const defaultProject = deviceId ? defaultProjectPath(deviceId) : null;

  async function refresh() {
    if (!deviceId) return;
    setError(null);
    if (app.sessions.length) setRefreshing(true);
    try {
      const rows = await loadSessions(client, deviceId);
      app.setSessions(rows);
      setNow(Date.now());
    } catch (e) {
      setError(toAppError(e, "device"));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  // Every list request goes through one single-flight refresher (HG-104, app/listRefresh.ts).
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  const refresher = useRef<ListRefresher | null>(null);

  useEffect(() => {
    const scheduler = createListRefresher(() => refreshRef.current());
    refresher.current = scheduler;
    scheduler.now();
    const onVisible = () => {
      if (document.visibilityState === "visible") scheduler.now();
    };
    document.addEventListener("visibilitychange", onVisible);
    const tick = setInterval(() => setNow(Date.now()), 60_000);
    return () => {
      scheduler.dispose();
      if (refresher.current === scheduler) refresher.current = null;
      document.removeEventListener("visibilitychange", onVisible);
      clearInterval(tick);
    };
  }, [deviceId]);

  // A lifecycle event usually means the list moved (new title, new activity): refetch, debounced —
  // the cursor can move on every inbox poll.
  useEffect(() => {
    if (app.inbox.primed) refresher.current?.soon();
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

  // Something new needs you while the reader is scrolled away from the top: a pill, not a jump
  // (DESIGN §5.2 keep-item, Android NeedsYouReveal). Near the top it is simply visible.
  const seenNeedsYou = useRef<ReadonlySet<string>>(app.needsYou);
  useEffect(() => {
    const added = [...app.needsYou].filter((id) => !seenNeedsYou.current.has(id)).length;
    seenNeedsYou.current = app.needsYou;
    if (added && window.scrollY > 120) setReveal((n) => n + added);
  }, [app.needsYou]);
  useEffect(() => {
    if (!reveal) return;
    const onScroll = () => window.scrollY < 40 && setReveal(0);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [reveal]);

  const listable = useMemo(() => app.sessions.filter((s) => isListable(s) && !isBotSession(s)), [app.sessions]);
  const bots = useMemo(() => botSections(app.sessions), [app.sessions]);
  const showBots = bots.length > 0;
  const segment = showBots ? app.listSegment : "chats";

  const rows = useMemo(() => {
    // A session waiting on the user but not in the loaded page still gets a row.
    const known = new Set(app.sessions.map((s) => s.id));
    const extra: SessionListItem[] = [];
    for (const id of app.needsYou) {
      if (known.has(id)) continue;
      const entry = app.inbox.latest[id];
      extra.push({ id, title: entry?.title ?? null, last_active: entry ? entry.occurredAtMs / 1000 : null });
    }
    return [...extra, ...listable];
  }, [listable, app.needsYou, app.inbox.latest]);

  const projects = useMemo(() => deriveProjects(listable), [listable]);
  const projectLabels = useMemo(() => disambiguatedLabels(projects), [projects]);
  const shown = useMemo(() => (filter ? rows.filter((r) => app.needsYou.has(r.id) || inProject(r, filter.path)) : rows), [rows, filter, app.needsYou]);
  const groups = useMemo(() => groupSessions(shown, app.needsYou, now, app.isPinned), [shown, app.needsYou, now, app.isPinned]);
  const drafts = useMemo(() => (deviceId ? draftSessions(deviceId) : new Set<string>()), [deviceId, app.sessions]);
  const noFolderLabel = t("未指定文件夹", "No folder");
  const projectName = (path: string | null, fallback: string) =>
    path === null ? noFolderLabel : defaultProject && path === defaultProject ? t("默认项目", "Default project") : (projectLabels.get(path) ?? fallback);

  function open(id: string, inChatQuery: string | null = null) {
    app.setChatSearchSeed(inChatQuery);
    navigate({ name: "chat", sessionId: id });
  }

  function closeSearch() {
    setSearching(false);
    setQuery("");
  }

  return (
    <div class="page list-page">
      <header class="topbar">
        {searching ? (
          <div class="topbar-row search-row">
            <button type="button" class="icon-button" aria-label={t("关闭搜索", "Close search")} onClick={closeSearch}>
              <CloseIcon />
            </button>
            <input
              ref={searchRef}
              class="search-input"
              type="search"
              enterkeyhint="search"
              value={query}
              placeholder={t("搜索会话与消息…", "Search chats and messages…")}
              onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") setSubmitNonce((n) => n + 1);
              }}
            />
          </div>
        ) : (
          <div class="topbar-row">
            <span class="topbar-spacer" />
            <span class="topbar-spacer" />
            <h1 class="topbar-title">{t("会话", "Chats")}</h1>
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
              <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); setProjectsOpen(true); }}>
                <FolderIcon size={18} />
                {t("按项目查看", "View by project")}
              </button>
              <button type="button" role="menuitem" class="menu-item with-icon" onClick={() => { setMenuOpen(false); navigate({ name: "archived" }); }}>
                <ArchiveIcon size={18} />
                {t("已归档", "Archived")}
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
        {!searching && showBots ? (
          <div class="segments" role="tablist">
            <button type="button" role="tab" class={`segment${segment === "chats" ? " selected" : ""}`} aria-selected={segment === "chats"} onClick={() => app.setListSegment("chats")}>
              <ChatIcon size={18} />
              {t("会话", "Chats")}
            </button>
            <button type="button" role="tab" class={`segment${segment === "bots" ? " selected" : ""}`} aria-selected={segment === "bots"} onClick={() => app.setListSegment("bots")}>
              <BotIcon size={18} />
              {t("机器人", "Bots")}
            </button>
          </div>
        ) : null}
        {filter && !searching && segment === "chats" ? (
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
        {refreshing ? <div class="top-progress" aria-hidden="true" /> : null}
      </header>
      <main class="content">
        {!searching ? <HealthStrip /> : null}
        {offline ? <ErrorNotice error={appError("HR-CONN-005")} language={language} onRetry={() => refresher.current?.now()} variant="banner" /> : null}
        {searching ? (
          <SearchView
            query={query}
            submitNonce={submitNonce}
            defaultProject={defaultProject}
            onOpen={open}
            onPickRecent={(q) => {
              setQuery(q);
              setSubmitNonce((n) => n + 1);
            }}
          />
        ) : segment === "bots" ? (
          <>
            {bots.map((section) => {
              const folded = botFolded.has(section.source);
              return (
                <section class="group group-channel" key={section.source}>
                  <GroupHeader
                    id="channel"
                    label={botSourceLabel(section.source)}
                    count={section.sessions.length}
                    folded={folded}
                    onToggle={() =>
                      setBotFolded((prev) => {
                        const next = new Set(prev);
                        if (!next.delete(section.source)) next.add(section.source);
                        return next;
                      })
                    }
                  />
                  {folded
                    ? null
                    : section.sessions.map((s) => (
                        <SessionRow key={s.id} session={s} now={now} pinned={app.isPinned(s)} bot={{ statusLine: botStatusLine(s, now, language) }} onOpen={() => open(s.id)} onLongPress={() => setActionFor(s)} />
                      ))}
                </section>
              );
            })}
          </>
        ) : (
          <>
            {error && !app.sessions.length ? <ErrorNotice error={error} language={language} onRetry={() => refresher.current?.now()} /> : null}
            {loading && !error ? <div class="center-spinner"><span class="spinner" /></div> : null}
            {!loading && !error && groups.length === 0 ? (
              <div class="empty-state">
                <p class="empty-line">
                  {filter ? t("这个项目里没有会话，点右下角在这里新建", "No conversations in this project — start one below") : t("暂无会话", "No sessions yet")}
                </p>
                {!filter ? <p class="empty-sub">{t("点击右下角的加号开始对话。", "Tap + at the bottom right to start chatting.")}</p> : null}
              </div>
            ) : null}
            {groups.map((group) => {
              const folded = app.collapsed.has(group.id);
              return (
                <section class={`group group-${group.id}`} key={group.id}>
                  <GroupHeader id={group.id} label={language === "en" ? GROUP_LABEL[group.id][1] : GROUP_LABEL[group.id][0]} count={group.sessions.length} folded={folded} onToggle={() => app.toggleGroup(group.id)} />
                  {folded
                    ? null
                    : group.sessions.map((session) => (
                        <SessionRow
                          key={session.id}
                          session={session}
                          now={now}
                          view={rowView(session.id, app.inbox, app.needsYou)}
                          pinned={app.isPinned(session)}
                          draft={drafts.has(session.id)}
                          inProject={Boolean(filter)}
                          defaultProject={defaultProject}
                          onOpen={() => open(session.id)}
                          onLongPress={app.needsYou.has(session.id) && !app.sessions.some((s) => s.id === session.id) ? undefined : () => setActionFor(session)}
                        />
                      ))}
                </section>
              );
            })}
          </>
        )}
      </main>
      {reveal && !searching ? (
        <button
          type="button"
          class="reveal-pill"
          onClick={() => {
            window.scrollTo({ top: 0, behavior: "smooth" });
            setReveal(0);
          }}
        >
          <ChevronUpIcon size={16} />
          {t(`${reveal} 个会话需要处理`, `${reveal} need${reveal === 1 ? "s" : ""} you`)}
        </button>
      ) : null}
      {segment === "chats" && !searching ? (
        <button
          type="button"
          class="fab"
          aria-label={filter?.path ? t(`在 ${filter.label} 中新建会话`, `New chat in ${filter.label}`) : t("新会话", "New chat")}
          onClick={() => navigate({ name: "new" })}
        >
          <PlusIcon size={24} />
        </button>
      ) : null}
      {actionFor ? (
        <SessionActionSheet
          session={actionFor}
          busy={rowView(actionFor.id, app.inbox, app.needsYou).trailing === "spinner"}
          onClose={() => setActionFor(null)}
        />
      ) : null}
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
                <span class="picker-count mono">{listable.filter((s) => !s.archived).length}</span>
                <span class="picker-check">{filter ? null : <CheckIcon size={18} />}</span>
              </button>
              {projects.map((project) => {
                const selected = filter !== null && filter.path === project.path;
                const label = projectName(project.path, project.label);
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
                    <span class="picker-icon">
                      <FolderIcon size={18} />
                    </span>
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
