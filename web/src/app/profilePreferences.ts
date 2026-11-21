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
  const { account, device } = useApp();
  return useScopedProfilePreferences(account?.id, device?.deviceId);
}

export function useScopedProfilePreferences(accountId?: string, deviceId?: string) {
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
  const label = (name: string) => values[name]?.alias || name;
  function display(name: string, names: readonly string[]): string {
    const alias = label(name);
    return names.some(other => other !== name && label(other) === alias) ? alias + " · " + name : alias;
  }
  return {
    label, display,
    hidden: (name: string) => values[name]?.hidden === true,
    hasHidden: Object.values(values).some(value => value?.hidden),
    save,
  };
}

export function useProfileNames() {
  const { account, device, client } = useApp();
  const scope = JSON.stringify([account?.id, device?.deviceId]);
  const [loaded, setLoaded] = useState<{scope: string; names: string[]} | null>(null);
  useEffect(() => {
    if (!device) return;
    const controller = new AbortController();
    async function load(deviceId: string) {
      try {
        const result = await client.deviceApi<{profiles: {name: string; is_default?: boolean}[]}>(deviceId, "GET", "profiles", { signal: controller.signal });
        const names = result.profiles.map(p => p.is_default ? "default" : p.name).filter(Boolean);
        if (!controller.signal.aborted) setLoaded({scope, names: Array.from(new Set(names))});
      } catch { if (!controller.signal.aborted) setLoaded({scope, names: []}); }
    }
    void load(device.deviceId);
    return () => controller.abort();
  }, [scope, client]);
  return loaded?.scope === scope ? loaded.names : [];
}
