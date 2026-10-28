import { useEffect, useRef, useState } from "preact/hooks";
import { GatewayHttpError, type GatewayClient } from "../api/gateway";
import { appError, type AppError } from "../errors";
import { parseInstanceModels, preferenceMutation, type InstanceModelSnapshot, type PreferenceField } from "../hermes/instanceModelPreferences";

export function useInstanceModels(client: GatewayClient, deviceId: string, supported: boolean, writable: boolean) {
  const [snapshot, setSnapshot] = useState<InstanceModelSnapshot | null>(null);
  const current = useRef<InstanceModelSnapshot | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "legacy" | "error">(supported ? "loading" : "legacy");
  const [error, setError] = useState<AppError | null>(null);
  const [pending, setPending] = useState(false);
  const alive = useRef(true);
  const busy = useRef(false);
  const serial = useRef(0);
  const fetchSnapshot = () => client.deviceApi(deviceId, "GET", "model/preferences").then(parseInstanceModels);
  const install = (value: InstanceModelSnapshot) => {
    if (!alive.current) return;
    if (current.current?.instanceId === value.instanceId && BigInt(current.current.revision) > BigInt(value.revision)) return;
    current.current = value; setSnapshot(value); setStatus("ready");
    setError(previous => previous?.code === "HR-MODEL-001" ? null : previous);
  };
  async function refresh() {
    if (!supported || busy.current) return;
    const ticket = ++serial.current;
    try { const next = await fetchSnapshot(); if (ticket === serial.current) install(next); }
    catch (e) {
      if (!alive.current || ticket !== serial.current) return;
      if (e instanceof GatewayHttpError && [404, 405].includes(e.status)) setStatus("legacy");
      else { setStatus("error"); setError(appError("HR-MODEL-001")); }
    }
  }
  useEffect(() => {
    alive.current = true;
    current.current = null; setSnapshot(null); setStatus(supported ? "loading" : "legacy");
    if (supported) void refresh();
    const timer = setInterval(() => { if (document.visibilityState === "visible") void refresh(); }, 5000);
    return () => { alive.current = false; serial.current++; clearInterval(timer); };
  }, [client, deviceId, supported]);
  async function set(provider: string, model: string, field: PreferenceField, value: boolean | null, importing = false): Promise<boolean> {
    const before = current.current;
    if (!before || busy.current || !alive.current) return false;
    if (!writable) { setError(appError("HR-MODEL-003")); return false; }
    const ticket = ++serial.current;
    busy.current = true; setPending(true); setError(null);
    try {
      const updated = parseInstanceModels(await client.deviceApi(deviceId, "POST", "model/preferences", {
        body: preferenceMutation(before, provider, model, field, value, importing),
      }));
      if (!alive.current || ticket !== serial.current) return false;
      if (updated.instanceId !== before.instanceId) throw new Error("Instance changed");
      install(updated); return alive.current;
    } catch (e) {
      if (alive.current) setError(appError(e instanceof GatewayHttpError && e.status === 409 ? "HR-MODEL-002" :
        e instanceof GatewayHttpError && e.status === 403 ? "HR-MODEL-003" : "HR-MODEL-001"));
      // A lost reply is ambiguous. Read the authority; never replay a mutation automatically.
      try { const next = await fetchSnapshot(); if (ticket === serial.current) install(next); } catch { /* preserve last acknowledged snapshot */ }
      return false;
    } finally { busy.current = false; if (alive.current) setPending(false); }
  }
  return { snapshot, status, pending, error, writable, refresh, set };
}
