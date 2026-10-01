import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GatewayClient } from "../api/gateway";
import { AppContext, type AppContextValue } from "../app/store";
import { modelKey, modelPrefs, rememberReasoning } from "../app/localPrefs";
import { HermesSocketError } from "../hermes/client";
import { ModelSheet, type ModelActions } from "./ModelSheet";
import { AccountDrawer } from "./AccountDrawer";

let hosts: HTMLElement[] = [];
beforeEach(() => localStorage.clear());
afterEach(() => { for (const host of hosts) { act(() => render(null, host)); host.remove(); } hosts = []; });
const flush = async () => { await act(async () => { await Promise.resolve(); }); };
const client = () => ({
  modelOptions: vi.fn(async () => ({ providers: [{ slug: "p", name: "Provider", is_current: true, models: ["old", "new", "Model 6 Pro"] }] })),
  defaultModel: vi.fn(async () => ({ model: "old", provider: "p" })),
  setDefaultModel: vi.fn(async () => ({ model: "new", provider: "p" })),
});
const context = (api: object, id = "mac-a", enabled = true) => ({
  t: (zh: string) => zh, language: "zh", client: api,
  device: { deviceId: id, connector: { online: true } },
  features: new Set(["model-select", "default-model", ...(enabled ? ["default-model-write", "session-model-config"] : [])]),
  themeMode: "system", languagePreference: "system", fontSize: "standard",
} as unknown as AppContextValue);
const actions = (): ModelActions => ({ switchModel: vi.fn(async (_p, model) => ({ kind: "applied" as const, model, warning: false })), reasoning: vi.fn(async () => "medium"), setReasoning: vi.fn(async () => {}) });
function mount(node: preact.ComponentChildren, ctx: AppContextValue) {
  const host = document.createElement("div"); document.body.append(host); hosts.push(host);
  act(() => render(<AppContext.Provider value={ctx}>{node}</AppContext.Provider>, host)); return host;
}
function pick(host: HTMLElement, model = "new") {
  const button = [...host.querySelectorAll<HTMLButtonElement>(".model-pick")].find((b) => b.querySelector(".model-name")?.textContent === model)!;
  expect(button).toBeDefined(); act(() => button.click());
}
function textButton(host: HTMLElement, text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === text)!;
}
function sheet(api = client(), a = actions(), scope: "default" | "session" = "session") {
  const done = vi.fn(), close = vi.fn(), reasoning = vi.fn();
  const node = <ModelSheet current={{ model: "old", provider: "p" }} profile="work" explicitOverride={false} scope={scope}
    actions={a} onSwitched={done} onReasoning={reasoning} onClose={close} />;
  const host = mount(node, context(api));
  return { host, api, a, done, close, reasoning, node };
}

it("edits the selected Mac/profile default without touching session reasoning or recent models", async () => {
  const s = sheet(client(), actions(), "default"); await flush();
  expect(s.host.textContent).toContain("当前默认");
  expect(s.host.textContent).not.toContain("推理强度");
  expect(s.host.textContent).not.toContain("恢复默认模型");
  expect(s.a.reasoning).not.toHaveBeenCalled();
  pick(s.host); await flush();
  expect(s.api.setDefaultModel).toHaveBeenCalledWith("mac-a", "p", "new", "work", false);
  expect(s.a.switchModel).not.toHaveBeenCalled();
  expect(s.done).toHaveBeenCalledWith("p", "new", false, false);
  expect(s.close).toHaveBeenCalledOnce();
  expect(modelPrefs("mac-a").recents).toEqual([]);
});

it("keeps default editing read-only on an older Gateway", async () => {
  const old = mount(<AccountDrawer onClose={() => {}} />, context(client(), "a", false)); await flush();
  expect(old.querySelector<HTMLButtonElement>('[aria-label="默认模型"]')!.disabled).toBe(true);
  const enabled = mount(<AccountDrawer onClose={() => {}} />, context(client())); await flush();
  act(() => enabled.querySelector<HTMLButtonElement>('[aria-label="默认模型"]')!.click()); await flush();
  expect(enabled.textContent).toContain("当前默认");
});

it("never commits confirmation-required switches before explicit confirmation, and cancellation keeps the old model", async () => {
  const a = actions(); a.switchModel = vi.fn(async (_p, model, confirmed) => confirmed ? { kind: "applied" as const, model, warning: false } : { kind: "confirmation" as const });
  const s = sheet(client(), a); await flush(); pick(s.host); await flush();
  expect(s.done).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled(); expect(modelPrefs("mac-a").recents).toEqual([]);
  act(() => textButton(s.host, "取消").click()); await flush();
  expect(a.switchModel).toHaveBeenCalledOnce();
  pick(s.host); await flush(); act(() => textButton(s.host, "确认切换").click()); await flush();
  expect(a.switchModel).toHaveBeenLastCalledWith("p", "new", true);
  expect(s.done).toHaveBeenCalledOnce(); expect(s.close).toHaveBeenCalledOnce();
});

it("default-model confirmation is also explicit and is bound to the selected model", async () => {
  const api = client(); api.setDefaultModel = vi.fn(async (_id: string, _p: string, _m: string, _profile: string, confirmed: boolean) => confirmed ? { model: "new", provider: "p" } : { confirm_required: true }) as unknown as typeof api.setDefaultModel;
  const s = sheet(api, actions(), "default"); await flush(); pick(s.host); await flush();
  expect(s.done).not.toHaveBeenCalled();
  act(() => textButton(s.host, "确认切换").click()); await flush();
  expect(api.setDefaultModel).toHaveBeenLastCalledWith("mac-a", "p", "new", "work", true);
  expect(s.done).toHaveBeenCalledOnce();
});

it("deferred switches keep the sheet, current model and recents unchanged", async () => {
  const a = actions(); a.switchModel = vi.fn(async () => ({ kind: "deferred" as const }));
  const s = sheet(client(), a); await flush(); pick(s.host); await flush();
  expect(s.host.textContent).toContain("下一次发送消息");
  expect(s.done).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled(); expect(modelPrefs("mac-a").recents).toEqual([]);
});

it("warnings update the canonical applied model but retain the sheet and hide raw upstream text", async () => {
  const a = actions(); a.switchModel = vi.fn(async () => ({ kind: "applied" as const, model: "canonical", warning: true }));
  const s = sheet(client(), a); await flush(); pick(s.host); await flush();
  expect(s.done).toHaveBeenCalledWith("p", "canonical", false, true);
  expect(s.close).not.toHaveBeenCalled(); expect(s.host.textContent).toContain("可用性仍需检查");
});

it("unanswered switches are unconfirmed and never automatically replayed", async () => {
  const a = actions(); a.switchModel = vi.fn(async () => { throw new HermesSocketError("timeout", "token=CANARY"); });
  const s = sheet(client(), a); await flush(); pick(s.host); await flush();
  expect(s.host.textContent).toContain("HR-RPC-008"); expect(s.host.textContent).not.toContain("CANARY");
  expect(textButton(s.host, "重试")).toBeUndefined(); expect(a.switchModel).toHaveBeenCalledOnce();
  expect(s.done).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled(); expect(modelPrefs("mac-a").recents).toEqual([]);
});

it("reapplies reasoning presets and rolls back a failed effort selection", async () => {
  rememberReasoning("mac-a", modelKey("p", "new"), "high");
  const s = sheet(); await flush(); pick(s.host); await flush();
  expect(s.a.setReasoning).toHaveBeenCalledWith("high"); expect(s.reasoning).toHaveBeenCalledWith("high");
  const a = actions(); a.setReasoning = vi.fn(async () => { throw new Error("CANARY"); });
  const failed = sheet(client(), a); await flush();
  const select = failed.host.querySelector<HTMLSelectElement>("select")!;
  act(() => { select.value = "ultra"; select.dispatchEvent(new Event("change", { bubbles: true })); }); await flush();
  expect(select.value).toBe("medium"); expect(failed.host.textContent).toContain("HR-RPC-006");
});

it("drops pending old Mac results instead of changing the newly selected Mac", async () => {
  let resolve!: (result: { kind: "applied"; model: string; warning: boolean }) => void;
  const a = actions(); a.switchModel = vi.fn(() => new Promise<{ kind: "applied"; model: string; warning: boolean }>((yes) => { resolve = yes; }));
  const s = sheet(client(), a); await flush(); pick(s.host); await flush();
  act(() => render(<AppContext.Provider value={context(s.api, "mac-b")}>{s.node}</AppContext.Provider>, s.host)); await flush();
  await act(async () => resolve({ kind: "applied", model: "private-a", warning: false }));
  expect(s.done).not.toHaveBeenCalled(); expect(s.close).not.toHaveBeenCalled(); expect(s.host.textContent).not.toContain("private-a");
  expect(modelPrefs("mac-b").recents).toEqual([]);
});

// HG-177: the account drawer owns z 44/45 and the shared Sheet is 24/25, so the default-model
// sheet opened from the card page was painted behind the drawer. It must carry the lift; the same
// sheet opened over a page (chat composer) must not, or it would rise above overlays it shouldn't.
it("lifts the default-model sheet above the card page drawer, and only when opened from the card page", async () => {
  const fromCard = mount(<AccountDrawer onClose={() => {}} />, context(client()));
  await flush();
  act(() => fromCard.querySelector<HTMLButtonElement>('[aria-label="默认模型"]')!.click());
  await flush();
  const dialog = fromCard.querySelector('[role="dialog"][aria-label="默认模型"]');
  expect(dialog).not.toBeNull();
  expect(dialog!.classList.contains("above-drawer")).toBe(true);
  expect(fromCard.querySelector(".sheet-scrim.above-drawer")).not.toBeNull();

  const direct = sheet(client(), actions(), "default");
  await flush();
  expect(direct.host.querySelector('[role="dialog"][aria-label="默认模型"]')).not.toBeNull();
  expect(direct.host.querySelector(".picker-sheet")?.classList.contains("above-drawer")).toBe(false);
  expect(direct.host.querySelector(".sheet-scrim.above-drawer")).toBeNull();
});
