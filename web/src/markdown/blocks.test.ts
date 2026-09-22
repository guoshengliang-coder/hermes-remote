// @vitest-environment jsdom
// jsdom for the same reason as render.test.ts: DOMPurify must really run.
import { describe, expect, it } from "vitest";
import { codeLanguage, copyPayload, decorateBlocks, renderMarkdownFragment, tableToTsv } from "./render";

const labels = { table: "表格", copyCode: "复制代码", copyTable: "复制表格" };

function render(md: string): HTMLDivElement {
  const div = document.createElement("div");
  const fragment = renderMarkdownFragment(md);
  decorateBlocks(fragment, labels);
  div.replaceChildren(fragment);
  return div;
}

describe("code block and table cards (DESIGN §5.4)", () => {
  it("wraps a fenced block with its language and a copy button that yields the code verbatim", () => {
    const root = render("before\n\n```TypeScript\nconst a = 1;\n\tindented <b>x</b>\n```\n");
    const card = root.querySelector(".code-card")!;
    expect(card.querySelector(".block-label")!.textContent).toBe("typescript");
    const button = card.querySelector("button.block-copy")!;
    expect(button.getAttribute("aria-label")).toBe("复制代码");
    expect(copyPayload(button)).toEqual({ kind: "code", text: "const a = 1;\n\tindented <b>x</b>" });
    // The block's markup stayed text: no element was created from the code.
    expect(card.querySelector("pre b")).toBeNull();
  });

  it("labels an unfenced block as code", () => {
    const root = render("    plain indented\n");
    expect(codeLanguage(root.querySelector("pre")!)).toBe("code");
    expect(root.querySelector(".block-label")!.textContent).toBe("code");
  });

  it("copies a table as tab-separated cells, flattening tabs and newlines inside cells", () => {
    const root = render("| a | b |\n|---|:-:|\n| 1 | two words |\n| x\ty | `z` |\n");
    const card = root.querySelector(".table-card")!;
    expect(card.querySelector(".block-label")!.textContent).toBe("表格");
    expect(card.querySelector(".table-scroll > table")).not.toBeNull();
    expect(copyPayload(card.querySelector("button.block-copy")!)).toEqual({ kind: "table", text: "a\tb\n1\ttwo words\nx y\tz" });
  });

  it("does nothing to prose, and a button outside a card yields nothing", () => {
    const root = render("just **text**");
    expect(root.querySelector(".block-card")).toBeNull();
    const stray = document.createElement("button");
    stray.setAttribute("data-copy", "code");
    expect(copyPayload(stray)).toBeNull();
  });

  it("tableToTsv reads header and body rows", () => {
    const table = document.createElement("table");
    const tr = table.appendChild(document.createElement("tr"));
    tr.appendChild(document.createElement("th")).textContent = " h ";
    tr.appendChild(document.createElement("td")).textContent = "v\r\nw";
    expect(tableToTsv(table)).toBe("h\tv w");
  });
});

describe("diff cards", () => {
  it("colours a unified diff line by line, summarises +/−, and copies it back exactly", () => {
    const code = "@@ -1,3 +1,3 @@\n ctx\n-old\n+new\n\n ";
    const root = render("```diff\n" + code + "\n```\n");
    const card = root.querySelector(".code-card")!;
    expect(Array.from(card.querySelectorAll(".diff-line"), (l) => l.className)).toEqual([
      "diff-line hunk", "diff-line context", "diff-line del", "diff-line add", "diff-line context", "diff-line context",
    ]);
    expect(card.querySelector(".diff-summary")!.textContent).toBe("+1 −1");
    expect(copyPayload(card.querySelector("button.block-copy")!)).toEqual({ kind: "code", text: code });
  });

  it("leaves a markdown-list-looking code block alone", () => {
    const root = render("```\n- a\n- b\n- c\n```\n");
    expect(root.querySelector(".diff-line")).toBeNull();
  });
});

describe("readableText", () => {
  it("drops marks, keeps code verbatim and list structure", async () => {
    const { readableText } = await import("./render");
    expect(readableText("# Title\n\nSome **bold** and [a link](https://x.test).\n\n- one\n- two\n\n1. first\n2. second\n\n```\ncode  kept\n```")).toBe(
      "Title\n\nSome bold and a link.\n\n• one\n• two\n\n1. first\n2. second\n\ncode  kept",
    );
  });
});

describe("table card extras (batch 5: 保存为图片 / 全屏查看)", () => {
  it("adds save-image and fullscreen buttons only when labelled, never to code cards", () => {
    const md = "| a | b |\n|---|---|\n| 1 | 2 |\n\n```\ncode\n```\n";
    const plain = render(md);
    expect(plain.querySelectorAll("[data-action]").length).toBe(0);
    const div = document.createElement("div");
    const fragment = renderMarkdownFragment(md);
    decorateBlocks(fragment, { ...labels, saveTable: "保存为图片", fullTable: "全屏查看" });
    div.replaceChildren(fragment);
    const actions = [...div.querySelectorAll(".table-card [data-action]")].map((b) => `${b.getAttribute("data-action")}:${b.getAttribute("aria-label")}`);
    expect(actions).toEqual(["table-image:保存为图片", "table-full:全屏查看"]);
    expect(div.querySelector(".code-card [data-action]")).toBeNull();
    // The copy button still yields the cells, not the extra buttons.
    expect(copyPayload(div.querySelector(".table-card [data-copy]")!)?.kind).toBe("table");
  });
});
