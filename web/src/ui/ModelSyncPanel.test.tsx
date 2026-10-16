import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AppContext, type AppContextValue } from "../app/store";
import { ModelSyncPanel } from "./ModelSyncPanel";
import { appError, diagnostics } from "../errors";
const hosts: HTMLElement[] = [];
afterEach(() => {
  for (const h of hosts.splice(0)) {
    render(null, h);
    h.remove();
  }
});
function mount() {
  const importLegacy = vi.fn(),
    discard = vi.fn(),
    reapply = vi.fn();
  const value = {
    language: "zh",
    t: (zh: string) => zh,
    account: { id: "a", email: "dev@example.test" },
    device: { deviceId: "mac" },
    modelSync: {
      view: {
        owner: "a",
        enabled: true,
        ready: true,
        rows: [],
        pending: [],
        error: null,
      },
      client: { importLegacy, discard, reapply },
    },
  } as unknown as AppContextValue;
  const legacy = {
    candidates: [{ provider: "p", model: "m", favorite: true as const }],
    unresolved: 1,
  };
  const host = document.createElement("div");
  hosts.push(host);
  document.body.append(host);
  const draw = () =>
    act(() =>
      render(
        <AppContext.Provider value={value}>
          <ModelSyncPanel legacy={legacy} />
        </AppContext.Provider>,
        host,
      ),
    );
  draw();
  const click = (label: string) =>
    act(() => {
      (
        [...host.querySelectorAll("button")].find(
          (b) => b.textContent === label,
        ) as HTMLButtonElement
      ).click();
    });
  return { host, value, draw, click, importLegacy, discard, reapply };
}
it("never imports before explicit confirmation, preserves unresolved source, and clears confirmation on account change", async () => {
  const m = mount();
  expect(m.importLegacy).not.toHaveBeenCalled();
  expect(m.host.textContent).toContain("无法唯一识别");
  m.click("导入此设备的旧偏好");
  expect(m.importLegacy).not.toHaveBeenCalled();
  expect(m.host.querySelector('[role="alertdialog"]')?.textContent).toContain(
    "dev@example.test",
  );
  m.click("取消");
  expect(m.importLegacy).not.toHaveBeenCalled();
  m.click("导入此设备的旧偏好");
  m.click("确认导入");
  expect(m.importLegacy).toHaveBeenCalledTimes(1);
  m.click("导入此设备的旧偏好");
  m.value.modelSync!.view = { ...m.value.modelSync!.view, owner: "b" };
  m.draw();
  await act(async () => {
    await new Promise((r) => setTimeout(r, 10));
  });
  expect(m.host.querySelector('[role="alertdialog"]')).toBeNull();
});
it("model errors have bilingual retry and diagnostic-redaction contracts", () => {
  for (const code of [
    "HR-SYNC-012",
    "HR-SYNC-013",
    "HR-SYNC-014",
    "HR-SYNC-015",
    "HR-SYNC-016",
    "HR-SYNC-017",
  ] as const) {
    const e = appError(code, "Authorization: Bearer private");
    expect(e.zh).not.toBe(e.en);
    expect(e.retryable).toBe(code === "HR-SYNC-017");
    expect(diagnostics(e)).not.toContain("private");
  }
});
