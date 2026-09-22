// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { layoutTable, tableImageName, tableRows, TABLE_CELL_WIDTH, wrapText } from "./tableImage";

const measure = (s: string) => Array.from(s).length * 10; // 10px per character

describe("table image layout", () => {
  it("wraps words at spaces, CJK at characters, and breaks a token only when it is wider than the cell", () => {
    expect(wrapText("alpha beta gamma", 100, measure)).toEqual(["alpha beta", "gamma"]);
    expect(wrapText("一二三四五六七八九十十一十二", 100, measure)).toEqual(["一二三四五六七八九十", "十一十二"]);
    expect(wrapText("abcdefghijklmnop", 100, measure)).toEqual(["abcdefghij", "klmnop"]);
    expect(wrapText("", 100, measure)).toEqual([""]);
  });

  it("gives every row the height of its tallest cell and every column 170px", () => {
    const layout = layoutTable(
      [
        { header: true, cells: ["端口", "服务"] },
        { header: false, cells: ["443", "一个非常非常非常非常非常长的服务名称需要换行"] },
      ],
      measure,
    );
    expect(layout.columns).toBe(2);
    expect(layout.width).toBe(2 * TABLE_CELL_WIDTH);
    expect(layout.rows[0]!.height).toBe(20 + 20);
    expect(layout.rows[1]!.lines[1]!.length).toBe(2);
    expect(layout.rows[1]!.height).toBe(2 * 20 + 20);
    expect(layout.rows[1]!.y).toBe(40);
  });

  it("reads header and body rows from the rendered table", () => {
    const div = document.createElement("div");
    div.innerHTML = "<table><thead><tr><th>a</th><th> b  c </th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>";
    expect(tableRows(div.querySelector("table")!)).toEqual([
      { header: true, cells: ["a", "b c"] },
      { header: false, cells: ["1", "2"] },
    ]);
  });

  it("names the file like Android", () => {
    expect(tableImageName(new Date(2026, 8, 22, 8, 7, 5).getTime())).toBe("HermesGO-Table-20260922-080705.png");
  });
});
