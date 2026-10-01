import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { DevicePicker } from "./DevicePicker";
import { resetOverlays } from "../app/overlayHistory";
const host = document.createElement("div");
document.body.append(host);
afterEach(() => {
  act(() => render(null, host));
  resetOverlays();
});
function mount(onBack?: () => void) {
  act(() =>
    render(
      <DevicePicker
        devices={[]}
        selectedId={null}
        language="zh"
        t={(zh) => zh}
        onSelect={() => {}}
        onSignOut={() => {}}
        onBack={onBack}
      />,
      host,
    ),
  );
}
it("shows an actionable toolbar back only for cancellable Mac switching", () => {
  const back = vi.fn();
  mount(back);
  act(() =>
    host.querySelector<HTMLButtonElement>('[aria-label="返回"]')!.click(),
  );
  expect(back).toHaveBeenCalledTimes(1);
});
it("first-time selection cannot bypass the required Mac gate", () => {
  mount();
  expect(host.querySelector('[aria-label="返回"]')).toBeNull();
});
it("system back closes a cancellable picker", async () => {
  const back = vi.fn();
  mount(back);
  await act(async () => {
    history.back();
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
  expect(back).toHaveBeenCalledTimes(1);
});
