import { GatewayHttpError, hermesPaths, type GatewayClient } from "../api/gateway";
import type { ProfileSessionsResponse, SessionListItem, SessionListResponse } from "../hermes/types";
import { pinToken } from "./pins";

export async function loadProfileSessionPages(
  client: GatewayClient, deviceId: string, signal: AbortSignal,
  archivedOnly = false,
): Promise<SessionListItem[]> {
  const limit = 500;
  const base = hermesPaths.profileSessions + "?limit=" + limit + "&order=recent" + (archivedOnly ? "&archived=only" : "");
  const rows = new Map<string, SessionListItem>();
  for (let offset = 0; ; offset += limit) {
    const path = offset ? base + "&offset=" + offset : base;
    let body: ProfileSessionsResponse;
    try {
      body = await client.deviceApi<ProfileSessionsResponse>(deviceId, "GET", path, { signal });
    } catch (error) {
      if (archivedOnly || offset !== 0 || signal.aborted || !(error instanceof GatewayHttpError) || error.status !== 404) throw error;
      const fallback = await client.deviceApi<SessionListResponse>(deviceId, "GET",
        hermesPaths.sessions + "?limit=100&offset=0&order=recent", { signal });
      return Array.isArray(fallback?.sessions) ? fallback.sessions : [];
    }
    const page = body?.sessions ?? [];
    const before = rows.size;
    for (const row of page) rows.set(pinToken(row), row);
    if (page.length < limit || offset + limit >= (body.total ?? Infinity)) break;
    if (body.total == null && rows.size === before) break;
  }
  return Array.from(rows.values());
}
