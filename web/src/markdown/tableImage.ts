// Table card → PNG (Android OffscreenTableExporter / TableExport): 170px columns, always the light
// palette (an exported image is read outside the app), no search highlight, saved as
// `HermesGO-Table-yyyyMMdd-HHmmss.png`. Layout is pure (a measure function is injected) so the
// wrapping rules are testable without a canvas.

export const TABLE_CELL_WIDTH = 170;
export const TABLE_CELL_PADDING = 10;
export const TABLE_LINE_HEIGHT = 20;
export const TABLE_MAX_HEIGHT = 12_000;

export interface TableRowData {
  header: boolean;
  cells: string[];
}

export interface TableLayout {
  columns: number;
  width: number;
  height: number;
  rows: { header: boolean; y: number; height: number; lines: string[][] }[];
}

/** Rows and cell texts of a rendered table (whitespace collapsed). */
export function tableRows(table: Element): TableRowData[] {
  return Array.from(table.querySelectorAll("tr")).map((tr) => ({
    header: tr.querySelector("th") !== null && tr.querySelector("td") === null,
    cells: Array.from(tr.querySelectorAll("th, td"), (c) => (c.textContent ?? "").replace(/\s+/g, " ").trim()),
  }));
}

/** Greedy wrap: at spaces between words, at any character in CJK runs, mid-token only when a token is wider than the cell. */
export function wrapText(text: string, maxWidth: number, measure: (s: string) => number): string[] {
  if (!text) return [""];
  const tokens = text.match(/\s+|[\u3000-\u9fff\uff00-\uffef]|[^\s\u3000-\u9fff\uff00-\uffef]+/g) ?? [text];
  const lines: string[] = [];
  let line = "";
  for (let token of tokens) {
    if (measure(line + token) <= maxWidth) {
      line += token;
      continue;
    }
    if (line.trim()) {
      lines.push(line.trimEnd());
      line = "";
      token = token.trimStart();
      if (!token) continue;
    }
    if (measure(token) <= maxWidth) {
      line = token;
      continue;
    }
    for (const ch of Array.from(token)) {
      if (line && measure(line + ch) > maxWidth) {
        lines.push(line);
        line = ch;
      } else line += ch;
    }
  }
  if (line.trim() || !lines.length) lines.push(line.trimEnd());
  return lines;
}

export function layoutTable(rows: readonly TableRowData[], measure: (s: string) => number): TableLayout {
  const columns = Math.max(1, ...rows.map((r) => r.cells.length));
  const inner = TABLE_CELL_WIDTH - 2 * TABLE_CELL_PADDING;
  let y = 0;
  const out: TableLayout["rows"] = [];
  for (const row of rows) {
    const lines = Array.from({ length: columns }, (_, i) => wrapText(row.cells[i] ?? "", inner, measure));
    const height = Math.max(...lines.map((l) => l.length)) * TABLE_LINE_HEIGHT + 2 * TABLE_CELL_PADDING;
    out.push({ header: row.header, y, height, lines });
    y += height;
  }
  return { columns, width: columns * TABLE_CELL_WIDTH, height: y, rows: out };
}

export function tableImageName(nowMs = Date.now()): string {
  const d = new Date(nowMs);
  const p = (n: number) => String(n).padStart(2, "0");
  return `HermesGO-Table-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.png`;
}

const FONT = '14px -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';
const BOLD = `600 ${FONT}`;

/** Draw the table at 2× into a PNG. Throws when the canvas cannot be made or the table is too tall. */
export async function renderTableImage(table: Element): Promise<Blob> {
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("canvas 2d context unavailable");
  const rows = tableRows(table);
  const measure = (bold: boolean) => (s: string) => {
    ctx.font = bold ? BOLD : FONT;
    return ctx.measureText(s).width;
  };
  // Header rows measure bold; body rows regular.
  const layout = layoutTable(rows, measure(false));
  const headerLayout = layoutTable(rows, measure(true));
  layout.rows.forEach((r, i) => {
    if (r.header) layout.rows[i] = { ...headerLayout.rows[i]!, y: r.y };
  });
  const margin = 12;
  const width = layout.width + margin * 2;
  const height = layout.rows.reduce((h, r) => h + r.height, 0) + margin * 2;
  if (height > TABLE_MAX_HEIGHT) throw new Error(`table image too tall: ${height}px`);
  const scale = 2;
  canvas.width = width * scale;
  canvas.height = height * scale;
  ctx.scale(scale, scale);
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);
  let y = margin;
  for (const row of layout.rows) {
    if (row.header) {
      ctx.fillStyle = "#f4f4f0";
      ctx.fillRect(margin, y, layout.width, row.height);
    }
    ctx.font = row.header ? BOLD : FONT;
    ctx.fillStyle = "#1b1c1a";
    ctx.textBaseline = "top";
    row.lines.forEach((cell, c) => {
      cell.forEach((line, l) => ctx.fillText(line, margin + c * TABLE_CELL_WIDTH + TABLE_CELL_PADDING, y + TABLE_CELL_PADDING + l * TABLE_LINE_HEIGHT + 3));
    });
    y += row.height;
  }
  ctx.strokeStyle = "#c9c7c2";
  ctx.lineWidth = 1;
  y = margin;
  for (const row of layout.rows) {
    ctx.strokeRect(margin + 0.5, y + 0.5, layout.width - 1, row.height);
    for (let c = 1; c < layout.columns; c++) {
      ctx.beginPath();
      ctx.moveTo(margin + c * TABLE_CELL_WIDTH + 0.5, y);
      ctx.lineTo(margin + c * TABLE_CELL_WIDTH + 0.5, y + row.height);
      ctx.stroke();
    }
    y += row.height;
  }
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("canvas.toBlob returned null");
  return blob;
}
