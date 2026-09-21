import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { GatewayHttpError, hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { groupSessions, lastActiveMs, relativeTime, sessionSubline, type GroupId } from "../app/grouping";
import { navigate } from "../app/router";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { buildSearchQuery } from "../hermes/search-query";
import type { ProfileSessionsResponse, SessionListItem, SessionListResponse } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { CloseIcon, MoreIcon, PlusIcon, SearchIcon } from "./icons";

// Session list (DESIGN §5.2 simplified for the Web, §5.21): rows on the paper surface, groups
// needs-you → today → yesterday → last 7 days → older, search in the top bar, neutral FAB.

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
    return [...extra, ...app.sessions];
  }, [app.sessions, app.needsYou, app.inbox.latest]);

  const groups = useMemo(() => groupSessions(rows, app.needsYou, now), [rows, app.needsYou, now]);

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
              <button type="button" role="menuitem" class="menu-item" onClick={() => { setMenuOpen(false); app.chooseDevice(); }}>
                {t("切换 Mac", "Switch Mac")}
              </button>
              <button type="button" role="menuitem" class="menu-item danger" onClick={() => { setMenuOpen(false); void app.signOut(); }}>
                {t("退出登录", "Sign out")}
              </button>
            </div>
          </>
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
              <p class="empty-line">{t("还没有会话，点右下角开始", "No conversations yet — start one below")}</p>
            ) : null}
            {groups.map((group) => (
              <section class={`group group-${group.id}`} key={group.id}>
                <h2 class="group-header">
                  <span class="pillar" aria-hidden="true" />
                  <span class="group-label">{language === "en" ? GROUP_LABEL[group.id][1] : GROUP_LABEL[group.id][0]}</span>
                  <span class="group-count mono">{group.sessions.length}</span>
                </h2>
                {group.sessions.map((session) => {
                  const subline = sessionSubline(session);
                  const waiting = group.id === "needs-you";
                  return (
                    <button type="button" class="session-row" key={session.id} onClick={() => open(session.id)}>
                      <span class="row-main">
                        <span class="row-title">{session.title || session.display_name || t("未命名会话", "Untitled")}</span>
                        {waiting ? (
                          <span class="row-status warn">
                            <span class="dot dot-warn" aria-hidden="true" />
                            {t("等待你的回应", "Waiting for you")}
                          </span>
                        ) : subline ? (
                          <span class="row-subline mono">{subline}</span>
                        ) : null}
                      </span>
                      <span class="row-time">{relativeTime(lastActiveMs(session), now, language)}</span>
                    </button>
                  );
                })}
              </section>
            ))}
          </>
        )}
      </main>
      <button type="button" class="fab" aria-label={t("新会话", "New chat")} onClick={() => navigate({ name: "new" })}>
        <PlusIcon size={24} />
      </button>
    </div>
  );
}
