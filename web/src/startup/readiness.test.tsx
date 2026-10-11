import { render } from "preact";
import { useRef } from "preact/hooks";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { StartupContext, useStartupPage } from "./readiness";
import { appError, type AppError } from "../errors";
import type { Route } from "../app/router";

let host: HTMLElement;
const report = vi.fn();
function Probe({ page, ready, error, busy }: { page: Route['name']; ready: boolean; error?: AppError; busy?: boolean }) {
  const root = useRef<HTMLDivElement>(null);
  useStartupPage(page, ready, error, root);
  return <div ref={root}><div class="markdown" aria-busy={busy} /></div>;
}
beforeEach(() => { host = document.createElement('div'); document.body.append(host); report.mockClear(); });
afterEach(() => { act(() => render(null, host)); host.remove(); });
async function draw(page: Route['name'], ready: boolean, busy = false, error?: AppError) {
  await act(async () => render(<StartupContext.Provider value={{ target: 'chat', report }}><Probe page={page} ready={ready} busy={busy} error={error} /></StartupContext.Provider>, host));
}
it("a loaded split sidebar cannot dismiss the initial chat gate", async () => {
  await draw('list', true);
  expect(report).not.toHaveBeenCalled();
});
it("waits for first history and deferred Markdown paint, but not subsequent background data", async () => {
  await draw('chat', false); expect(report).not.toHaveBeenCalled();
  await draw('chat', true, true); expect(report).not.toHaveBeenCalled();
  host.querySelector('.markdown')!.setAttribute('aria-busy', 'false');
  await act(async () => {});
  expect(report).toHaveBeenCalledWith();
});
it("reports the original structured error instead of an endless loading state", async () => {
  const error = appError('HR-SYNC-003');
  await draw('chat', false, false, error);
  expect(report).toHaveBeenCalledWith(error);
});
