import MarkdownIt, { type Env } from "markdown-it";
import DOMPurify from "dompurify";
import { useEffect, useRef, useState } from "preact/hooks";
import { useApp } from "../app/store";
import { toAppError } from "../app/failures";
import type { AppError } from "../errors";
import { classifyAttachment } from "../hermes/media";
import { ErrorNotice } from "./ErrorNotice";
import { FileCard } from "./Media";
import { hermesPaths } from "../api/gateway";
import { isAllowedHref } from "../markdown/render";

const markdown = new MarkdownIt();
const directive = /^::preview\{(.*?)\}$/;
const attribute = /([a-z][\w-]{0,63})=(?:"([^"]*)"|'([^']*)')/gi;
const htmlFile = /\.(?:html?|xhtml)$/i;
const maxBytes = 4 * 1024 * 1024;
const accents = { light: "#8b80e8", dark: "#ada3ff" };

type PreviewBlock = { source: string } | { target: string; height: number };

/** Upstream emits standalone paragraphs. Markdown block maps keep examples and lists intact. */
export function inlinePreviewBlocks(source: string, env: Env = {}): PreviewBlock[] {
  if (!source.includes("::preview{")) return [{ source }];
  const text = source.replace(/\r\n?/g, "\n");
  const lines = text.split("\n");
  const blocks: PreviewBlock[] = [];
  let start = 0;
  for (const token of markdown.parse(text, env)) {
    if (token.type !== "paragraph_open" || token.level !== 0 || !token.map) continue;
    const [first, end] = token.map;
    if (end !== first + 1) continue;
    const body = directive.exec((lines[first] ?? "").trim())?.[1];
    if (!body || body.length > 2048 || /[{}]/.test(body)) continue;
    const attributes = new Map(Array.from(body.matchAll(attribute), match =>
      [match[1]?.toLowerCase(), match[2] ?? match[3] ?? ""] as const));
    const target = attributes.get("file")?.trim().replace(/^<(https?:\/\/[^<>\s]+)>$/i, "$1");
    if (!target || /[\x00-\x1f\x7f]/.test(target)) continue;
    const rawHeight = attributes.get("height")?.trim();
    const height = rawHeight && /^-?\d+$/.test(rawHeight) ? Number(rawHeight) : 280;
    if (first > start) blocks.push({ source: lines.slice(start, first).join("\n") });
    blocks.push({ target, height: Math.min(1200, Math.max(120, height)) });
    start = end;
  }
  if (!start) return [{ source }];
  if (start < lines.length) blocks.push({ source: lines.slice(start).join("\n") });
  return blocks;
}

/** Keep only declarative HTML/SVG that can render under the existing production CSP. */
export function staticInlinePreview(source: string, dark: boolean): string | null {
  const parsed = document.createElement("template");
  parsed.innerHTML = source;
  // CSS and script can determine the data's meaning and layout. Avoid passing a partial chart
  // off as complete when the production shell would prevent those declarations from running.
  if (parsed.content.querySelector("script, style, link, [style], canvas, meta[http-equiv]")) return null;
  const safe = DOMPurify.sanitize(source, {
    USE_PROFILES: { html: true, svg: true },
    FORBID_TAGS: ["script", "style", "link", "meta", "base", "iframe", "object", "embed", "form", "foreignObject", "img", "image", "audio", "video"],
    FORBID_ATTR: ["href", "src", "srcset", "xlink:href", "action", "formaction", "style", "ping"],
    RETURN_DOM_FRAGMENT: true,
  });
  if (!safe.querySelector("svg, table")) return null;
  for (const node of safe.querySelectorAll("[fill], [stroke], [stop-color]")) {
    for (const attribute of ["fill", "stroke", "stop-color"]) {
      if (node.getAttribute(attribute)?.trim() === "var(--accent)") {
        node.setAttribute(attribute, dark ? accents.dark : accents.light);
      }
    }
  }
  const content = document.createElement("div");
  content.append(safe);
  return '<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; base-uri \'none\'; form-action \'none\'">' + content.innerHTML;
}

export async function readStaticPreview(response: Response, limit = maxBytes): Promise<string | undefined> {
  const reader = response.body?.getReader();
  if (!reader) return;
  const decoder = new TextDecoder();
  const chunks: string[] = [];
  let size = 0;
  try {
    if (Number(response.headers.get("content-length")) > limit) {
      await reader.cancel();
      return;
    }
    while (true) {
      const { done, value } = await reader.read();
      if (done) return chunks.join("") + decoder.decode();
      size += value.byteLength;
      if (size > limit) {
        await reader.cancel();
        return;
      }
      chunks.push(decoder.decode(value, { stream: true }));
    }
  } finally {
    reader.releaseLock();
  }
}

export function InlinePreview({ target, cwd, height }: { target: string; cwd?: string; height: number }) {
  const { client, device, account, language, t } = useApp();
  const url = /^https?:\/\//i.test(target);
  const local = target.replace(/^file:\/\//i, "");
  const path = !url && !/^[a-z][a-z0-9+.-]*:/i.test(local)
    ? local.startsWith("/") ? local : cwd?.startsWith("/") ? cwd.replace(/\/+$/, "") + "/" + local : null
    : null;
  const deviceId = device?.deviceId;
  const container = useRef<HTMLElement>(null);
  const [nearby, setNearby] = useState(false);
  const [attempt, retry] = useState(0);
  const [result, setResult] = useState<{ text?: string; error?: AppError }>();
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme === "dark");
  const html = !!path && htmlFile.test(path);
  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => setDark(root.dataset.theme === "dark"));
    observer.observe(root, { attributes: true, attributeFilter: ["data-theme"] });
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!html || !container.current) return;
    if (typeof IntersectionObserver === "undefined") {
      setNearby(true);
      return;
    }
    const observer = new IntersectionObserver(entries => {
      if (!entries.some(entry => entry.isIntersecting)) return;
      setNearby(true);
      observer.disconnect();
    }, { rootMargin: "350px" });
    observer.observe(container.current);
    return () => observer.disconnect();
  }, [html, path]);
  useEffect(() => {
    setResult(undefined);
    if (!nearby || !deviceId || !path || !html) return;
    const controller = new AbortController();
    let active = true;
    client.deviceApi<Response>(deviceId, "GET", hermesPaths.file(path), { raw: true, signal: controller.signal })
      .then(async response => ({ text: await readStaticPreview(response) }))
      .then(value => { if (active) setResult(value); })
      .catch((error: unknown) => { if (active) setResult({ error: toAppError(error, "download") }); });
    return () => { active = false; controller.abort(); };
  }, [client, deviceId, account?.id, path, html, nearby, attempt]);

  if (url) return isAllowedHref(target) ? <a href={target} target="_blank" rel="noopener noreferrer">{target}</a> : <span>{target}</span>;
  if (!path) return <span>{target}</span>;
  const attachment = classifyAttachment(path, "file-directive");
  if (!html) return <FileCard attachment={attachment} />;
  const srcDoc = result?.text ? staticInlinePreview(result.text, dark) : null;
  return <section ref={container} class="inline-html-preview">
    <FileCard attachment={attachment} />
    {result?.error ? <ErrorNotice error={result.error} language={language} onRetry={() => retry(n => n + 1)} /> :
      !result ? <p role="status">{t("正在打开…", "Opening…")}</p> :
      srcDoc ? <iframe title={attachment.name} sandbox="" srcDoc={srcDoc} height={height} referrerPolicy="no-referrer" /> :
      <p>{t("此图表请下载后查看。", "Download this chart to view it.")}</p>}
  </section>;
}
