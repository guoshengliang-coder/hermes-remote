// @vitest-environment jsdom
// jsdom, not happy-dom: happy-dom's NodeIterator skips the node after a removed one, so DOMPurify
// there silently leaves every other element unsanitized — a sanitizer test on it proves nothing.
import { describe, expect, it } from "vitest";
import { isAllowedHref, renderMarkdown, sanitizeHtml } from "./render";

function dom(html: string): HTMLElement {
  const div = document.createElement("div");
  div.innerHTML = html;
  return div;
}

/** No executable surface survived: no script-ish tags, no on* handlers, no non-https URLs. */
function assertInert(html: string) {
  const root = dom(html);
  expect(root.querySelector("script, img, svg, iframe, object, embed, style, form, input, math, link, meta, base")).toBeNull();
  for (const el of root.querySelectorAll("*")) {
    for (const attr of el.getAttributeNames()) {
      expect(attr.startsWith("on")).toBe(false);
      expect(["href", "target", "rel", "class", "start"]).toContain(attr);
    }
    const href = el.getAttribute("href");
    if (href !== null) expect(href.startsWith("https:")).toBe(true);
  }
}

describe("renderMarkdown basics", () => {
  it("renders common markdown", () => {
    const html = renderMarkdown("# T\n\n**b** _i_ ~~s~~ `c`\n\n- a\n- b\n\n3. x\n4. y\n\n> q\n\n```js\nlet x = 1 < 2;\n```");
    const root = dom(html);
    expect(root.querySelector("h1")?.textContent).toBe("T");
    expect(root.querySelector("strong")?.textContent).toBe("b");
    expect(root.querySelector("s")?.textContent).toBe("s");
    expect(root.querySelector("ol")?.getAttribute("start")).toBe("3");
    expect(root.querySelector("pre code")?.className).toBe("language-js");
    expect(root.querySelector("pre code")?.textContent).toBe("let x = 1 < 2;\n");
    assertInert(html);
  });

  it("turns table alignment into classes, never inline styles", () => {
    const html = renderMarkdown("| a | b |\n|:--|--:|\n| 1 | 2 |");
    expect(html).not.toContain("style=");
    expect(dom(html).querySelector("th")?.className).toBe("md-align-left");
    expect(dom(html).querySelector("td:last-child")?.className).toBe("md-align-right");
  });

  it("makes https links open safely in a new tab", () => {
    const a = dom(renderMarkdown("[docs](https://example.com/x?a=1)")).querySelector("a")!;
    expect(a.getAttribute("href")).toBe("https://example.com/x?a=1");
    expect(a.getAttribute("target")).toBe("_blank");
    expect(a.getAttribute("rel")).toBe("noopener noreferrer");
  });

  it("linkifies bare https URLs but leaves http and fuzzy domains as text", () => {
    const root = dom(renderMarkdown("see https://a.example/p and http://b.example and c.example"));
    const links = [...root.querySelectorAll("a")].map((a) => a.getAttribute("href"));
    expect(links).toEqual(["https://a.example/p"]);
    expect(root.textContent).toContain("http://b.example");
    expect(root.textContent).toContain("c.example");
  });

  it("renders markdown images as link text, never <img>", () => {
    const html = renderMarkdown("![diagram](https://cdn.example/d.png) and ![local](/Users/me/a.png) ![](https://cdn.example/e.png)");
    const root = dom(html);
    expect(root.querySelector("img")).toBeNull();
    const links = [...root.querySelectorAll("a")];
    expect(links.map((a) => [a.textContent, a.getAttribute("href")])).toEqual([
      ["diagram", "https://cdn.example/d.png"],
      ["https://cdn.example/e.png", "https://cdn.example/e.png"],
    ]);
    expect(root.textContent).toContain("local");
  });
});

describe("XSS regressions", () => {
  const vectors: Array<[string, string]> = [
    ["javascript: link", "[x](javascript:alert(1))"],
    ["JaVaScRiPt with whitespace", "[x]( JaVaScRiPt:alert(1) )"],
    ["data: link", "[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)"],
    ["vbscript: link", "[x](vbscript:msgbox(1))"],
    ["file: link", "[x](file:///etc/passwd)"],
    ["raw script", "<script>alert(1)</script>"],
    ["img onerror", '<img src=x onerror="alert(1)">'],
    ["svg onload", "<svg onload=alert(1)><circle/></svg>"],
    ["entity-obfuscated javascript", "[x](&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;alert(1))"],
    ["hex entity javascript", "[x](&#x6A;avascript:alert(1))"],
    ["percent-encoded javascript", "[x](%6A%61%76%61%73%63%72%69%70%74:alert(1))"],
    ["tab inside scheme", "[x](java\tscript:alert(1))"],
    ["autolink javascript", "<javascript:alert(1)>"],
    ["autolink data", "<data:text/html,<script>alert(1)</script>>"],
    ["reference-style javascript", "[click][r]\n\n[r]: javascript:alert(1)"],
    ["reference-style data image", "![p][i]\n\n[i]: data:image/svg+xml,<svg onload=alert(1)>"],
    ["image javascript", "![x](javascript:alert(1))"],
    ["link title breakout", '[x](https://ok.example "a\\" onmouseover=\\"alert(1)")'],
    ["html in link text", "[<img src=x onerror=alert(1)>](https://ok.example)"],
    ["iframe", '<iframe src="https://evil.example"></iframe>'],
    ["style tag", "<style>body{display:none}</style>"],
    ["a tag raw", '<a href="javascript:alert(1)">x</a>'],
    ["math/mathml", "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>"],
  ];

  it.each(vectors)("%s stays inert", (_name, source) => {
    const html = renderMarkdown(source);
    assertInert(html);
    // Tags may survive only as escaped text.
    expect(html.toLowerCase()).not.toMatch(/<(script|img|svg|iframe|style|math|object|embed)\b/);
    expect(html.toLowerCase()).not.toMatch(/<a\b[^>]*href="(?!https:)/);
  });

  it("keeps raw HTML visible as text rather than executing it", () => {
    expect(dom(renderMarkdown("<b>x</b>")).textContent?.trim()).toBe("<b>x</b>");
  });

  it("the sanitizer alone strips dangerous markup and non-https hrefs", () => {
    const html = sanitizeHtml('<p style="color:red" onclick="x()">t</p><a href="http://x.example">h</a><a href="https://ok.example" target="_self">o</a><span class="evil">s</span><code class="language-ts x">c</code><img src=x>');
    assertInert(html);
    const root = dom(html);
    expect(root.querySelector("p")?.getAttributeNames()).toEqual([]);
    expect(root.querySelectorAll("a")[0]?.hasAttribute("href")).toBe(false);
    expect(root.querySelectorAll("a")[1]?.getAttribute("target")).toBe("_blank");
    expect(root.querySelector("code")?.hasAttribute("class")).toBe(false);
    expect(root.textContent).toContain("s");
  });
});

describe("isAllowedHref", () => {
  it("allows only absolute https", () => {
    expect(isAllowedHref("https://a.example")).toBe(true);
    for (const href of ["http://a", "/rel", "mailto:a@b.c", "javascript:x", "data:x", "", null, "HTTPS://A.EXAMPLE".toLowerCase().replace("https", "ftp")]) {
      expect(isAllowedHref(href)).toBe(false);
    }
  });
});
