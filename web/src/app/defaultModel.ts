import { useEffect, useState } from "preact/hooks";
import type { DefaultModelResponse, GatewayClient } from "../api/gateway";
import { appError, type AppError } from "../errors";

type Snapshot = { key: string; model: DefaultModelResponse | null; error: AppError | null };

/** A Mac/profile-scoped read. A previous Mac's value is never shown while a new read is pending. */
export function useDefaultModel(client: GatewayClient, deviceId: string, profile: string | null, enabled: boolean) {
  const key = `${deviceId.length}:${deviceId}:${profile === null ? "-" : `+${profile.length}:${profile}`}`;
  const [snapshot, setSnapshot] = useState<Snapshot>({ key: "", model: null, error: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!enabled || !deviceId) return;
    let live = true;
    setSnapshot({ key, model: null, error: null });
    client.defaultModel(deviceId, profile).then(
      (body) => {
        if (!live) return;
        if (typeof body?.model !== "string" || !body.model.trim() || typeof body?.provider !== "string" || !body.provider.trim()) {
          setSnapshot({ key, model: null, error: appError("HR-WEB-009") });
          return;
        }
        setSnapshot({ key, model: { model: body.model, provider: body.provider }, error: null });
      },
      () => { if (live) setSnapshot({ key, model: null, error: appError("HR-WEB-009") }); },
    );
    return () => { live = false; };
  }, [client, key, attempt, enabled]);

  const current = enabled && snapshot.key === key ? snapshot : null;
  return { model: current?.model ?? null, error: current?.error ?? null, loading: enabled && !current, retry: () => setAttempt((n) => n + 1) };
}
