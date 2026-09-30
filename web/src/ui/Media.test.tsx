import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it } from "vitest";
import { GatewayHttpError } from "../api/gateway";
import { AppContext, type AppContextValue } from "../app/store";
import { MacImage } from "./Media";

// A chat image that cannot be fetched draws one small broken-image cell, never an error card
// (HG-167, DESIGN §5.4 / Android ChatImages.kt). The old shape stacked a full-width `ErrorNotice`
// per failed image — the Connector refusing a Mac path outside FILES_ROOT arrives as
// HR-FILE-003 — so a two-image message read as "the content will not load". These tests pin the
// cell, its accessible label, and that a picture which does arrive is untouched.

const hosts: HTMLElement[] = [];
afterEach(() => {
  for (const host of hosts.splice(0)) {
    render(null, host);
    host.remove();
  }
});

function mount(node: preact.ComponentChildren, overrides: Record<string, unknown>): HTMLElement {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  const language = overrides.language === "en" ? "en" : "zh";
  const context = {
    language,
    t: (zh: string, en: string) => (language === "en" ? en : zh),
    device: null,
    client: {},
    ...overrides,
  } as unknown as AppContextValue;
  act(() => render(<AppContext.Provider value={context}>{node}</AppContext.Provider>, host));
  return host;
}

/** Let the fetch chain behind `MacImage` settle: cache read, network, blob URL. */
async function settle(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

const forbidden = () => ({
  deviceApi: async () => {
    throw new GatewayHttpError(403, { error: "forbidden" }, "files");
  },
});
const device = { deviceId: "dev-1" };

it("keeps the cell and shows the broken-image glyph when the file cannot be fetched", async () => {
  const host = mount(<MacImage path="/tmp/hg167-refused-a.png" name="a.png" />, { device, client: forbidden() });
  await settle();
  expect(host.querySelector(".media-failed")).not.toBeNull();
  expect(host.querySelector(".media-failed .error-notice")).toBeNull();
  expect(host.querySelector(".error-notice")).toBeNull();
  // Inert like Android: there is nothing to open and nothing to tap into a retry.
  expect(host.querySelector("button")).toBeNull();
});

it("labels the failed cell for assistive tech in the app language", async () => {
  const zh = mount(<MacImage path="/tmp/hg167-refused-zh.png" name="zh.png" />, { device, client: forbidden() });
  await settle();
  expect(zh.querySelector(".media-failed")?.getAttribute("aria-label")).toBe("图片加载失败");

  const en = mount(<MacImage path="/tmp/hg167-refused-en.png" name="en.png" />, { device, client: forbidden(), language: "en" });
  await settle();
  expect(en.querySelector(".media-failed")?.getAttribute("aria-label")).toBe("Image unavailable");
});

it("still shows a picture that arrives, with no failure cell", async () => {
  const client = {
    deviceApi: async () => ({ blob: async () => new Blob(["png"], { type: "image/png" }) }),
  };
  const host = mount(<MacImage path="/tmp/hg167-ok.png" name="ok.png" onOpen={() => undefined} />, { device, client });
  await settle();
  expect(host.querySelector(".media-image")).not.toBeNull();
  expect(host.querySelector(".media-failed")).toBeNull();
});
