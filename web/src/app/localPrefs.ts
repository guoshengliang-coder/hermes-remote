// Small per-Mac preferences kept in this browser and cleared on sign-out: recent searches (Android
// DataStore `recent_searches`, 8 newest) and the default project's folder, learned from the cwd a
// top-level `session.create` lands in (Android `project_prefs.default_project_path`).

const RECENT = "hermes-go.recentSearches.";
const DEFAULT_PROJECT = "hermes-go.defaultProject.";
const MAX_RECENT = 8;

function readList(key: string): string[] {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(key) ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* private mode */
  }
}

export function recentSearches(deviceId: string): string[] {
  return readList(RECENT + deviceId);
}

export function addRecentSearch(deviceId: string, query: string): string[] {
  const q = query.trim();
  if (!q) return recentSearches(deviceId);
  const next = [q, ...recentSearches(deviceId).filter((x) => x !== q)].slice(0, MAX_RECENT);
  write(RECENT + deviceId, JSON.stringify(next));
  return next;
}

export function removeRecentSearch(deviceId: string, query: string): string[] {
  const next = recentSearches(deviceId).filter((x) => x !== query);
  write(RECENT + deviceId, next.length ? JSON.stringify(next) : null);
  return next;
}

export function clearRecentSearches(deviceId: string): void {
  write(RECENT + deviceId, null);
}

export function defaultProjectPath(deviceId: string): string | null {
  try {
    return localStorage.getItem(DEFAULT_PROJECT + deviceId);
  } catch {
    return null;
  }
}

export function rememberDefaultProject(deviceId: string, cwd: string | null | undefined): void {
  const path = cwd?.trim().replace(/[/\\]+$/, "");
  if (path) write(DEFAULT_PROJECT + deviceId, path);
}

/** Sign-out: every per-Mac preference in this browser. */
export function clearLocalPrefs(): void {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && (k.startsWith(RECENT) || k.startsWith(DEFAULT_PROJECT))) doomed.push(k);
    }
    for (const k of doomed) localStorage.removeItem(k);
  } catch {
    /* nothing stored */
  }
}
