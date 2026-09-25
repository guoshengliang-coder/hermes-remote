import { boundedResponseBody } from "./hermes-auth.js";

const MESSAGES = /^\/api\/sessions\/[^/]+\/messages$/;
const MAX_PAGE_BYTES = 8 * 1024 * 1024;
const PAGE_SIZE = 100;
const PREVIEW_CHARS = 160;

type Row = Record<string, unknown>;

function isRecord(value: unknown): value is Row {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function failure(status: number): Response {
  return Response.json({ error: { code: "HR-SYNC-005", message: "History preview unavailable", retryable: true } },
    { status, headers: { "cache-control": "private, no-store" } });
}

function validPage(value: unknown): value is Row & { messages: Row[] } {
  return isRecord(value) && Array.isArray(value.messages) && value.messages.every(isRecord);
}

/** Connector-owned opt-in read projection. Legacy requests return null and keep their byte stream. */
export async function historyProjection(
  method: string,
  path: string,
  fetchUpstream: (path: string) => Promise<Response>,
): Promise<Response | null> {
  if (method !== "GET") return null;
  let url: URL;
  try { url = new URL(path, "http://connector.local"); } catch { return null; }
  if (!MESSAGES.test(url.pathname)) return null;
  let sessionId: string;
  try { sessionId = decodeURIComponent(url.pathname.split("/")[3]!); } catch { return failure(400); }
  if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(sessionId)) return failure(400);
  const preview = url.searchParams.get("hr_preview");
  const fullId = url.searchParams.get("hr_full_message_id");
  if (preview === null && fullId === null) return null;
  if (preview !== null && (preview !== "1" || fullId !== null || url.searchParams.getAll("hr_preview").length !== 1)) return failure(400);
  if (fullId !== null && (!/^[1-9]\d{0,15}$/.test(fullId) || url.searchParams.getAll("hr_full_message_id").length !== 1)) return failure(400);
  const sourceOffset = url.searchParams.get("hr_full_offset");
  if (fullId !== null && (sourceOffset === null || !/^(0|[1-9]\d{0,8})$/.test(sourceOffset))) return failure(400);
  if (fullId === null && sourceOffset !== null) return failure(400);
  url.searchParams.delete("hr_preview");
  url.searchParams.delete("hr_full_message_id");
  url.searchParams.delete("hr_full_offset");

  const readPage = async (candidate: URL): Promise<{ response: Response; page: Row & { messages: Row[] } } | Response> => {
    const response = await fetchUpstream(candidate.pathname + candidate.search);
    if (!response.ok) return response;
    try {
      const page: unknown = JSON.parse(await boundedResponseBody(response, MAX_PAGE_BYTES));
      if (!validPage(page)) return failure(502);
      return { response, page };
    } catch {
      return failure(502);
    } finally {
      await response.body?.cancel().catch(() => undefined);
    }
  };

  if (preview !== null) {
    const result = await readPage(url);
    if (result instanceof Response) return result;
    const offset = Number(url.searchParams.get("offset") ?? "0");
    if (!Number.isSafeInteger(offset) || offset < 0) return failure(400);
    for (const row of result.page.messages) {
      if (!Number.isSafeInteger(row.id) || (row.id as number) <= 0) continue;
      const fields: string[] = [];
      const toolRole = ["tool", "function", "tool_result", "tool_call"].includes(String(row.role).toLowerCase());
      if (toolRole && typeof row.content === "string" && row.content.length > PREVIEW_CHARS) {
        row.content = row.content.slice(0, PREVIEW_CHARS);
        fields.push("content");
      }
      if (String(row.role).toLowerCase() === "assistant") {
        for (const key of ["reasoning", "reasoning_content"]) {
          if (typeof row[key] === "string" && row[key].length > PREVIEW_CHARS) {
            row[key] = row[key].slice(0, PREVIEW_CHARS);
            fields.push(key);
          }
        }
      }
      if (fields.length) {
        row.hr_preview = {
          fields, offset, sessionId,
          profile: url.searchParams.get("profile"),
        };
      }
    }
    return Response.json(result.page, { headers: { "cache-control": "private, no-store" } });
  }

  const offset = Number(sourceOffset);
  const offsets = [offset, offset + PAGE_SIZE, Math.max(0, offset - PAGE_SIZE), offset + 2 * PAGE_SIZE];
  for (const candidateOffset of [...new Set(offsets)]) {
    const candidate = new URL(url);
    candidate.searchParams.set("order", "latest");
    candidate.searchParams.set("limit", String(PAGE_SIZE));
    candidate.searchParams.set("offset", String(candidateOffset));
    const result = await readPage(candidate);
    if (result instanceof Response) return result;
    const row = result.page.messages.find((item) => item.id === Number(fullId));
    if (row) return Response.json({ messages: [row] }, { headers: { "cache-control": "private, no-store" } });
  }
  return failure(404);
}
