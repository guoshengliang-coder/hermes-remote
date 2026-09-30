import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-164. A table wider than its card must slide sideways the way Android's chat table does,
 * instead of being squeezed into the card: with an auto-layout table and `.markdown`'s
 * `word-break`, a five-column table became five two-character columns and there was nothing to
 * scroll. The squeeze is a layout bug jsdom cannot reproduce (no `scrollWidth`), so this pins the
 * declarations that produce the fix — a fixed-layout table exactly as wide as its columns, a 100%
 * floor for a table that still fits, and Android's cell width on every cell. Measured in headless
 * Chrome at a 353px viewport with a 321px card, the report's five-column table went from 305px
 * wide with 42–79px columns and no overflow, to 551px wide with 110px columns and a 567px
 * `scrollWidth` inside `.table-scroll`.
 */
describe("chat table card slides sideways (DESIGN §5.21, HG-164)", () => {
  const style = document.createElement("style");
  style.textContent = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");
  document.head.append(style);

  const rules = [...(style.sheet?.cssRules ?? [])];
  const rule = (...selectors: string[]): CSSStyleRule | undefined => rules.find(
    (candidate): candidate is CSSStyleRule => candidate instanceof CSSStyleRule
      && selectors.every((selector) => candidate.selectorText.split(",").map((part) => part.trim()).includes(selector)),
  );

  it("scrolls the card body instead of squeezing the table into it", () => {
    expect(rule(".table-scroll")?.style.overflowX).toBe("auto");

    const table = rule(".markdown .table-card table");
    expect(table?.style.tableLayout).toBe("fixed");
    expect(table?.style.width).toBe("max-content");
    expect(table?.style.minWidth).toBe("100%");
  });

  it("keeps a table that still fits on the card width, with Android's cell width as the floor", () => {
    // Android wraps a cell inside a fixed column (CHAT_TABLE_CELL_WIDTH); `min-width: 100%` above
    // is what stretches the fixed grid over the card when the columns do not fill it.
    const cells = rule(".markdown .table-card th", ".markdown .table-card td");
    expect(cells?.style.width).toBe("6.875rem");
    expect(6.875 * 16).toBe(110);
  });

  it("keeps the Web cell width in step with Android's CHAT_TABLE_CELL_WIDTH", () => {
    // A hand-copied constant across two surfaces: when Android changes this, the Web cell floor has
    // to follow in the same batch. 110dp and 110 CSS px are the same size at the standard density.
    const kotlin = readFileSync(
      resolve(process.cwd(), "../android/app/src/main/java/com/hermes/client/ui/chat/HermesMarkdown.kt"),
      "utf8",
    );
    const androidCellWidthDp = /CHAT_TABLE_CELL_WIDTH\s*=\s*(\d+(?:\.\d+)?)\.dp/.exec(kotlin)?.[1];
    expect(androidCellWidthDp).toBe("110");
  });
});
