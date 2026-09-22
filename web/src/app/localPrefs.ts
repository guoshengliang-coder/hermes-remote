// Small per-Mac preferences kept in this browser and cleared on sign-out: recent searches (Android
// DataStore `recent_searches`, 8 newest) and the default project's folder, learned from the cwd a
// top-level `session.create` lands in (Android `project_prefs.default_project_path`).

const RECENT = "hermes-go.recentSearches.";
const DEFAULT_PROJECT = "hermes-go.defaultProject.";
const MAX_RECENT = 8;
const MODELS = "hermes-go.models.";
const MAX_MODEL_RECENTS = 5;

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
      if (k && (k.startsWith(RECENT) || k.startsWith(DEFAULT_PROJECT) || k.startsWith(MODELS))) doomed.push(k);
    }
    for (const k of doomed) localStorage.removeItem(k);
  } catch {
    /* nothing stored */
  }
}

// Model sheet (Android ModelRecentsStore / favourites / reasoning presets): device-local, per Mac.
// Keys are `provider/model`.

export function modelKey(provider: string, model: string): string {
  return `${provider}/${model}`;
}

interface ModelPrefs {
  recents: string[];
  favorites: string[];
  presets: Record<string, string>;
}

function readModels(deviceId: string): ModelPrefs {
  try {
    const v = JSON.parse(localStorage.getItem(MODELS + deviceId) ?? "{}") as Partial<ModelPrefs>;
    return {
      recents: Array.isArray(v.recents) ? v.recents.filter((x) => typeof x === "string") : [],
      favorites: Array.isArray(v.favorites) ? v.favorites.filter((x) => typeof x === "string") : [],
      presets: v.presets && typeof v.presets === "object" ? Object.fromEntries(Object.entries(v.presets).filter(([, x]) => typeof x === "string")) : {},
    };
  } catch {
    return { recents: [], favorites: [], presets: {} };
  }
}

function writeModels(deviceId: string, prefs: ModelPrefs): void {
  write(MODELS + deviceId, JSON.stringify(prefs));
}

export function modelPrefs(deviceId: string): ModelPrefs {
  return readModels(deviceId);
}

export function recordModelUse(deviceId: string, key: string): void {
  const prefs = readModels(deviceId);
  prefs.recents = [key, ...prefs.recents.filter((k) => k !== key)].slice(0, MAX_MODEL_RECENTS);
  writeModels(deviceId, prefs);
}

export function toggleFavoriteModel(deviceId: string, key: string): string[] {
  const prefs = readModels(deviceId);
  prefs.favorites = prefs.favorites.includes(key) ? prefs.favorites.filter((k) => k !== key) : [...prefs.favorites, key];
  writeModels(deviceId, prefs);
  return prefs.favorites;
}

/** The reasoning effort last chosen for a model, re-applied after switching to it. */
export function rememberReasoning(deviceId: string, key: string, value: string): void {
  const prefs = readModels(deviceId);
  prefs.presets[key] = value;
  writeModels(deviceId, prefs);
}
