import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { SessionToast } from "./SessionToast";

// HG-186: the top lifecycle notice must be closable on its own, not only by tapping through to the
// session (which was the previous, sole interaction).

const hosts: HTMLElement[] = [];
afterEach(() => { for (const host of hosts.splice(0)) { render(null, host); host.remove(); } });

const t = (zh: string) => zh;

function mount(props: { title: string; waiting: boolean; onOpen?: () => void; onClose?: () => void }) {
  const host = document.createElement("div");
  document.body.append(host);
  hosts.push(host);
  const onOpen = props.onOpen ?? (() => {});
  const onClose = props.onClose ?? (() => {});
  act(() => render(
    <SessionToast title={props.title} waiting={props.waiting} t={t} onOpen={onOpen} onClose={onClose} />,
    host,
  ));
  return host;
}

it("shows a completed session in green and opens it from the body", () => {
  const onOpen = vi.fn();
  const onClose = vi.fn();
  const host = mount({ title: "查询东京今天天气", waiting: false, onOpen, onClose });

  expect(host.querySelector(".toast-text")?.textContent).toBe("「查询东京今天天气」已完成");
  expect(host.querySelector(".dot")?.classList.contains("dot-good")).toBe(true);

  act(() => host.querySelector<HTMLButtonElement>(".toast-main")!.click());
  expect(onOpen).toHaveBeenCalledTimes(1);
  expect(onClose).not.toHaveBeenCalled();
});

it("shows a waiting session in amber", () => {
  const host = mount({ title: "部署", waiting: true });
  expect(host.querySelector(".toast-text")?.textContent).toBe("「部署」需要你处理");
  expect(host.querySelector(".dot")?.classList.contains("dot-warn")).toBe(true);
});

it("dismisses the notice from the close button without opening the session", () => {
  const onOpen = vi.fn();
  const onClose = vi.fn();
  const host = mount({ title: "查询东京今天天气", waiting: false, onOpen, onClose });

  const close = host.querySelector<HTMLButtonElement>(".toast-close")!;
  expect(close.getAttribute("aria-label")).toBe("关闭提醒");

  act(() => close.click());
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(onOpen).not.toHaveBeenCalled();
});

it("keeps the open and close targets as siblings, never a button inside a button", () => {
  const host = mount({ title: "A", waiting: false });
  expect(host.querySelector(".toast-main button") ?? host.querySelector(".toast button button")).toBeNull();
  expect(host.querySelectorAll(".toast > button")).toHaveLength(2);
});
