// @vitest-environment jsdom
import { expect, it } from "vitest";
import { renderMarkdownFragment } from "../markdown/render";
import { inlinePreviewBlocks, readStaticPreview, staticInlinePreview } from "./InlinePreview";

it("places the preview in its original position and leaves Markdown examples literal", () => {
  const code = '~~~html\n::preview{file="example.html"}\n~~~';
  const quote = '> ::preview{file="example.html"}';
  expect(inlinePreviewBlocks('Before\n\n::preview{file="chart.html" height="400"}\n\nAfter')).toEqual([
    { source: 'Before\n' }, { target: 'chart.html', height: 400 }, { source: '\nAfter' },
  ]);
  for (const source of [code, quote, '    ::preview{file="example.html"}', '::preview{file="chart.html"']) {
    expect(inlinePreviewBlocks(source)).toEqual([{ source }]);
  }
});

it("renders declarative wide SVG with the preview accent while preserving authored colors", () => {
  const svg = '<svg width="880" height="200"><rect fill="var(--accent)" width="20" height="30"/><rect fill="#226688"/></svg>';
  expect(staticInlinePreview(svg, false)).toContain('fill="#8b80e8"');
  expect(staticInlinePreview(svg, true)).toContain('fill="#ada3ff"');
  expect(staticInlinePreview(svg, true)).toContain('fill="#226688"');
  expect(staticInlinePreview(svg, true)).toContain('width="880"');
});

it("uses the download fallback for script or CSS dependent charts and removes interactive content", () => {
  expect(staticInlinePreview('<script>draw()</script><svg></svg>', false)).toBeNull();
  expect(staticInlinePreview('<style>rect { fill: red }</style><svg></svg>', false)).toBeNull();
  const safe = staticInlinePreview('<table onclick="send()"><tr><td>Cell</td></tr></table><a href="/outside">Link</a><img src="/outside"><iframe src="/outside"></iframe>', false);
  expect(safe).toContain("Cell");
  expect(safe).not.toMatch(/outside|onclick|<iframe|<img/);
});

it("shares reference links across a preview and only consumes actual attributes", () => {
  const env = {};
  const source = `[Details][ref]\n\n::preview{file="<https://example.com/chart>" note=\'height="900"\'}\n\n[ref]: https://example.com/ref`;
  const blocks = inlinePreviewBlocks(source, env);
  expect(blocks[1]).toEqual({ target: "https://example.com/chart", height: 280 });
  const before = blocks[0];
  expect(before && "source" in before && renderMarkdownFragment(before.source, env).querySelector("a")?.href)
    .toBe("https://example.com/ref");
  const invalid = `::preview{note='file="hidden.html"'}`;
  expect(inlinePreviewBlocks(invalid)).toEqual([{ source: invalid }]);
});

it("limits streamed previews before concatenating and cancels the remaining download", async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(3));
      controller.enqueue(new Uint8Array(3));
    },
    cancel() { cancelled = true; },
  }));
  expect(await readStaticPreview(response, 5)).toBeUndefined();
  expect(cancelled).toBe(true);
  expect(await readStaticPreview(new Response("图表"), 6)).toBe("图表");
});
