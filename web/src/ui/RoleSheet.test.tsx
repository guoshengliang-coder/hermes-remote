import { render } from "preact";
import { act } from "preact/test-utils";
import { useState } from "preact/hooks";
import { afterEach, expect, it } from "vitest";
import { RoleSheet } from "./RoleSheet";
import { toggleRole } from "../app/roleFilter";
import { resetOverlays } from "../app/overlayHistory";
let host: HTMLElement;
afterEach(() => { act(() => render(null, host)); host.remove(); resetOverlays(); });
it("keeps a multi-select sheet open, supports none/all and exposes checkbox state", () => {
  const names = ["default", "work"];
  function Fixture() {
    const [selection, set] = useState<string[] | null>(null);
    return <RoleSheet names={names} label={name => name} title="筛选角色" closeLabel="关闭" multiple
      selected={name => selection === null || selection.includes(name)} allLabel="全部角色" onAll={() => set(null)}
      onToggle={name => set(previous => toggleRole(previous, names, name))} onClose={() => { throw new Error("toggle must not close"); }}/>;
  }
  host = document.createElement("div"); document.body.append(host);
  act(() => render(<Fixture/>, host));
  act(() => host.querySelectorAll<HTMLButtonElement>('[role="checkbox"]')[1]!.click());
  expect(host.querySelectorAll('[aria-checked="true"]')).toHaveLength(1);
  expect(host.querySelector('[role="dialog"]')).not.toBeNull();
  act(() => host.querySelectorAll<HTMLButtonElement>('[role="checkbox"]')[0]!.click());
  expect(host.querySelectorAll('[aria-checked="true"]')).toHaveLength(0);
  act(() => [...host.querySelectorAll("button")].find(button => button.textContent === "全部角色")!.click());
  expect(host.querySelectorAll('[aria-checked="true"]')).toHaveLength(2);
});
