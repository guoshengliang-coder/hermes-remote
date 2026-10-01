import { render } from "preact";
import { act } from "preact/test-utils";
import { useLayoutEffect } from "preact/hooks";
import { afterEach, expect, it } from "vitest";
import { clearPageSnapshots, pageSnapshotKey, readPageSnapshot } from "./router";
import { useListReturn } from "./useListReturn";

let host: HTMLDivElement | null = null;
let latest: ReturnType<typeof useListReturn<{ query: string }>>;
function Probe({ query = "" }: { query?: string }) {
  const returned = useListReturn("sessions:mac", { query }); latest = returned;
  useLayoutEffect(() => { returned.remember(returned.initial); returned.restore(); });
  return <div class="page-scroll" ref={returned.port}><main /></div>;
}
const mount = () => act(() => render(<Probe />, host!));
afterEach(() => { if (host) { act(() => render(null, host!)); host.remove(); host = null; } clearPageSnapshots(); });
it("restores the frame scroll only after asynchronous results fit, with the original query", () => {
  clearPageSnapshots(); history.replaceState(null, "", "/app/");
  host = document.createElement("div"); document.body.append(host); mount();
  const port = host.querySelector<HTMLDivElement>(".page-scroll")!;
  act(() => { latest.remember({ query: "release" }); port.scrollTop = 420; port.dispatchEvent(new Event("scroll")); });
  act(() => render(null, host!)); mount();
  expect(latest.initial.query).toBe("release");
  const restored = host.querySelector<HTMLDivElement>(".page-scroll")!;
  expect(restored.scrollTop).toBe(0);
  Object.defineProperty(restored, "scrollHeight", { value: 1000 });
  Object.defineProperty(restored, "clientHeight", { value: 300 });
  act(() => latest.restore());
  expect(restored.scrollTop).toBe(420);
  expect(readPageSnapshot(pageSnapshotKey("sessions:mac"))).toEqual({ view: { query: "release" }, scrollTop: 420 });
});
