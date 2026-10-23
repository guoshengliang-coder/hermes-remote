import type { MessagesPage } from "../api/gateway";
import { toAppError } from "../app/failures";
import { appError, type AppError } from "../errors";
import type { MessageRow, MessagesResponse } from "../hermes/types";

// Paged transcript history (HG-104). The chat opens on the newest page only; older pages are
// fetched as the reader scrolls up and merged by row id. Everything here is pure except the
// full-transcript helper, which only calls the page fetcher it is given.

/** Rows per page while reading a conversation. */
export const HISTORY_PAGE_SIZE = 100;
/** Rows per page when the whole transcript is needed (upstream caps `limit` at 500). */
export const FULL_HISTORY_PAGE_SIZE = 500;
/** Upper bound on pages fetched for one full transcript (500 × 400 = 200k rows). */
const FULL_HISTORY_MAX_PAGES = 400;

export const latestPage = (offset = 0, limit = HISTORY_PAGE_SIZE): MessagesPage => ({ order: "latest", limit, offset });

export function rowsOf(body: MessagesResponse | null | undefined): MessageRow[] {
  return Array.isArray(body?.messages) ? body.messages : [];
}

/**
 * Whether a page may have more rows beyond it. An answer without `pagination` comes from a Hermes
 * that does not page: it already sent everything it will ever send, so there is nothing further.
 */
export function pageHasMore(body: MessagesResponse | null | undefined, limit: number): boolean {
  const pagination = body?.pagination;
  if (!pagination || typeof pagination !== "object") return false;
  const returned = typeof pagination.returned === "number" ? pagination.returned : rowsOf(body).length;
  return returned >= limit;
}

function idOf(row: MessageRow): number | null {
  return typeof row.id === "number" && Number.isFinite(row.id) ? row.id : null;
}

/**
 * Compaction chooses new representative IDs for old logical messages. Respect server display
 * order and join only at an actual boundary identity; numeric IDs cannot establish chronology.
 * An unknown gap resets the window so older pages can fill it without inventing adjacency.
 */
export function mergeTail(existing: readonly MessageRow[], page: readonly MessageRow[], full: boolean): { rows: MessageRow[]; reset: boolean } {
  const reset = () => ({ rows: [...page], reset: true });
  if (!existing.length || !full || !page.length) return reset();
  if (existing.some((r) => idOf(r) === null) || page.some((r) => idOf(r) === null)) return reset();
  const boundary = existing.findIndex((r) => idOf(r) === idOf(page[0]!));
  if (boundary < 0) return reset();
  const pageIds = new Set(page.map(idOf));
  return { rows: [...existing.slice(0, boundary).filter((r) => !pageIds.has(idOf(r))), ...page], reset: false };
}

/** Only the prefix before the first held row precedes the window; offsets can drift newer. */
export function mergeOlder(existing: readonly MessageRow[], older: readonly MessageRow[]): MessageRow[] {
  if (!existing.length) return [...older];
  if (existing.some((r) => idOf(r) === null)) return [...existing];
  const held = new Set(existing.map(idOf));
  const overlap = older.findIndex((r) => held.has(idOf(r)));
  const prefix = overlap < 0 ? older : older.slice(0, overlap);
  return [...prefix.filter((r) => idOf(r) !== null), ...existing];
}

/** Rows of `page` that are older than everything in `existing` (what an older page adds). */
export function olderRowsIn(existing: readonly MessageRow[], page: readonly MessageRow[]): number {
  return mergeOlder(existing, page).length - existing.length;
}

/**
 * A history fetch that fills in more of a conversation already on screen failed: the transcript
 * shown stays, the failure is HR-SYNC-001 (retryable) with the mapped cause in its details.
 */
export function historySyncError(error: unknown, what: string): AppError {
  const cause = toAppError(error, "history");
  return appError("HR-SYNC-001", `${what} failed: ${cause.code}${cause.details ? ` ${cause.details}` : ""}`);
}

/**
 * The whole stored transcript, oldest first: pages of 500 with `order=oldest` until a short page
 * (new rows only ever append, so these offsets stay put). Rows are de-duplicated by id.
 */
export async function fetchFullHistory(fetchPage: (page: MessagesPage) => Promise<MessagesResponse>, pageSize = FULL_HISTORY_PAGE_SIZE): Promise<MessageRow[]> {
  const out: MessageRow[] = [];
  const seen = new Set<number>();
  let offset = 0;
  const maxPages = Math.ceil(FULL_HISTORY_MAX_PAGES * FULL_HISTORY_PAGE_SIZE / pageSize);
  for (let n = 0; n < maxPages; n++) {
    const body = await fetchPage({ order: "oldest", limit: pageSize, offset });
    const rows = rowsOf(body);
    const before = out.length;
    for (const row of rows) {
      const id = idOf(row);
      if (id !== null) {
        if (seen.has(id)) continue;
        seen.add(id);
      }
      out.push(row);
    }
    if (!pageHasMore(body, pageSize) || !rows.length) return out;
    if (out.length === before) throw new Error("Full history made no progress");
    offset += rows.length;
  }
  throw new Error("Full history exceeded the bounded page limit");
}
