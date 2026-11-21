import { loadProfileSessionPages } from "../app/profileSessionPages";
import { useProfileNames, useProfilePreferences } from "../app/profilePreferences";
import { explicitProfile } from "../app/profile";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { hermesPaths } from "../api/gateway";
import { toAppError } from "../app/failures";
import { lastActiveMs, relativeTime } from "../app/grouping";
import { addRecentSearch, clearRecentSearches, recentSearches, removeRecentSearch } from "../app/localPrefs";
import { centerSnippet, titleMatches } from "../app/searchText";
import { BOT_SOURCES, INTERNAL_SESSION_SOURCES, isListable } from "../app/sources";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { buildSearchQuery } from "../hermes/search-query";
import type { SessionListItem } from "../hermes/types";
import { ErrorNotice } from "./ErrorNotice";
import { Highlighted } from "./Highlighted";
import { CloseIcon } from "./icons";
import { projectLabelOf, SessionRow } from "./SessionRow";

// Search (DESIGN §5.2 搜索页, Android SearchScreen.kt): title matches appear instantly from the live
// and archived lists; message search runs 450 ms after typing stops (≥ 2 characters, or at once on
// the keyboard's search key). States: recent searches / pending / searching / results / empty /
// failed (HR-SEARCH-001 with Retry — the title matches stay).

interface SearchHit {
  profile?: string;
  session_id?: string;
  id?: string;
  title?: string | null;
  snippet?: string | null;
  last_active?: number | null;
  archived?: boolean;
  cwd?: string | null;
  git_repo_root?: string | null;
}

const MESSAGE_DEBOUNCE_MS = 450;
const EXCLUDED = [...INTERNAL_SESSION_SOURCES, ...BOT_SOURCES];

type MessageState = { kind: "idle" } | { kind: "pending" } | { kind: "searching" } | { kind: "done"; hits: SearchHit[] } | { kind: "failed"; error: AppError };

export function SearchView({
  query,
  submitNonce,
  defaultProject,
  onOpen,
  onPickRecent,
}: {
  query: string;
  /** Bumped when the keyboard's search key is pressed: search now. */
  submitNonce: number;
  defaultProject: string | null;
  onOpen: (sessionId: string, inChatQuery: string | null, profile?: string | null) => void;
  onPickRecent: (query: string) => void;
}) {
  const prefs = useProfilePreferences();
  const visibleProfiles = useProfileNames().filter(name => !prefs.hidden(name));
  const profileScope = JSON.stringify(visibleProfiles);
  const app = useApp();
  const { t, language, client, device } = app;
  const deviceId = device?.deviceId ?? null;
  const [archived, setArchived] = useState<SessionListItem[]>([]);
  const [message, setMessage] = useState<MessageState>({ kind: "idle" });
  const [recent, setRecent] = useState<string[]>(() => (deviceId ? recentSearches(deviceId) : []));
  const [attempt, setAttempt] = useState(0);
  const lastSubmit = useRef(submitNonce);
  const lastAttempt = useRef(attempt);
  const now = Date.now();
  const q = query.trim();

  // Archived rows are searched too (Android loads `archived=only` alongside the live list).
  useEffect(() => {
    if (!deviceId) return;
    let live = true;
    const controller = new AbortController();
    loadProfileSessionPages(client, deviceId, controller.signal, true)
      .then((items) => live && setArchived(items), () => undefined);
    return () => {
      live = false;
      controller.abort();
    };
  }, [deviceId]);

  const titleHits = useMemo(() => {
    if (!q) return [];
    const seen = new Set<string>();
    return [...app.sessions.filter(isListable), ...archived.map((s) => ({ ...s, archived: true }))].filter((s) => {
      const key = JSON.stringify([explicitProfile(s), s.id]);
      if (seen.has(key) || prefs.hidden(explicitProfile(s) ?? "default")) return false;
      seen.add(key);
      return titleMatches(s.title || s.display_name || "", projectLabelOf(s, defaultProject), q);
    });
  }, [q, app.sessions, archived, defaultProject, prefs.hidden]);

  useEffect(() => {
    if (!deviceId || q.length < 2) {
      setMessage({ kind: "idle" });
      return;
    }
    const built = buildSearchQuery(q);
    if (!built) return;
    const controller = new AbortController();
    setMessage({ kind: "pending" });
    const run = async () => {
      setMessage({ kind: "searching" });
      try {
        const groups = await Promise.all(visibleProfiles.map(async profile => {
          const body = await client.deviceApi<{results?: SearchHit[]; sessions?: SearchHit[]}>(deviceId, "GET",
            `${hermesPaths.search(built, EXCLUDED)}&limit=30&profile=${encodeURIComponent(profile)}`, {signal: controller.signal});
          return (body.results ?? body.sessions ?? []).map(hit => ({...hit, profile}));
        }));
        if (controller.signal.aborted) return;
        const hits = groups.flat().sort((a, b) => (b.last_active ?? 0) - (a.last_active ?? 0));
        setMessage({ kind: "done", hits });
        setRecent(addRecentSearch(deviceId, q));
      } catch (e) {
        if (controller.signal.aborted) return;
        const error = toAppError(e, "device");
        setMessage({ kind: "failed", error: { ...appError("HR-SEARCH-001", error.details ?? error.code), retryable: true } });
      }
    };
    // The keyboard's search key (a new nonce) or Retry searches at once; typing waits.
    const immediate = submitNonce !== lastSubmit.current || attempt !== lastAttempt.current;
    lastSubmit.current = submitNonce;
    lastAttempt.current = attempt;
    const timer = setTimeout(() => void run(), immediate ? 0 : MESSAGE_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [q, deviceId, submitNonce, attempt, profileScope]);

  function open(id: string, inChat: boolean, profile?: string | null) {
    if (deviceId && q) setRecent(addRecentSearch(deviceId, q));
    onOpen(id, inChat ? q : null, profile);
  }

  if (!q) {
    return (
      <section class="search-results">
        {recent.length ? (
          <>
            <div class="search-section-head">
              <span>{t("最近搜索", "Recent searches")}</span>
              <button
                type="button"
                class="text-button small"
                onClick={() => {
                  if (deviceId) clearRecentSearches(deviceId);
                  setRecent([]);
                }}
              >
                {t("清除", "Clear")}
              </button>
            </div>
            {recent.map((r) => (
              <div class="recent-row" key={r}>
                <button type="button" class="recent-text" onClick={() => onPickRecent(r)}>
                  {r}
                </button>
                <button type="button" class="icon-button" aria-label={t("删除这条搜索记录", "Remove this search")} onClick={() => deviceId && setRecent(removeRecentSearch(deviceId, r))}>
                  <CloseIcon size={16} />
                </button>
              </div>
            ))}
          </>
        ) : (
          <p class="empty-line">{t("搜索会话标题与消息内容", "Search conversation titles and messages")}</p>
        )}
      </section>
    );
  }

  return (
    <section class="search-results">
      {titleHits.length ? <div class="search-section-head">{t("会话", "Conversations")}</div> : null}
      {titleHits.map((s) => (
        <SessionRow
          showTime
          key={JSON.stringify([explicitProfile(s), s.id])}
          session={s}
          now={now}
          pinned={app.isPinned(s)}
          archived={s.archived}
          defaultProject={defaultProject}
          query={q}
          onOpen={() => open(s.id, false, explicitProfile(s))}
        />
      ))}
      <div class="search-section-head">
        <span>{t("消息", "Messages")}</span>
        {message.kind === "searching" ? <span class="search-busy">{t("搜索中…", "Searching…")}</span> : null}
      </div>
      {message.kind === "searching" ? <div class="line-progress" aria-hidden="true" /> : null}
      {message.kind === "idle" && q.length < 2 ? <p class="empty-line small">{t("再输入一个字开始搜索消息", "Type one more character to search messages")}</p> : null}
      {message.kind === "failed" ? <ErrorNotice error={message.error} language={language} onRetry={() => setAttempt(attempt + 1)} variant="inline" /> : null}
      {message.kind === "done" && message.hits.length === 0 ? <p class="empty-line small">{t(`消息中没有匹配“${q}”。`, `No messages match "${q}".`)}</p> : null}
      {message.kind === "done"
        ? message.hits.map((hit, i) => {
            const id = hit.session_id ?? hit.id ?? "";
            if (!id) return null;
            const project = projectLabelOf(hit, defaultProject);
            if (!hit.profile || prefs.hidden(hit.profile)) return null;
            const matches = app.sessions.filter(s => s.id === id && explicitProfile(s) === hit.profile);
            const row = matches.length === 1 ? matches[0] : undefined;
            return (
              <button type="button" class="session-row with-divider hit-row" key={`${id}-${i}`} onClick={() => open(id, true, hit.profile)}>
                <span class="row-main">
                  <span class="row-title">
                    {hit.title || row?.title || t("未命名会话", "Untitled")}
                    {hit.archived ? <span class="row-tag">{t("已归档", "Archived")}</span> : null}
                  </span>
                  {hit.snippet ? (
                    <span class="row-snippet">
                      <Highlighted text={centerSnippet(hit.snippet, q)} query={q} />
                    </span>
                  ) : null}
                  {project ? <span class="row-subline mono">{project}</span> : null}
                </span>
                <span class="row-side">
                  <span class="row-time">{relativeTime(lastActiveMs(hit), now, language)}</span>
                </span>
              </button>
            );
          })
        : null}
    </section>
  );
}
