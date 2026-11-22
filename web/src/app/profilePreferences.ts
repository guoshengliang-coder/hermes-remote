import { useEffect, useMemo, useState } from "preact/hooks";
import { useApp } from "./store";

interface Preference { alias?: string; hidden?: boolean }
type Preferences = Record<string, Preference>;
const changed = "hermes-go:profile-display";

function read(key: string): Preferences {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(key) ?? "{}");
    if (!stored || typeof stored !== "object" || Array.isArray(stored)) return {};
    const entries = Object.entries(stored).flatMap(([name, value]) =>
      value && typeof value === "object" ? [[name, {
        alias: typeof value.alias === "string" ? value.alias : undefined,
        hidden: value.hidden === true,
      }]] : []);
    return Object.fromEntries(entries);
  } catch {
    return {};
  }
}

export function useProfilePreferences() {
  const { account, device, profileCatalog } = useApp();
  return useScopedProfilePreferences(account?.id, device?.deviceId, profileCatalog);
}

export function useScopedProfilePreferences(accountId?: string, deviceId?: string, catalog: Record<string,string> = {}) {
  const key = "hermes-go.profile-display." + JSON.stringify([accountId, deviceId]);
  const [revision, refresh] = useState(0);
  useEffect(() => {
    const update = () => refresh(value => value + 1);
    window.addEventListener(changed, update);
    window.addEventListener("storage", update);
    return () => {
      window.removeEventListener(changed, update);
      window.removeEventListener("storage", update);
    };
  }, []);
  const values = useMemo(() => read(key), [key, revision]);
  function save(name: string, patch: Preference) {
    if (!deviceId || !accountId) return;
    const latest = read(key);
    localStorage.setItem(key, JSON.stringify({ ...latest, [name]: { ...latest[name], ...patch } }));
    window.dispatchEvent(new Event(changed));
  }
  const label = (name: string) => values[name]?.alias || catalog[name] || name;
  function display(name: string, names: readonly string[] = Object.keys(catalog)): string {
    const alias = label(name);
    return names.some(other => other !== name && label(other) === alias) ? alias + " · " + name : alias;
  }
  return {
    label, display, alias: (name: string) => values[name]?.alias ?? "",
    hidden: (name: string) => values[name]?.hidden === true,
    hasHidden: Object.values(values).some(value => value?.hidden),
    save,
  };
}

export function useProfileNames() {
  return Object.keys(useApp().profileCatalog ?? {});
}

interface ProfileRecord { name: string; is_default?: boolean; display_name?: string; bot_title?: string }

export function catalogLabels(profiles: readonly ProfileRecord[]): Record<string,string> {
  return Object.fromEntries(profiles.filter(p => p.name).map(p => [
    p.is_default ? "default" : p.name, p.display_name?.trim() || p.bot_title?.trim() || p.name
  ]));
}

export function useProfileCatalog(client: import("./store").AppContextValue["client"], accountId?: string, deviceId?: string) {
  const scope = JSON.stringify([accountId, deviceId]);
  const [loaded, setLoaded] = useState<{scope: string; labels: Record<string,string>} | null>(null);
  useEffect(() => {
    if (!accountId || !deviceId) return;
    const controller = new AbortController();
    void client.deviceApi<{profiles: ProfileRecord[]}>(deviceId, "GET", "profiles", { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setLoaded({scope, labels: catalogLabels(result.profiles)}); })
      .catch(() => { if (!controller.signal.aborted) setLoaded(null); });
    return () => controller.abort();
  }, [scope, client]);
  return loaded?.scope === scope ? loaded.labels : {};
}
