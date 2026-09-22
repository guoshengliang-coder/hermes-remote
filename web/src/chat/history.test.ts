import { describe, expect, it } from "vitest";
import type { MessagesPage } from "../api/gateway";
import type { MessageRow, MessagesResponse } from "../hermes/types";
import { fetchFullHistory, mergeOlder, mergeTail, olderRowsIn, pageHasMore } from "./history";

const row = (id: number, content = `m${id}`): MessageRow => ({ id, role: id % 2 ? "user" : "assistant", content, timestamp: id });
const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => row(from + i));
const ids = (rows: readonly MessageRow[]) => rows.map((r) => r.id);

describe("pageHasMore", () => {
  it("a full page may have more; a short one or an unpaged answer has not", () => {
    expect(pageHasMore({ messages: range(1, 100), pagination: { limit: 100, offset: 0, order: "latest", returned: 100 } }, 100)).toBe(true);
    expect(pageHasMore({ messages: range(1, 40), pagination: { returned: 40 } }, 100)).toBe(false);
    expect(pageHasMore({ messages: range(1, 100), pagination: {} }, 100)).toBe(true);
    // An older Hermes that ignores paging sends everything at once.
    expect(pageHasMore({ messages: range(1, 100) }, 100)).toBe(false);
  });
});

describe("mergeTail", () => {
  it("keeps older pages and replaces everything from the page's oldest row on", () => {
    const loaded = range(1, 200);
    const page = [...range(151, 200).map((r) => (r.id === 180 ? { ...r, content: "edited" } : r)), ...range(201, 250)];
    const { rows, reset } = mergeTail(loaded, page, true);
    expect(reset).toBe(false);
    expect(ids(rows)).toEqual(ids(range(1, 250)));
    expect(rows.find((r) => r.id === 180)!.content).toBe("edited");
  });

  it("drops rows the server no longer has inside the page's window", () => {
    const { rows } = mergeTail(range(1, 200), [...range(101, 150), ...range(152, 200)], true);
    expect(ids(rows)).not.toContain(151);
    expect(rows).toHaveLength(199);
  });

  it("starts over when the page cannot be joined: a gap, the whole conversation, or rows without ids", () => {
    expect(mergeTail(range(1, 100), range(300, 399), true)).toEqual({ rows: range(300, 399), reset: true });
    expect(mergeTail(range(1, 100), range(50, 120), false).reset).toBe(true);
    expect(mergeTail([], range(1, 100), true).reset).toBe(true);
    expect(mergeTail([{ role: "user", content: "x" }], range(1, 100), true).reset).toBe(true);
  });
});

describe("mergeOlder", () => {
  it("prepends only rows older than the oldest loaded (an overlapping page is de-duplicated)", () => {
    const loaded = range(101, 200);
    const page = range(81, 180); // offset drifted forward: 80 rows overlap
    expect(ids(mergeOlder(loaded, page))).toEqual(ids(range(81, 200)));
    expect(olderRowsIn(loaded, page)).toBe(20);
    expect(olderRowsIn(loaded, range(150, 200))).toBe(0);
    expect(ids(mergeOlder([], range(1, 3)))).toEqual([1, 2, 3]);
  });
});

describe("fetchFullHistory", () => {
  function server(total: number, paged = true) {
    const all = range(1, total);
    const calls: MessagesPage[] = [];
    const fetchPage = async (page: MessagesPage): Promise<MessagesResponse> => {
      calls.push(page);
      const messages = page.order === "oldest" ? all.slice(page.offset, page.offset + page.limit) : [];
      return paged ? { messages, pagination: { ...page, returned: messages.length } } : { messages: all.slice(-500) };
    };
    return { calls, fetchPage };
  }

  it("pages oldest-first until a short page and returns every row once", async () => {
    const { calls, fetchPage } = server(1234);
    const rows = await fetchFullHistory(fetchPage);
    expect(ids(rows)).toEqual(ids(range(1, 1234)));
    expect(calls).toEqual([
      { order: "oldest", limit: 500, offset: 0 },
      { order: "oldest", limit: 500, offset: 500 },
      { order: "oldest", limit: 500, offset: 1000 },
    ]);
  });

  it("stops after an empty page when the total is a multiple of the page size", async () => {
    const { calls, fetchPage } = server(1000);
    expect(await fetchFullHistory(fetchPage)).toHaveLength(1000);
    expect(calls).toHaveLength(3);
  });

  it("an unpaged Hermes answers in one request", async () => {
    const { calls, fetchPage } = server(800, false);
    expect(await fetchFullHistory(fetchPage)).toHaveLength(500);
    expect(calls).toHaveLength(1);
  });
});
