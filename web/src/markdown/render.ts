import markdownIt, { type Env, type MarkdownIt, type RendererRule } from "markdown-it";
import createDOMPurify, { type DOMPurify } from "dompurify";
import { diffKind, looksLikeDiff } from "../chat/organize";

// Assistant Markdown → sanitized HTML. Content is untrusted (it can quote any web page), so:
// raw HTML is off, only https: links become anchors (anything else renders as its text), Markdown
// images never become <img> (no cross-origin fetches or tracking pixels; an https image becomes a
// link), and the output is passed through DOMPurify with a tight allowlist as a second layer.

const ALLOWED_TAGS = [
  "p", "br", "hr", "strong", "em", "del", "s", "code", "pre", "blockquote",
  "ul", "ol", "li", "a", "h1", "h2", "h3", "h4", "h5", "h6",
  "table", "thead", "tbody", "tr", "th", "td",
];
const ALLOWED_ATTR = ["href", "target", "rel", "class", "start"];
const CLASS_ALLOWED = /^(?:language-[\w+#.-]{1,40}|md-align-(?:left|center|right))$/;

/** The one link scheme allowed out of message content. */
export function isAllowedHref(href: string | null | undefined): href is string {
  if (!href) return false;
  try {
    return new URL(href).protocol === "https:";
  } catch {
    return false;
  }
}

/** Per-render state: whether each open link was refused, so its close tag is dropped too. */
interface RenderEnv extends Env {
  hiddenLinks: boolean[];
}

function hiddenLinks(env: Env | undefined): boolean[] {
  const e = (env ?? {}) as Partial<RenderEnv>;
  return (e.hiddenLinks ??= []);
}

function attr(value: string | number | null): string {
  return value === null ? "" : String(value);
}

function createMarkdown(): MarkdownIt {
  const md = markdownIt({ html: false, linkify: true, breaks: false, typographer: false });
  // Accept every destination at parse time so a refused link keeps its label as text instead of
  // falling back to raw `[label](url)` source; the renderer below decides what becomes an anchor.
  md.validateLink = () => true;

  const linkOpen: RendererRule = (tokens, idx, options, env, self) => {
    const token = tokens[idx]!;
    const href = attr(token.attrGet("href"));
    const allowed = isAllowedHref(href);
    hiddenLinks(env).push(!allowed);
    if (!allowed) return "";
    token.attrs = [["href", href], ["target", "_blank"], ["rel", "noopener noreferrer"]];
    return self.renderToken(tokens, idx, options);
  };
  md.renderer.rules.link_open = linkOpen;
  md.renderer.rules.link_close = (tokens, idx, options, env, self) =>
    hiddenLinks(env).pop() ? "" : self.renderToken(tokens, idx, options);

  md.renderer.rules.image = (tokens, idx, options, env, self) => {
    const token = tokens[idx]!;
    const src = attr(token.attrGet("src"));
    const label = self.renderInlineAsText(token.children ?? [], options, env);
    const text = md.utils.escapeHtml(label || (isAllowedHref(src) ? src : ""));
    if (!isAllowedHref(src)) return text;
    return `<a href="${md.utils.escapeHtml(src)}" target="_blank" rel="noopener noreferrer">${text}</a>`;
  };

  // Table alignment arrives as style="text-align:…"; inline styles are blocked by the CSP, so it
  // becomes a class instead.
  for (const rule of ["th_open", "td_open"] as const) {
    md.renderer.rules[rule] = (tokens, idx, options, _env, self) => {
      const token = tokens[idx]!;
      const align = /text-align:\s*(left|center|right)/.exec(attr(token.attrGet("style")))?.[1];
      token.attrs = align ? [["class", `md-align-${align}`]] : null;
      return self.renderToken(tokens, idx, options);
    };
  }
  return md;
}

let purifier: DOMPurify | null = null;

function getPurifier(): DOMPurify {
  if (purifier) return purifier;
  const instance = createDOMPurify(window);
  instance.addHook("uponSanitizeAttribute", (node, data) => {
    if (data.attrName === "class" && !data.attrValue.split(/\s+/).every((c) => CLASS_ALLOWED.test(c))) {
      data.keepAttr = false;
    }
    if (data.attrName === "href" && !isAllowedHref(data.attrValue)) data.keepAttr = false;
    if (data.attrName === "start" && node.nodeName !== "OL") data.keepAttr = false;
  });
  instance.addHook("afterSanitizeAttributes", (node) => {
    if (node.nodeName === "A") {
      if (!node.getAttribute("href")) {
        node.removeAttribute("target");
        node.removeAttribute("rel");
        return;
      }
      node.setAttribute("target", "_blank");
      node.setAttribute("rel", "noopener noreferrer");
    }
  });
  purifier = instance;
  return instance;
}

const SANITIZE_CONFIG = {
  ALLOWED_TAGS,
  ALLOWED_ATTR,
  ALLOWED_URI_REGEXP: /^https:/i,
  // A non-URI attribute must be declared URI-safe, or the https-only regexp rejects its value.
  ADD_URI_SAFE_ATTR: ["start"],
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: false,
  KEEP_CONTENT: true,
  RETURN_TRUSTED_TYPE: false,
};

export function sanitizeHtml(html: string): string {
  return getPurifier().sanitize(html, SANITIZE_CONFIG) as string;
}

/** Same sanitizer, returned as a DOM fragment: attach with `replaceChildren`, never innerHTML. */
export function sanitizeToFragment(html: string): DocumentFragment {
  return getPurifier().sanitize(html, { ...SANITIZE_CONFIG, RETURN_DOM_FRAGMENT: true });
}

let markdown: MarkdownIt | null = null;

/** Render untrusted Markdown to sanitized HTML safe for innerHTML. */
export function renderMarkdown(source: string): string {
  markdown ??= createMarkdown();
  const env: RenderEnv = { hiddenLinks: [] };
  return sanitizeHtml(markdown.render(source, env));
}

/** Render untrusted Markdown to a sanitized DocumentFragment (the UI's only path into the DOM). */
export function renderMarkdownFragment(source: string): DocumentFragment {
  markdown ??= createMarkdown();
  const env: RenderEnv = { hiddenLinks: [] };
  return sanitizeToFragment(markdown.render(source, env));
}

// ---- block headers (DESIGN §5.4 code block / table card) ----------------------------------
// Code blocks and tables get a header row — language (or 「表格」) and a copy button — built with
// DOM calls on the already-sanitized fragment. Nothing here parses markup, so it adds no path for
// message content to become HTML.

export interface BlockLabels {
  table: string;
  copyCode: string;
  copyTable: string;
}

export type CopyKind = "code" | "table";

const SVG_NS = "http://www.w3.org/2000/svg";

function copyGlyph(doc: Document): SVGSVGElement {
  const svg = doc.createElementNS(SVG_NS, "svg");
  for (const [k, v] of [["width", "16"], ["height", "16"], ["viewBox", "0 0 24 24"], ["fill", "none"], ["stroke", "currentColor"], ["stroke-width", "1.8"], ["stroke-linecap", "round"], ["stroke-linejoin", "round"], ["aria-hidden", "true"], ["focusable", "false"]]) {
    svg.setAttribute(k!, v!);
  }
  const rect = doc.createElementNS(SVG_NS, "rect");
  for (const [k, v] of [["x", "8"], ["y", "8"], ["width", "12"], ["height", "12"], ["rx", "2.5"]]) rect.setAttribute(k!, v!);
  const back = doc.createElementNS(SVG_NS, "path");
  back.setAttribute("d", "M16 8V6a2 2 0 00-2-2H6a2 2 0 00-2 2v8a2 2 0 002 2h2");
  svg.append(rect, back);
  return svg;
}

function blockCard(doc: Document, kind: CopyKind, label: string, copyLabel: string, body: Element): HTMLElement {
  const card = doc.createElement("div");
  card.className = kind === "code" ? "block-card code-card" : "block-card table-card";
  const head = doc.createElement("div");
  head.className = "block-head";
  const name = doc.createElement("span");
  name.className = "block-label";
  name.textContent = label;
  const button = doc.createElement("button");
  button.type = "button";
  button.className = "block-copy";
  button.dataset.copy = kind;
  button.setAttribute("aria-label", copyLabel);
  button.appendChild(copyGlyph(doc));
  head.append(name, button);
  body.replaceWith(card);
  card.append(head, body);
  return card;
}

/** The fenced language (`language-ts` → `ts`), lower-cased; "code" when there is none. */
export function codeLanguage(pre: Element): string {
  const cls = pre.querySelector("code")?.className ?? "";
  const lang = /(?:^|\s)language-([\w+#.-]{1,40})/.exec(cls)?.[1];
  return lang ? lang.toLowerCase() : "code";
}

/** Wrap every `pre` and `table` in `root` in a card with a header and a copy button. */
export function decorateBlocks(root: DocumentFragment | Element, labels: BlockLabels): void {
  const doc = root.ownerDocument ?? document;
  for (const pre of Array.from(root.querySelectorAll("pre"))) {
    const language = codeLanguage(pre);
    const card = blockCard(doc, "code", language, labels.copyCode, pre);
    const code = pre.querySelector("code") ?? pre;
    const text = (code.textContent ?? "").replace(/\n$/, "");
    if (looksLikeDiff(text, language === "code" ? null : language)) markDiff(doc, card, code, text);
  }
  for (const table of Array.from(root.querySelectorAll("table"))) {
    const scroller = doc.createElement("div");
    scroller.className = "table-scroll";
    table.replaceWith(scroller);
    scroller.appendChild(table);
    blockCard(doc, "table", labels.table, labels.copyTable, scroller);
  }
}

/** Unified diff: one span per line (text nodes only) plus a `+N −M` summary in the header. */
function markDiff(doc: Document, card: HTMLElement, code: Element, text: string): void {
  let add = 0;
  let del = 0;
  const lines = text.split("\n").map((line) => {
    const kind = diffKind(line);
    if (kind === "add" && !line.startsWith("+++")) add++;
    if (kind === "del" && !line.startsWith("---")) del++;
    const span = doc.createElement("span");
    span.className = `diff-line ${kind}`;
    // An empty line still needs a box to keep its height; the marker keeps copy exact.
    span.textContent = line || " ";
    if (!line) span.dataset.blank = "1";
    return span;
  });
  code.replaceChildren(...lines);
  const summary = doc.createElement("span");
  summary.className = "diff-summary";
  const plus = doc.createElement("span");
  plus.className = "add";
  plus.textContent = `+${add}`;
  const minus = doc.createElement("span");
  minus.className = "del";
  minus.textContent = ` −${del}`;
  summary.append(plus, minus);
  card.querySelector(".block-copy")?.before(summary);
}

/** Table → tab-separated rows, which spreadsheets and notes paste as cells (Android parity). */
export function tableToTsv(table: Element): string {
  return Array.from(table.querySelectorAll("tr"))
    .map((row) =>
      Array.from(row.querySelectorAll("th, td"))
        .map((cell) => (cell.textContent ?? "").replace(/[\t\r\n]+/g, " ").trim())
        .join("\t"),
    )
    .join("\n");
}

/** What a copy button inside a decorated card should put on the clipboard. */
export function copyPayload(button: Element): { kind: CopyKind; text: string } | null {
  const kind = button.getAttribute("data-copy");
  const card = button.closest(".block-card");
  if (!card || (kind !== "code" && kind !== "table")) return null;
  if (kind === "code") {
    const pre = card.querySelector("pre");
    if (!pre) return null;
    const diff = pre.querySelectorAll(".diff-line");
    const text = diff.length ? Array.from(diff, (line) => ((line as HTMLElement).dataset.blank ? "" : line.textContent ?? "")).join("\n") : pre.textContent ?? "";
    return { kind, text: text.replace(/\n$/, "") };
  }
  const table = card.querySelector("table");
  return table ? { kind, text: tableToTsv(table) } : null;
}

// ---- readable text (DESIGN §5.5 查看原文 / 选择: marks removed, structure kept) ----------

const BLOCKS = new Set(["P", "H1", "H2", "H3", "H4", "H5", "H6", "BLOCKQUOTE", "PRE", "UL", "OL", "TABLE", "HR"]);

/**
 * Markdown → readable plain text: emphasis and link syntax gone, code verbatim, list markers and
 * indentation kept, table rows tab-separated. Used by "view source / select" and read-aloud.
 */
export function readableText(source: string): string {
  const fragment = renderMarkdownFragment(source);
  const out: string[] = [];
  const walk = (node: Node, depth: number) => {
    for (const child of Array.from(node.childNodes)) {
      if (child.nodeType === 3) {
        // Markup whitespace between list items / table rows is not content.
        const container = (node as Element).tagName;
        if (container && /^(UL|OL|TABLE|THEAD|TBODY|TR)$/.test(container) && !(child.textContent ?? "").trim()) continue;
        out.push(child.textContent ?? "");
        continue;
      }
      if (child.nodeType !== 1) continue;
      const el = child as Element;
      const tag = el.tagName;
      if (tag === "BR") {
        out.push("\n");
        continue;
      }
      if (tag === "PRE") {
        out.push(`\n${(el.textContent ?? "").replace(/\n$/, "")}\n\n`);
        continue;
      }
      if (tag === "LI") {
        const parent = el.parentElement;
        const marker = parent?.tagName === "OL" ? `${Array.from(parent.children).indexOf(el) + Number(parent.getAttribute("start") ?? 1)}. ` : "• ";
        out.push(`${"  ".repeat(Math.max(0, depth - 1))}${marker}`);
        walk(el, depth);
        out.push("\n");
        continue;
      }
      if (tag === "TR") {
        out.push(Array.from(el.children).map((c) => (c.textContent ?? "").trim()).join("\t") + "\n");
        continue;
      }
      if (tag === "HR") {
        out.push("\n");
        continue;
      }
      walk(el, tag === "UL" || tag === "OL" ? depth + 1 : depth);
      if (BLOCKS.has(tag)) out.push("\n\n");
    }
  };
  walk(fragment, 0);
  return out.join("").replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
}
