import markdownIt, { type Env, type MarkdownIt, type RendererRule } from "markdown-it";
import createDOMPurify, { type DOMPurify } from "dompurify";

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

export function sanitizeHtml(html: string): string {
  return getPurifier().sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOWED_URI_REGEXP: /^https:/i,
    // A non-URI attribute must be declared URI-safe, or the https-only regexp rejects its value.
    ADD_URI_SAFE_ATTR: ["start"],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    KEEP_CONTENT: true,
    RETURN_TRUSTED_TYPE: false,
  }) as string;
}

let markdown: MarkdownIt | null = null;

/** Render untrusted Markdown to sanitized HTML safe for innerHTML. */
export function renderMarkdown(source: string): string {
  markdown ??= createMarkdown();
  const env: RenderEnv = { hiddenLinks: [] };
  return sanitizeHtml(markdown.render(source, env));
}
