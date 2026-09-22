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

function minId(rows: readonly MessageRow[]): number | null {
  let min: number | null = null;
  for (const row of rows) {
    const id = idOf(row);
    if (id !== null && (min === null || id < min)) min = id;
  }
  return min;
}

function maxId(rows: readonly MessageRow[]): number | null {
  let max: number | null = null;
  for (const row of rows) {
    const id = idOf(row);
    if (id !== null && (max === null || id > max)) max = id;
  }
  return max;
}

/**
 * Merge a freshly fetched newest page into the rows already loaded.
 *
 * The page is authoritative for everything from its oldest row onwards (a row deleted or rewritten
 * there disappears or changes, exactly as the old full reload did); loaded rows older than it are
 * kept. `reset` means the page replaces everything and the paging state starts over: nothing was
 * loaded, a row has no id to merge by, the page is the whole conversation (`full` false), or the
 * page does not reach back to the loaded rows (more than a page arrived meanwhile: a gap).
 */
export function mergeTail(existing: readonly MessageRow[], page: readonly MessageRow[], full: boolean): { rows: MessageRow[]; reset: boolean } {
  if (!existing.length || !full || !page.length) return { rows: [...page], reset: true };
  if (existing.some((r) => idOf(r) === null) || page.some((r) => idOf(r) === null)) return { rows: [...page], reset: true };
  const oldestInPage = minId(page)!;
  if (oldestInPage > maxId(existing)!) return { rows: [...page], reset: true };
  return { rows: [...existing.filter((r) => idOf(r)! < oldestInPage), ...page], reset: false };
}

/**
 * Prepend an older page. Offsets drift forward when new rows arrive after the first load, so the
 * page may overlap what is loaded; only rows older than the oldest loaded one are taken.
 */
export function mergeOlder(existing: readonly MessageRow[], older: readonly MessageRow[]): MessageRow[] {
  const oldest = minId(existing);
  if (oldest === null) return existing.length ? [...existing] : [...older];
  return [...older.filter((r) => {
    const id = idOf(r);
    return id !== null && id < oldest;
  }), ...existing];
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
export async function fetchFullHistory(fetchPage: (page: MessagesPage) => Promise<MessagesResponse>): Promise<MessageRow[]> {
  const out: MessageRow[] = [];
  const seen = new Set<number>();
  let offset = 0;
  for (let n = 0; n < FULL_HISTORY_MAX_PAGES; n++) {
    const body = await fetchPage({ order: "oldest", limit: FULL_HISTORY_PAGE_SIZE, offset });
    const rows = rowsOf(body);
    for (const row of rows) {
      const id = idOf(row);
      if (id !== null) {
        if (seen.has(id)) continue;
        seen.add(id);
      }
      out.push(row);
    }
    if (!pageHasMore(body, FULL_HISTORY_PAGE_SIZE) || !rows.length) break;
    offset += rows.length;
  }
  return out;
}
