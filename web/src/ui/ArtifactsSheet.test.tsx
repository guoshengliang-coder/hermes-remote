// @vitest-environment jsdom
import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { classifyAttachment } from "../hermes/media";
import { appError } from "../errors";
import { resetOverlays } from "../app/overlayHistory";
import { ArtifactsSheet } from "./ArtifactsSheet";
import { ArtifactPreview, staticArtifactHtml } from "./ArtifactPreview";

const hosts: HTMLElement[] = [];
afterEach(() => { for (const host of hosts.splice(0)) { act(() => render(null, host)); host.remove(); } resetOverlays(); vi.restoreAllMocks(); });
const artifact = { attachment: classifyAttachment("/tmp/report.html"), displayName: "report (2).html", messageKey: "h-8", timestampMs: null };
function mount(node: preact.ComponentChildren, override: Record<string, unknown> = {}) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  const context = { t: (zh: string) => zh, language: "zh", device: null, flash: vi.fn(), client: {}, ...override } as unknown as AppContextValue;
  act(() => render(<AppContext.Provider value={context}>{node}</AppContext.Provider>, host));
  return host;
}
it("restores list scroll, opens the selected result and sends its exact source to context", () => {
  const open = vi.fn(), context = vi.fn(), scroll = vi.fn();
  const host = mount(<ArtifactsSheet artifacts={[artifact]} loading={false} error={null} scrollTop={180}
    onScroll={scroll} onRetry={() => {}} onOpen={open} onContext={context} onClose={() => {}} />);
  expect(host.querySelector<HTMLElement>(".artifacts-list")?.scrollTop).toBe(180);
  act(() => host.querySelector<HTMLButtonElement>(".file-card")!.click());
  expect(open).toHaveBeenCalledWith(artifact);
  act(() => host.querySelector<HTMLButtonElement>(".artifact-details button")!.click());
  expect(context).toHaveBeenCalledWith(artifact);
  expect(host.textContent).toContain("report (2).html");
});
it("does not label an incomplete or failed scan as empty", () => {
  const host = mount(<ArtifactsSheet artifacts={[]} loading error={appError("HR-SYNC-001", "failed page")}
    scrollTop={0} onScroll={() => {}} onRetry={() => {}} onOpen={() => {}} onContext={() => {}} onClose={() => {}} />);
  expect(host.textContent).toContain("正在整理整个会话");
  expect(host.textContent).toContain("HR-SYNC-001");
  expect(host.textContent).not.toContain("暂无产物");
});
it("isolates HTML and removes scripts, redirects, forms and navigation", () => {
  const safe = staticArtifactHtml('<html><head><meta http-equiv="refresh" content="0;url=/api/private"></head><body><script>parent.alert(1)</script><form action="/api/delete"></form><a href="/private">链接</a><iframe src="/private"></iframe><h1>报告</h1></body></html>');
  expect(safe).toContain("default-src 'none'");
  expect(safe).toContain("<h1>报告</h1>");
  expect(safe).not.toMatch(/<script|<iframe|<form|href=|http-equiv="refresh"/i);
});
it("previews HTML in an opaque sandbox and keeps download available", async () => {
  const host = mount(<ArtifactPreview artifact={artifact} onClose={() => {}} />, { device: { deviceId: "mac" },
    client: { deviceApi: async () => ({ blob: async () => ({ size: 20, text: async () => "<h1>报告</h1>" }) }) } });
  await act(async () => { await new Promise((resolve) => setTimeout(resolve, 10)); });
  expect(host.querySelector("iframe")?.getAttribute("sandbox")).toBe("");
  expect(host.querySelector("iframe")?.getAttribute("srcdoc")).toContain("<h1>报告</h1>");
  expect(host.querySelector(".file-card")?.textContent).toContain("点按下载");
});
