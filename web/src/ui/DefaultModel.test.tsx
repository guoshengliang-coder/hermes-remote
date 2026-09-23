import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it } from "vitest";
import type { DefaultModelResponse, GatewayClient } from "../api/gateway";
import { AppContext, type AppContextValue } from "../app/store";
import { AccountDrawer } from "./AccountDrawer";
import { ModelSheet } from "./ModelSheet";

const hosts: HTMLElement[] = [];
afterEach(() => { for (const host of hosts.splice(0)) { render(null, host); host.remove(); } });

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function mount(node: preact.ComponentChildren, value: AppContextValue) {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  act(() => render(<AppContext.Provider value={value}>{node}</AppContext.Provider>, host));
  return host;
}

function context(deviceId: string, client: Partial<GatewayClient>): AppContextValue {
  return {
    t: (zh: string) => zh,
    language: "zh",
    device: { deviceId, desktopDisplayName: deviceId, connector: { online: true } },
    client,
    features: new Set(["default-model"]),
    themeMode: "system",
    languagePreference: "system",
  } as unknown as AppContextValue;
}

it("drops an old Mac's default response and displays only the selected Mac", async () => {
  const first = deferred<DefaultModelResponse>();
  const second = deferred<DefaultModelResponse>();
  const client = { defaultModel: (id: string) => id === "mac-a" ? first.promise : second.promise };
  const host = mount(<AccountDrawer onClose={() => {}} />, context("mac-a", client));
  act(() => render(<AppContext.Provider value={context("mac-b", client)}><AccountDrawer onClose={() => {}} /></AppContext.Provider>, host));
  await act(async () => { first.resolve({ model: "private-a", provider: "a" }); await first.promise; });
  expect(host.textContent).not.toContain("private-a");
  await act(async () => { second.resolve({ model: "model-b", provider: "b" }); await second.promise; });
  expect(host.textContent).toContain("model-b · b");
});

it("restores the profile default through the session action and hides it on read failure", async () => {
  const call: string[] = [];
  const client = {
    defaultModel: async (_id: string, profile: string | null) => {
      call.push(`read:${profile}`);
      return { model: "default-id", provider: "provider" };
    },
    modelOptions: async () => ({ providers: [] }),
  };
  const host = mount(<ModelSheet current={{ model: "custom-id", provider: "provider" }} profile="work" explicitOverride
    actions={{ switchModel: async (provider, model) => { call.push(`switch:${provider}/${model}`); }, reasoning: async () => null, setReasoning: async () => {} }}
    onSwitched={(provider, model, restored) => call.push(`done:${provider}/${model}/${restored}`)} onReasoning={() => {}} onClose={() => {}} />,
  context("mac-a", client));
  await act(async () => { await Promise.resolve(); });
  const restore = [...host.querySelectorAll("button")].find((button) => button.textContent === "恢复默认模型");
  expect(restore).toBeDefined();
  await act(async () => { restore!.click(); await Promise.resolve(); });
  expect(call).toContain("read:work");
  expect(call).toContain("switch:provider/default-id");
  expect(call).toContain("done:provider/default-id/true");

  const failed = { ...client, defaultModel: async () => { throw new Error("token=private-canary"); } };
  const failedHost = mount(<ModelSheet current={{ model: "custom-id", provider: "provider" }} profile="work" explicitOverride
    actions={{ switchModel: async () => {}, reasoning: async () => null, setReasoning: async () => {} }}
    onSwitched={() => {}} onReasoning={() => {}} onClose={() => {}} />, context("mac-b", failed));
  await act(async () => { await Promise.resolve(); });
  expect(failedHost.textContent).toContain("HR-WEB-009");
  expect(failedHost.textContent).not.toContain("private-canary");
  expect(failedHost.textContent).not.toContain("恢复默认模型");
});
